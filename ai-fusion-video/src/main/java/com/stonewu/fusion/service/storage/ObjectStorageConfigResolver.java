package com.stonewu.fusion.service.storage;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.storage.StorageConfig;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Resolves persisted storage configuration into object storage client settings.
 */
@Component
public class ObjectStorageConfigResolver {

    private final StorageProviderRegistry providerRegistry;

    public ObjectStorageConfigResolver(StorageProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public ResolvedObjectStorageConfig resolve(StorageConfig config) {
        if (config == null) {
            throw new BusinessException("对象存储配置为空");
        }
        if (!StorageTypes.isObjectStorage(config.getType())) {
            throw new BusinessException("不是对象存储配置: " + config.getType());
        }

        String provider = resolveProvider(config);
        StorageProviderProfile profile = providerRegistry.get(provider);

        String region = StrUtil.blankToDefault(config.getRegion(), profile.defaultRegion());
        boolean tos = StorageProviderRegistry.VOLCENGINE_TOS.equals(provider);
        if (tos) {
            if (region == null || !region.trim().matches("[a-z0-9]+(?:-[a-z0-9]+)+")) {
                throw new BusinessException("火山引擎 TOS 需要填写存储桶地域，例如 cn-beijing");
            }
            region = region.trim();
        }
        String endpoint = resolveEndpoint(config.getEndpoint(), profile, region);
        validateRequired(config, endpoint, profile);
        if (tos) {
            validateTosEndpoint(endpoint);
            return new ResolvedObjectStorageConfig(config, profile, provider, endpoint, config.getBucketName(),
                    config.getAccessKey(), config.getSecretKey(), region, null, config.getBasePath(),
                    config.getCustomDomain(), false, false, null);
        }

        JSONObject options = StorageConfigOptions.parseOrEmpty(config.getOptions());
        String signingRegion = StrUtil.blankToDefault(
                StorageConfigOptions.getString(options, "signingRegion"),
                StrUtil.blankToDefault(region, "us-east-1")
        );

        return new ResolvedObjectStorageConfig(
                config,
                profile,
                provider,
                endpoint,
                config.getBucketName(),
                config.getAccessKey(),
                config.getSecretKey(),
                region,
                signingRegion,
                config.getBasePath(),
                config.getCustomDomain(),
                StorageConfigOptions.getBoolean(options, "pathStyleAccessEnabled", profile.pathStyleAccessEnabled()),
                StorageConfigOptions.getBoolean(options, "chunkedEncodingEnabled", profile.chunkedEncodingEnabled()),
                StrUtil.blankToDefault(StorageConfigOptions.getString(options, "checksumCalculation"), "WHEN_REQUIRED")
        );
    }

    public String resolveProvider(StorageConfig config) {
        String legacyProvider = StorageTypes.legacyProvider(config.getType());
        if (StrUtil.isNotBlank(legacyProvider)) {
            return legacyProvider;
        }
        return StrUtil.blankToDefault(config.getProvider(), StorageProviderRegistry.GENERIC_S3);
    }

    private String resolveEndpoint(String configuredEndpoint, StorageProviderProfile profile, String region) {
        if (StrUtil.isNotBlank(configuredEndpoint)) {
            return normalizeEndpoint(configuredEndpoint);
        }
        if (StrUtil.isBlank(profile.endpointTemplate())) {
            return null;
        }
        if (StrUtil.isBlank(region)) {
            throw new BusinessException(profile.label() + " 未配置 endpoint 时必须填写 region");
        }
        return normalizeEndpoint(profile.endpointTemplate().replace("{region}", region));
    }

    public String normalizeEndpoint(String endpoint) {
        if (StrUtil.isBlank(endpoint)) {
            return null;
        }
        String value = endpoint.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        return value.replaceAll("/+$", "");
    }

    private void validateTosEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException failure) {
            throw new BusinessException("火山引擎 TOS 访问端点格式无效");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new BusinessException("火山引擎 TOS 访问端点应为地域服务域名");
        }
    }

    private void validateRequired(StorageConfig config, String endpoint, StorageProviderProfile profile) {
        if (StrUtil.isBlank(endpoint) && profile.endpointRequired()) {
            throw new BusinessException("S3 存储配置缺少 endpoint");
        }
        if (StrUtil.isBlank(config.getBucketName())) {
            throw new BusinessException(profile.label() + " 缺少存储桶名称");
        }
        if (StrUtil.isBlank(config.getAccessKey()) || StrUtil.isBlank(config.getSecretKey())) {
            throw new BusinessException(profile.label() + " 缺少 Access Key 或 Secret Key");
        }
    }
}
