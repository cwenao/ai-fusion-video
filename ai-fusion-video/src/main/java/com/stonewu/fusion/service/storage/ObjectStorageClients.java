package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.service.media.model.MediaException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.transport.TransportConfig;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class ObjectStorageClients {

    private final S3ClientFactory s3Clients;
    private final Map<TosClientKey, TOSV2> tosClients = new ConcurrentHashMap<>();

    public ObjectStorageClient forConfig(ResolvedObjectStorageConfig config) {
        return forConfig(config, false);
    }

    public ObjectStorageClient forManagedMedia(ResolvedObjectStorageConfig config) {
        return forConfig(config, true);
    }

    private ObjectStorageClient forConfig(ResolvedObjectStorageConfig config, boolean managedMedia) {
        if (StorageProviderRegistry.VOLCENGINE_TOS.equals(config.provider())) {
            return new TosObjectStorageClient(tosClient(config, config.endpoint()), config.bucketName());
        }
        return new S3ObjectStorageClient(s3Clients.getClient(config), config.bucketName(), managedMedia);
    }

    public String signBrowserRead(ResolvedObjectStorageConfig config, String key, Duration lifetime) {
        if (StorageProviderRegistry.VOLCENGINE_TOS.equals(config.provider())) {
            // 数据面的浏览器 GET 使用同地域公网地址，服务端仍可通过内网端点读加密头。
            return tosClient(config, tosPublicEndpoint(config)).preSignedURL(new PreSignedURLInput()
                    .setHttpMethod(HttpMethod.GET).setBucket(config.bucketName()).setKey(key)
                    .setExpires(lifetime.toSeconds())).getSignedUrl();
        }
        return signS3Read(config, s3BrowserOrigin(config), key, lifetime, null);
    }

    public PrivateMediaDeliveryBucket forPrivateMediaDelivery(ResolvedObjectStorageConfig config) {
        URI endpoint = httpsOrigin(config.endpoint(), "交付桶服务端点必须使用 HTTPS");
        if (StorageProviderRegistry.VOLCENGINE_TOS.equals(config.provider())) {
            String domain = config.customDomain();
            boolean customDomain = domain != null && !domain.isBlank();
            if (customDomain && !domain.contains("://")) {
                domain = "https://" + domain.trim();
            }
            URI browserOrigin = customDomain
                    ? httpsOrigin(domain, "TOS 交付桶需要填写已绑定的 HTTPS 自定义域名")
                    : URI.create(tosPublicEndpoint(config));
            return new TosPrivateMediaDeliveryBucket(tosClient(config, endpoint.toString()), config.bucketName(),
                    browserOrigin, customDomain);
        }
        return new S3PrivateMediaDeliveryBucket(s3Clients.getClient(config), config, s3BrowserOrigin(config));
    }

    static String signS3Read(ResolvedObjectStorageConfig config, URI origin, String key, Duration lifetime,
                             String contentDisposition) {
        try (var signer = S3Presigner.builder().region(Region.of(config.signingRegion())).endpointOverride(origin)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(config.pathStyleAccessEnabled())
                        .checksumValidationEnabled(false).build()).build()) {
            var signed = signer.presignGetObject(request -> request.signatureDuration(lifetime)
                    .getObjectRequest(object -> object.bucket(config.bucketName()).key(key)
                            .responseContentDisposition(contentDisposition)));
            if (!signed.isBrowserExecutable()) {
                throw MediaException.unavailable("无法签发可在浏览器读取的 S3 地址");
            }
            return signed.url().toExternalForm();
        }
    }

    private URI s3BrowserOrigin(ResolvedObjectStorageConfig config) {
        var options = StorageConfigOptions.parseOrEmpty(config.source().getOptions());
        String browserEndpoint = StorageConfigOptions.getString(options, "browserEndpoint");
        return httpsOrigin(browserEndpoint == null ? config.endpoint() : browserEndpoint,
                "S3 媒体直读需要浏览器可达的 HTTPS 服务端点");
    }

    private String tosPublicEndpoint(ResolvedObjectStorageConfig config) {
        return config.profile().endpointTemplate().replace("{region}", config.region());
    }

    private URI httpsOrigin(String value, String invalidMessage) {
        if (value == null || value.isBlank()) {
            throw MediaException.unavailable(invalidMessage);
        }
        URI origin;
        try {
            origin = URI.create(value.trim().replaceAll("/+$", ""));
        } catch (IllegalArgumentException invalid) {
            throw MediaException.unavailable(invalidMessage);
        }
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || !origin.getPath().isEmpty() || origin.getQuery() != null || origin.getFragment() != null) {
            throw MediaException.unavailable(invalidMessage);
        }
        return origin;
    }

    private TOSV2 tosClient(ResolvedObjectStorageConfig config, String endpoint) {
        var key = new TosClientKey(config.source().getId(), endpoint, config.region(),
                config.accessKey(), config.secretKey());
        return tosClients.computeIfAbsent(key, ignored -> newTosClient(config, endpoint));
    }

    public <T> T withProbeClient(ResolvedObjectStorageConfig config, Function<ObjectStorageClient, T> action) {
        if (StorageProviderRegistry.VOLCENGINE_TOS.equals(config.provider())) {
            TOSV2 client = newTosClient(config, config.endpoint());
            try {
                return action.apply(new TosObjectStorageClient(client, config.bucketName()));
            } finally {
                closeTosClient(client);
            }
        }
        return action.apply(forConfig(config));
    }

    public void invalidate(Long configId) {
        s3Clients.invalidate(configId);
        tosClients.forEach((key, client) -> {
            if (Objects.equals(key.configId(), configId) && tosClients.remove(key, client)) {
                closeTosClient(client);
            }
        });
    }

    @PreDestroy
    public void closeAll() {
        tosClients.values().forEach(this::closeTosClient);
        tosClients.clear();
    }

    private TOSV2 newTosClient(ResolvedObjectStorageConfig config, String endpoint) {
        var transport = TransportConfig.builder().writeTimeoutMills(120_000).build();
        return new TOSV2ClientBuilder().build(config.region(), endpoint, config.accessKey(),
                config.secretKey(), transport);
    }

    private void closeTosClient(TOSV2 client) {
        try {
            client.close();
        } catch (IOException failure) {
            log.warn("关闭 TOS 客户端失败", failure);
        }
    }

    private record TosClientKey(Long configId, String endpoint, String region, String accessKey, String secretKey) {
    }
}
