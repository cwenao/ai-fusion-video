package com.stonewu.fusion.service.media;

import com.stonewu.fusion.repository.media.MediaResourceRepository;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaResource;
import com.stonewu.fusion.service.storage.ObjectStorageClients;
import com.stonewu.fusion.service.storage.ObjectStorageConfigResolver;
import com.stonewu.fusion.service.storage.StorageProviderRegistry;
import com.stonewu.fusion.service.storage.StorageTypes;
import com.volcengine.tos.TosException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class TosMediaPlayback {

    private static final int HEADER_SIZE = 24;
    private final MediaResourceRepository resources;
    private final MediaBlobStore blobs;
    private final ObjectStorageConfigResolver configurations;
    private final ObjectStorageClients clients;
    private final MediaCryptor cryptor;

    static boolean isPlayable(MediaResource resource) {
        return switch (resource.content().kind()) {
            case IMAGE, VIDEO, AUDIO -> true;
            default -> false;
        };
    }

    boolean usesTos(String blobId) {
        var blob = resources.blob(blobId);
        return StorageTypes.S3.equals(blob.backend())
                && StorageProviderRegistry.VOLCENGINE_TOS.equals(blobs.configuration(blob).getProvider());
    }

    public Grant issue(MediaAccessTickets.Authorized access) {
        MediaIoBoundary.requireOutsideTransaction();
        var resource = access.resource();
        var blob = resources.blob(access.blobId());
        var configuration = blobs.configuration(blob);
        if (!isPlayable(resource) || !StorageProviderRegistry.VOLCENGINE_TOS.equals(configuration.getProvider())) {
            throw new MediaException(400, "INVALID_TOS_PLAYBACK", "此媒体不支持 TOS 浏览器直读");
        }
        var config = configurations.resolve(configuration);
        String object = blobs.objectKey(configuration, blob.objectKey());
        var client = clients.forManagedMedia(config);
        try {
            var metadata = client.head(object);
            if (blob.storedBytes() == null || metadata.size() != blob.storedBytes()
                    || metadata.size() < HEADER_SIZE + 16 || metadata.etag() == null) {
                throw new IOException("媒体对象长度或版本不符");
            }
            byte[] header = client.getRange(object, metadata.etag(), 0, HEADER_SIZE - 1, metadata.size());
            byte[] key = cryptor.playbackKey(blob.keyset(), header, resource.id());
            try {
                long seconds = Duration.between(Instant.now(), access.expiresAt()).toSeconds();
                if (seconds < 1) {
                    throw new MediaException(403, "MEDIA_TICKET_DENIED", "媒体访问凭据已失效");
                }
                return new Grant(clients.signTosBrowserRead(config, object, seconds), encode(key),
                        encode(Arrays.copyOfRange(header, 17, HEADER_SIZE)), resource.content().mimeType(),
                        resource.content().byteSize(), metadata.size(), access.expiresAt());
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IOException | TosException failure) {
            throw MediaException.unavailable("无法准备 TOS 媒体播放");
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record Grant(String url, String key, String noncePrefix, String mimeType, long size,
                        long ciphertextSize, Instant expiresAt) {
        @Override
        public String toString() {
            return "TosMediaPlayback.Grant";
        }
    }
}
