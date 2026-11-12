package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.entity.storage.StorageConfig;

/**
 * 对象存储配置及 S3 兼容厂商的协议选项。
 */
public record ResolvedObjectStorageConfig(
        StorageConfig source,
        StorageProviderProfile profile,
        String provider,
        String endpoint,
        String bucketName,
        String accessKey,
        String secretKey,
        String region,
        String signingRegion,
        String basePath,
        String customDomain,
        boolean pathStyleAccessEnabled,
        boolean chunkedEncodingEnabled,
        String checksumCalculation
) {
}
