package com.stonewu.fusion.service.media;

import com.stonewu.fusion.repository.media.MediaResourceRepository;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaResource;
import com.stonewu.fusion.service.storage.ObjectStorageClients;
import com.stonewu.fusion.service.storage.ObjectStorageConfigResolver;
import com.stonewu.fusion.service.storage.StorageTypes;
import com.volcengine.tos.TosException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class ObjectStorageMediaPlayback {

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

    boolean supports(MediaResource resource, String blobId) {
        return isPlayable(resource) && StorageTypes.S3.equals(resources.blob(blobId).backend());
    }

    public Grant issue(MediaAccessTickets.Authorized access) {
        MediaIoBoundary.requireOutsideTransaction();
        var resource = access.resource();
        var blob = resources.blob(access.blobId());
        if (!isPlayable(resource) || !StorageTypes.S3.equals(blob.backend())) {
            throw MediaException.invalid("此媒体不支持对象存储直读");
        }
        var configuration = blobs.configuration(blob);
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
                return new Grant(clients.signBrowserRead(config, object, Duration.ofSeconds(seconds)), encode(key),
                        encode(Arrays.copyOfRange(header, 17, HEADER_SIZE)), resource.content().mimeType(),
                        resource.content().byteSize(), metadata.size(), access.expiresAt());
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IOException | TosException | SdkException failure) {
            throw MediaException.unavailable("无法准备对象存储媒体直读");
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record Grant(String url, String key, String noncePrefix, String mimeType, long size,
                        long ciphertextSize, Instant expiresAt) {
        @Override
        public String toString() {
            return "ObjectStorageMediaPlayback.Grant";
        }
    }
}
