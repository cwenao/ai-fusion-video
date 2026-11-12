package com.stonewu.fusion.service.storage.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.storage.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 保存普通媒体并生成对象访问地址。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ObjectStorageStrategy implements StorageStrategy {

    private static final long REMOTE_STREAMING_UPLOAD_THRESHOLD = 32L * 1024 * 1024;

    private final ObjectStorageConfigResolver configResolver;
    private final ObjectStorageClients clients;
    private final StorageUrlResolver storageUrlResolver;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(25, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Override
    public String getType() {
        return StorageTypes.S3;
    }

    @Override
    public String store(String remoteUrl, String subDir, StorageConfig config) {
        ResolvedObjectStorageConfig resolved = configResolver.resolve(config);

        Request request = new Request.Builder().url(remoteUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new RuntimeException("下载文件失败: HTTP " + response.code() + " url=" + remoteUrl);
            }

            String contentType = response.header("Content-Type");
            String extension = guessExtension(remoteUrl, contentType);
            String objectKey = buildObjectKey(resolved, subDir, extension);
            long contentLength = body.contentLength();

            if (contentLength >= 0 && contentLength <= REMOTE_STREAMING_UPLOAD_THRESHOLD) {
                try (InputStream inputStream = body.byteStream()) {
                    clients.forConfig(resolved).putStream(objectKey, contentType, inputStream, contentLength);
                }
                logUploaded(resolved, objectKey, contentLength);
            } else {
                Path tempFile = Files.createTempFile("afv-object-upload-", "." + extension);
                try {
                    try (InputStream inputStream = body.byteStream()) {
                        Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    clients.forConfig(resolved).putFile(objectKey, contentType, tempFile);
                    logUploaded(resolved, objectKey, Files.size(tempFile));
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }

            return storageUrlResolver.resolvePublicUrl(resolved, objectKey);
        } catch (IOException e) {
            throw new RuntimeException("对象存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeBytes(byte[] data, String subDir, String extension, StorageConfig config) {
        ResolvedObjectStorageConfig resolved = configResolver.resolve(config);

        String objectKey = buildObjectKey(resolved, subDir, extension);
        String contentType = guessContentType(extension);
        clients.forConfig(resolved).putBytes(objectKey, contentType, data);

        logUploaded(resolved, objectKey, data.length);
        return storageUrlResolver.resolvePublicUrl(resolved, objectKey);
    }

    @Override
    public String storeFile(Path filePath, String subDir, String extension, StorageConfig config) {
        ResolvedObjectStorageConfig resolved = configResolver.resolve(config);

        String objectKey = buildObjectKey(resolved, subDir, extension);
        String contentType = guessContentType(extension);
        try {
            clients.forConfig(resolved).putFile(objectKey, contentType, filePath);
        } catch (IOException e) {
            throw new RuntimeException("读取待上传文件失败: " + e.getMessage(), e);
        }

        try {
            logUploaded(resolved, objectKey, Files.size(filePath));
        } catch (IOException e) {
            log.info("[ObjectStorage] 文件已上传: provider={}, key={}", resolved.provider(), objectKey);
        }
        return storageUrlResolver.resolvePublicUrl(resolved, objectKey);
    }

    private String buildObjectKey(ResolvedObjectStorageConfig config, String subDir, String extension) {
        String prefix = StrUtil.isNotBlank(config.basePath())
                ? config.basePath().replace("\\", "/").replaceAll("^/+|/+$", "") + "/"
                : "";
        String safeSubDir = sanitizePath(subDir, "uploads");
        String safeExtension = sanitizeExtension(extension);
        return prefix + safeSubDir + "/" + IdUtil.fastSimpleUUID() + "." + safeExtension;
    }

    private String sanitizePath(String value, String defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        String normalized = value.replace("\\", "/").replaceAll("^/+|/+$", "");
        StringBuilder result = new StringBuilder();
        for (String part : normalized.split("/")) {
            String safe = part.replaceAll("[^A-Za-z0-9._-]", "");
            if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(safe);
        }
        return result.isEmpty() ? defaultValue : result.toString();
    }

    private String sanitizeExtension(String extension) {
        if (StrUtil.isBlank(extension)) {
            return "bin";
        }
        String value = extension.replace(".", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (value.isBlank() || value.length() > 10) {
            return "bin";
        }
        return value;
    }

    private void logUploaded(ResolvedObjectStorageConfig config, String objectKey, long size) {
        log.info("[ObjectStorage] 文件已上传: provider={}, key={}, size={} bytes, url={}",
                config.provider(), objectKey, size, storageUrlResolver.resolvePublicUrl(config, objectKey));
    }

    /**
     * 推断文件扩展名。
     */
    private String guessExtension(String url, String contentType) {
        if (StrUtil.isNotBlank(contentType)) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.contains("png")) {
                return "png";
            }
            if (lower.contains("jpeg") || lower.contains("jpg")) {
                return "jpg";
            }
            if (lower.contains("gif")) {
                return "gif";
            }
            if (lower.contains("webp")) {
                return "webp";
            }
            if (lower.contains("svg")) {
                return "svg";
            }
            if (lower.contains("mp4")) {
                return "mp4";
            }
            if (lower.contains("webm")) {
                return "webm";
            }
            if (lower.contains("mov") || lower.contains("quicktime")) {
                return "mov";
            }
        }
        try {
            String path = url.split("\\?")[0];
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx > 0) {
                String ext = path.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
                if (ext.length() <= 10) {
                    return sanitizeExtension(ext);
                }
            }
        } catch (Exception ignored) {
        }
        return "bin";
    }

    private String guessContentType(String extension) {
        return switch (sanitizeExtension(extension)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }
}
