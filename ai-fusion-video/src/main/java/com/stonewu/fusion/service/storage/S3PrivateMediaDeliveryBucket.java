package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.service.media.model.MediaException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.TransitionStorageClass;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

record S3PrivateMediaDeliveryBucket(S3Client client, ResolvedObjectStorageConfig config, URI origin)
        implements PrivateMediaDeliveryBucket {

    @Override
    public String toString() {
        return "S3PrivateMediaDeliveryBucket";
    }

    @Override
    public void verify(String key) {
        var block = client.getPublicAccessBlock(request -> request.bucket(config.bucketName())).publicAccessBlockConfiguration();
        if (block == null || !Boolean.TRUE.equals(block.blockPublicAcls()) || !Boolean.TRUE.equals(block.ignorePublicAcls())
                || !Boolean.TRUE.equals(block.blockPublicPolicy()) || !Boolean.TRUE.equals(block.restrictPublicBuckets())) {
            throw MediaException.unavailable("S3 交付桶必须开启全部阻止公共访问选项");
        }
        requireNoBucketPolicy();
        if (client.getBucketVersioning(request -> request.bucket(config.bucketName())).status() != null) {
            throw MediaException.unavailable("S3 交付桶必须关闭版本控制");
        }
        var rules = client.getBucketLifecycleConfiguration(request -> request.bucket(config.bucketName())).rules();
        if (rules.stream().noneMatch(this::expiresDeliveryObjects)) {
            throw MediaException.unavailable("S3 交付桶需要为 afv-media-delivery/ 前缀配置至少 4 天的过期规则");
        }
        Instant retentionCutoff = Instant.now().plus(OBJECT_RETENTION_DAYS, ChronoUnit.DAYS);
        if (rules.stream().filter(rule -> appliesTo(rule, key)).anyMatch(rule -> endsReadabilityEarly(rule, retentionCutoff))) {
            throw MediaException.unavailable("S3 交付对象存在提前删除或归档规则，无法保证链接在 72 小时内可读");
        }
    }

    private void requireNoBucketPolicy() {
        try {
            client.getBucketPolicy(request -> request.bucket(config.bucketName()));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 && failure.awsErrorDetails() != null
                    && "NoSuchBucketPolicy".equals(failure.awsErrorDetails().errorCode())) {
                return;
            }
            throw failure;
        }
        throw MediaException.unavailable("S3 交付桶应使用 IAM 授权，桶策略需为空");
    }

    private boolean expiresDeliveryObjects(LifecycleRule rule) {
        if (rule.status() != ExpirationStatus.ENABLED || rule.expiration() == null
                || rule.expiration().days() == null || rule.expiration().days() < OBJECT_RETENTION_DAYS) {
            return false;
        }
        var filter = rule.filter();
        if (filter != null && (filter.tag() != null || filter.and() != null
                || filter.objectSizeGreaterThan() != null || filter.objectSizeLessThan() != null)) {
            return false;
        }
        String prefix = filter == null ? rule.prefix() : filter.prefix();
        return PREFIX.equals(prefix);
    }

    private boolean appliesTo(LifecycleRule rule, String key) {
        if (rule.status() != ExpirationStatus.ENABLED) {
            return false;
        }
        var filter = rule.filter();
        var combined = filter == null ? null : filter.and();
        // 交付对象无需标签，带标签的规则不会匹配。
        if (filter != null && (filter.tag() != null || (combined != null && !combined.tags().isEmpty()))) {
            return false;
        }
        String prefix = filter == null ? rule.prefix() : combined == null ? filter.prefix() : combined.prefix();
        return prefix == null || key.startsWith(prefix);
    }

    private boolean endsReadabilityEarly(LifecycleRule rule, Instant retentionCutoff) {
        var expiry = rule.expiration();
        return (expiry != null && beforeRetention(expiry.days(), expiry.date(), retentionCutoff))
                || rule.transitions().stream().anyMatch(transition ->
                (transition.storageClass() == TransitionStorageClass.GLACIER
                        || transition.storageClass() == TransitionStorageClass.DEEP_ARCHIVE)
                        && beforeRetention(transition.days(), transition.date(), retentionCutoff));
    }

    private boolean beforeRetention(Integer days, Instant date, Instant retentionCutoff) {
        return (days != null && days < OBJECT_RETENTION_DAYS) || (date != null && date.isBefore(retentionCutoff));
    }

    @Override
    public void put(String key, String mimeType, InputStream data, long length) {
        var request = PutObjectRequest.builder().bucket(config.bucketName()).key(key).contentType(mimeType)
                .contentLength(length).contentDisposition("inline").cacheControl(CACHE_CONTROL)
                .serverSideEncryption(ServerSideEncryption.AES256).storageClass(StorageClass.STANDARD)
                .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofMinutes(4))).build();
        client.putObject(request, RequestBody.fromInputStream(data, length));
    }

    @Override
    public String signRead(String key, Duration lifetime, String contentDisposition) {
        return ObjectStorageClients.signS3Read(config, origin, key, lifetime, contentDisposition);
    }

    @Override
    public void delete(String key) {
        client.deleteObject(request -> request.bucket(config.bucketName()).key(key)
                .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))));
    }
}
