package com.stonewu.fusion.service.media;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.entity.media.MediaAccessTicketEntity;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.mapper.execution.ExecutionCoordinationMapper;
import com.stonewu.fusion.mapper.media.MediaAccessTicketMapper;
import com.stonewu.fusion.mapper.storage.StorageConfigMapper;
import com.stonewu.fusion.repository.media.MediaResourceRepository;
import com.stonewu.fusion.repository.media.MediaResourceRepository.Row;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.model.MediaBlob;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaFacts;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.media.model.MediaResource;
import com.stonewu.fusion.service.storage.ObjectStorageClients;
import com.stonewu.fusion.service.storage.ObjectStorageConfigResolver;
import com.stonewu.fusion.service.storage.PrivateMediaDeliveryBucket;
import com.stonewu.fusion.service.storage.StorageProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class MediaCopyLinksTests {
    private static final String ID = "20000000-0000-0000-0000-000000000001";
    private static final String BLOB = "20000000-0000-0000-0000-000000000002";
    private static final CanvasActor ACTOR = new CanvasActor(17L, null);
    private static final byte[] ORIGINAL = {1, 3, 5, 7, 9};
    private static final Duration LIFETIME = Duration.ofHours(72);
    private static final String STORAGE_ORIGIN = "https://delivery.example.test/";

    private final MediaResourceRepository resources = mock(MediaResourceRepository.class);
    private final MediaAccessPolicy access = mock(MediaAccessPolicy.class);
    private final MediaAccessTickets tickets = mock(MediaAccessTickets.class);
    private final MediaBlobStore blobs = mock(MediaBlobStore.class);
    private final StorageConfigMapper configurations = mock(StorageConfigMapper.class);
    private final ObjectStorageClients clients = mock(ObjectStorageClients.class);
    private final PrivateMediaDeliveryBucket bucket = mock(PrivateMediaDeliveryBucket.class);
    private final ObjectStorageConfigResolver resolver = new ObjectStorageConfigResolver(new StorageProviderRegistry());
    private final MediaCopyLinks links = new MediaCopyLinks(resources, access, tickets, blobs, configurations, resolver,
            clients, 0);

    @Test
    void copiesDecryptedBytesToTheDefaultPrivateBucketAndSignsTheDeliveryObject(@TempDir Path directory) throws Exception {
        var row = managedSource(directory);
        Instant beforeSigning = Instant.now();

        var link = links.issue(ID, ACTOR);

        assertThat(link.url()).startsWith(STORAGE_ORIGIN + PrivateMediaDeliveryBucket.PREFIX).endsWith("?signature=one");
        assertThat(link.expiresAt()).isBetween(beforeSigning.plus(LIFETIME).minusSeconds(1), Instant.now().plus(LIFETIME));
        String key = link.url().substring(STORAGE_ORIGIN.length(), link.url().indexOf('?'));
        verify(bucket).put(eq(key), eq("video/mp4"), any(InputStream.class), eq((long) ORIGINAL.length));
        verify(bucket, never()).delete(anyString());
        verifyNoInteractions(tickets);
        assertThat(key).doesNotContain(row.blobId(), row.resource().id());
    }

    @Test
    void localCopyUsesA72HourTicketWhileNormalBrowserAccessUsesFiveMinutes() {
        var row = row(2);
        when(resources.get(ID, false)).thenReturn(row);
        when(resources.blob(BLOB)).thenReturn(blob("local", new byte[0], 20));
        var clock = mock(ExecutionCoordinationMapper.class);
        when(clock.now()).thenReturn(LocalDateTime.parse("2026-11-19T06:00:00"));
        var ticketStore = mock(MediaAccessTicketMapper.class);
        var realTickets = new MediaAccessTickets(ticketStore, clock, resources, access, null, mock(ObjectStorageMediaPlayback.class));
        var localLinks = new MediaCopyLinks(resources, access, realTickets, blobs, configurations, resolver, clients, 0);

        var copied = localLinks.issue(ID, ACTOR);
        assertThat(copied.url()).startsWith("/api/media-content/" + ID + "?ticket=");
        assertThat(copied.expiresAt()).isEqualTo(Instant.parse("2026-11-22T06:00:00Z"));
        assertThat(realTickets.browser(ID, ACTOR, false).expiresAt()).isEqualTo(Instant.parse("2026-11-19T06:05:00Z"));

        var inserted = ArgumentCaptor.forClass(MediaAccessTicketEntity.class);
        verify(ticketStore, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(MediaAccessTicketEntity::getExpiresAt).containsExactly(
                LocalDateTime.parse("2026-11-22T06:00:00"), LocalDateTime.parse("2026-11-19T06:05:00"));
        verifyNoInteractions(configurations, clients, bucket, blobs);
    }

    @Test
    void requestedDownloadSignsAReadablePrivateCopyForFiveMinutes(@TempDir Path directory) throws Exception {
        managedSource(directory);
        Instant beforeSigning = Instant.now();

        var download = links.download(ID, ACTOR);

        assertThat(download.url()).startsWith(STORAGE_ORIGIN + PrivateMediaDeliveryBucket.PREFIX);
        assertThat(download.expiresAt()).isBetween(beforeSigning.plusSeconds(299), Instant.now().plusSeconds(300));
        var disposition = ArgumentCaptor.forClass(String.class);
        verify(bucket).signRead(anyString(), eq(Duration.ofMinutes(5)), disposition.capture());
        assertThat(disposition.getValue()).startsWith("attachment;").contains("filename*=UTF-8''");
        verifyNoInteractions(tickets);
    }

    @Test
    void failedAccessCannotReadTheBlobOrPrepareTheBucket() {
        var row = row(2);
        when(resources.get(ID, false)).thenReturn(row);
        doThrow(new MediaException(403, "MEDIA_ACCESS_DENIED", "无权访问此媒体资源")).when(access).ready(row.resource(), ACTOR);

        assertThatThrownBy(() -> links.issue(ID, ACTOR)).isInstanceOf(MediaException.class).hasMessage("无权访问此媒体资源");
        verify(resources, never()).blob(anyString());
        verifyNoInteractions(configurations, clients, bucket, blobs, tickets);
    }

    @Test
    void rejectingTheBucketStopsBeforeDecryptingTheMedia() {
        var row = row(2);
        when(resources.get(ID, false)).thenReturn(row);
        when(resources.blob(BLOB)).thenReturn(blob("s3", new byte[0], 20));
        defaultBucket();
        doThrow(MediaException.unavailable("交付桶访问策略未通过验证")).when(bucket).verify(anyString());

        assertThatThrownBy(() -> links.issue(ID, ACTOR)).isInstanceOf(MediaException.class).hasMessage("交付桶访问策略未通过验证");
        verify(bucket, never()).put(anyString(), anyString(), any(InputStream.class), anyLong());
        verifyNoInteractions(blobs, tickets);
    }

    @Test
    void mediaChangesDuringUploadRemoveTheCopyAndIssueNoLink(@TempDir Path directory) throws Exception {
        var original = managedSource(directory);
        when(resources.get(ID, false)).thenReturn(original, row(3));

        assertThatThrownBy(() -> links.issue(ID, ACTOR)).isInstanceOf(MediaException.class).hasMessage("媒体已更新，请重新获取链接");
        var capture = ArgumentCaptor.forClass(String.class);
        verify(bucket).put(capture.capture(), eq("video/mp4"), any(InputStream.class), eq((long) ORIGINAL.length));
        verify(bucket).delete(capture.getValue());
        verify(bucket, never()).signRead(anyString(), any(), any());
    }

    @Test
    void refusesToTransferFromWithinATransactionSynchronizationScope() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> links.issue(ID, ACTOR)).isInstanceOf(IllegalTransactionStateException.class);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(resources, bucket, blobs);
    }

    private Row managedSource(Path directory) throws Exception {
        var row = row(2);
        var cryptor = new MediaCryptor();
        var keyset = cryptor.newKeyset();
        var source = Files.write(directory.resolve("source.mp4"), ORIGINAL);
        var encrypted = directory.resolve("source.afvblob");
        try (var stream = Files.newOutputStream(encrypted)) {
            cryptor.encrypt(source, stream, keyset, ID);
        }
        var blob = blob("s3", keyset, Files.size(encrypted));
        when(resources.get(ID, false)).thenReturn(row);
        when(resources.blob(BLOB)).thenReturn(blob);
        when(blobs.open(blob)).thenAnswer(call -> cryptor.decrypt(Files.newByteChannel(encrypted), keyset, ID));
        defaultBucket();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(call.<InputStream>getArgument(2).readAllBytes()).containsExactly(ORIGINAL);
            return null;
        }).when(bucket).put(anyString(), eq("video/mp4"), any(InputStream.class), eq((long) ORIGINAL.length));
        when(bucket.signRead(anyString(), any(), any())).thenAnswer(call -> STORAGE_ORIGIN + call.getArgument(0) + "?signature=one");
        return row;
    }

    private void defaultBucket() {
        var config = StorageConfig.builder().id(9L).name("tos-01").type("s3").isDefault(true)
                .provider(StorageProviderRegistry.VOLCENGINE_TOS).region("cn-shanghai").bucketName("fixture-private")
                .accessKey("fixture-ak").secretKey("fixture-sk").build();
        var page = new Page<StorageConfig>(1, 1, false);
        page.setRecords(List.of(config));
        when(configurations.selectPage(any(), any())).thenReturn(page);
        when(configurations.selectById(9L)).thenReturn(config);
        when(clients.forPrivateMediaDelivery(any())).thenReturn(bucket);
    }

    private Row row(int version) {
        var facts = new MediaFacts(MediaKind.VIDEO, "video/mp4", ORIGINAL.length, "digest", 8, 8, 1000L, false);
        var resource = new MediaResource(ID, 21L, 1, ACTOR.userId(), ACTOR.userId(), "成片.mp4", facts, "READY",
                version, null, null, null);
        return new Row(resource, "request", BLOB, 1, null, null);
    }

    private MediaBlob blob(String backend, byte[] keyset, long size) {
        return new MediaBlob(BLOB, ID, 9L, backend, BLOB + ".afvblob", keyset, size);
    }
}
