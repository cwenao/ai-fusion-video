package com.stonewu.fusion.service.storage;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.entity.storage.StorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Tests storage configuration without changing application state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageConnectionTestService {

    private static final String LOCAL_DEFAULT_BASE_PATH = "./data/media";

    private final ObjectStorageConfigResolver configResolver;
    private final ObjectStorageClients clients;
    private final StorageUrlResolver storageUrlResolver;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(25, TimeUnit.MINUTES)
            .followRedirects(true)
            .build();

    public StorageConnectionTestResult test(StorageConfig config) {
        String type = StorageTypes.normalizeType(config.getType());
        if (StorageTypes.LOCAL.equals(type)) {
            return testLocal(config);
        }
        if (StorageTypes.S3.equals(type)) {
            return testObjectStorage(config);
        }
        return StorageConnectionTestResult.failure("不支持的存储类型: " + config.getType(), null);
    }

    private StorageConnectionTestResult testLocal(StorageConfig config) {
        String basePath = StrUtil.blankToDefault(config.getBasePath(), LOCAL_DEFAULT_BASE_PATH);
        try {
            Path dir = Paths.get(basePath).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, ".afv-storage-test-", ".txt");
            try {
                Files.writeString(temp, "ok");
            } finally {
                Files.deleteIfExists(temp);
            }
            return StorageConnectionTestResult.success("本地存储目录可写", dir.toString());
        } catch (Exception e) {
            return StorageConnectionTestResult.failure("本地存储测试失败: " + e.getMessage(), null);
        }
    }

    private StorageConnectionTestResult testObjectStorage(StorageConfig config) {
        var resolved = configResolver.resolve(config);
        try {
            return clients.withProbeClient(resolved, client -> probe(resolved, client));
        } catch (RuntimeException failure) {
            return StorageConnectionTestResult.failure("对象存储测试失败: " + failure.getMessage(), null);
        }
    }

    private StorageConnectionTestResult probe(ResolvedObjectStorageConfig config, ObjectStorageClient client) {
        String prefix = StrUtil.isBlank(config.basePath()) ? ""
                : config.basePath().replace('\\', '/').replaceAll("^/+|/+$", "") + "/";
        String objectKey = prefix + ".healthcheck/" + IdUtil.fastSimpleUUID() + ".txt";
        String publicUrl = null;
        try {
            client.putBytes(objectKey, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
            publicUrl = storageUrlResolver.resolvePublicUrl(config, objectKey);
            StorageConnectionTestResult publicResult = verifyPublicUrl(config, publicUrl);
            if (!publicResult.success()) {
                return publicResult;
            }
            return StorageConnectionTestResult.success("对象存储上传与公开访问均正常", publicUrl);
        } catch (Exception e) {
            return StorageConnectionTestResult.failure("对象存储测试失败: " + e.getMessage(), publicUrl);
        } finally {
            try {
                client.delete(objectKey);
            } catch (Exception e) {
                log.warn("[StorageTest] 删除健康检查对象失败: key={}, error={}", objectKey, e.getMessage());
            }
        }
    }

    private StorageConnectionTestResult verifyPublicUrl(ResolvedObjectStorageConfig config,
                                                        String publicUrl) throws IOException {
        Request head = new Request.Builder().url(publicUrl).head().build();
        try (Response response = httpClient.newCall(head).execute()) {
            if (response.isSuccessful()) {
                return StorageConnectionTestResult.success("公开访问正常", publicUrl);
            }
            if (response.code() != 405) {
                return StorageConnectionTestResult.failure(publicAccessFailureMessage(config, response.code()),
                        publicUrl);
            }
        }

        Request get = new Request.Builder().url(publicUrl).get().build();
        try (Response response = httpClient.newCall(get).execute()) {
            if (response.isSuccessful()) {
                return StorageConnectionTestResult.success("公开访问正常", publicUrl);
            }
            return StorageConnectionTestResult.failure(publicAccessFailureMessage(config, response.code()), publicUrl);
        }
    }

    private String publicAccessFailureMessage(ResolvedObjectStorageConfig config, int statusCode) {
        if (statusCode == 403) {
            return "对象已上传，但公开访问失败（HTTP 403）。当前使用公有 Bucket/CDN 直链模式，请确认 "
                    + config.profile().label()
                    + " 的 Bucket 或对象允许匿名读取，或填写已放行的公开域名/CDN。";
        }
        if (statusCode == 404) {
            return "对象已上传，但公开访问地址返回 HTTP 404。请检查公开域名/CDN 是否指向当前 Bucket，或等待 CDN/权限配置生效后重试。";
        }
        return "对象已上传，但公开访问失败（HTTP " + statusCode + "）。请检查 Bucket 读权限、公开域名/CDN 和防盗链配置。";
    }
}
