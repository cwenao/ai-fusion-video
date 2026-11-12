package com.stonewu.fusion.service.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.transport.TransportConfig;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    public String signTosBrowserRead(ResolvedObjectStorageConfig config, String key, long seconds) {
        // 上传和服务端读取可以使用内网 Endpoint。浏览器使用同地域的 HTTPS 公网域名。
        String endpoint = config.profile().endpointTemplate().replace("{region}", config.region());
        return tosClient(config, endpoint).preSignedURL(new PreSignedURLInput().setHttpMethod(HttpMethod.GET)
                .setBucket(config.bucketName()).setKey(key).setExpires(seconds)).getSignedUrl();
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
