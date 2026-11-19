package com.stonewu.fusion.service.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.mapper.storage.StorageConfigMapper;
import com.stonewu.fusion.repository.media.MediaResourceRepository;
import com.stonewu.fusion.repository.media.MediaResourceRepository.Row;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.model.MediaBlob;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.storage.ObjectStorageClients;
import com.stonewu.fusion.service.storage.ObjectStorageConfigResolver;
import com.stonewu.fusion.service.storage.PrivateMediaDeliveryBucket;
import com.stonewu.fusion.service.storage.StorageTypes;
import com.volcengine.tos.TosException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.nio.channels.Channels;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.stonewu.fusion.service.storage.PrivateMediaDeliveryBucket.LINK_LIFETIME;

@Component
@Slf4j
public class MediaCopyLinks {
    private final MediaResourceRepository resources;
    private final MediaAccessPolicy access;
    private final MediaAccessTickets tickets;
    private final MediaBlobStore blobs;
    private final StorageConfigMapper configurations;
    private final ObjectStorageConfigResolver objectConfig;
    private final ObjectStorageClients clients;
    private final long deliveryConfigId;

    public MediaCopyLinks(MediaResourceRepository resources, MediaAccessPolicy access, MediaAccessTickets tickets,
                          MediaBlobStore blobs, StorageConfigMapper configurations, ObjectStorageConfigResolver objectConfig,
                          ObjectStorageClients clients,
                          @Value("${app.media.delivery.storage-config-id:0}") long deliveryConfigId) {
        this.resources = resources;
        this.access = access;
        this.tickets = tickets;
        this.blobs = blobs;
        this.configurations = configurations;
        this.objectConfig = objectConfig;
        this.clients = clients;
        this.deliveryConfigId = deliveryConfigId;
    }

    public record Link(String url, Instant expiresAt) {
        @Override
        public String toString() {
            return "MediaCopyLinks.Link";
        }
    }

    public Link issue(String id, CanvasActor actor) {
        MediaIoBoundary.requireOutsideTransaction();
        var row = resources.get(id, false);
        access.ready(row.resource(), actor);
        var kind = row.resource().content().kind();
        if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) {
            throw MediaException.invalid("仅支持复制图片和视频链接");
        }
        var blob = resources.blob(row.blobId());
        return switch (blob.backend()) {
            case StorageTypes.LOCAL -> {
                var issued = tickets.copyLocal(id, actor);
                yield new Link(issued.url(), issued.expiresAt());
            }
            case StorageTypes.S3 -> publish(row, blob, actor);
            default -> throw MediaException.unavailable("当前媒体存储类型无法签发复制链接");
        };
    }

    private Link publish(Row row, MediaBlob blob, CanvasActor actor) {
        String id = row.resource().id();
        String key = PrivateMediaDeliveryBucket.PREFIX + UUID.randomUUID();
        var bucket = deliveryBucket();
        try {
            bucket.verify(key);
        } catch (SdkException | TosException failure) {
            log.warn("无法验证私有交付桶，错误类型={}", failure.getClass().getSimpleName());
            throw MediaException.unavailable("无法验证私有交付桶，请检查桶配置与读取权限");
        }

        var facts = row.resource().content();
        try {
            try (var source = blobs.open(blob)) {
                if (source.size() != facts.byteSize()) {
                    throw new IOException("媒体内容长度与登记不符");
                }
                bucket.put(key, facts.mimeType(), Channels.newInputStream(source), facts.byteSize());
            }
            Row latest = resources.get(id, false);
            access.ready(latest.resource(), actor);
            if (latest.resource().version() != row.resource().version() || !latest.blobId().equals(row.blobId())) {
                throw new MediaException(409, "MEDIA_DELIVERY_VERSION_CHANGED", "媒体已更新，请重新复制链接");
            }
            Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(LINK_LIFETIME);
            return new Link(bucket.signRead(key), expiresAt);
        } catch (IOException | SdkException | TosException failure) {
            discard(bucket, key);
            log.warn("生成媒体交付链接失败，媒体编号={}，错误类型={}", id, failure.getClass().getSimpleName());
            throw MediaException.unavailable("无法生成对象存储直链，请检查媒体和私有交付桶");
        } catch (RuntimeException failure) {
            discard(bucket, key);
            throw failure;
        }
    }

    private PrivateMediaDeliveryBucket deliveryBucket() {
        StorageConfig target = deliveryConfigId > 0 ? configurations.selectById(deliveryConfigId)
                : configurations.selectPage(new Page<>(1, 1, false), Wrappers.lambdaQuery(StorageConfig.class)
                        .eq(StorageConfig::getIsDefault, true).eq(StorageConfig::getStatus, 1)
                        .orderByAsc(StorageConfig::getId)).getRecords().stream().findFirst().orElse(null);
        if (target == null || !Integer.valueOf(1).equals(target.getStatus()) || !StorageTypes.isObjectStorage(target.getType())) {
            throw MediaException.unavailable("请配置已启用的私有对象存储作为媒体交付目标");
        }
        return clients.forPrivateMediaDelivery(objectConfig.resolve(target));
    }

    private void discard(PrivateMediaDeliveryBucket bucket, String key) {
        try {
            bucket.delete(key);
        } catch (SdkException | TosException failure) {
            log.warn("媒体交付副本即时清理失败，等待对象存储过期清理，错误类型={}", failure.getClass().getSimpleName());
        }
    }
}
