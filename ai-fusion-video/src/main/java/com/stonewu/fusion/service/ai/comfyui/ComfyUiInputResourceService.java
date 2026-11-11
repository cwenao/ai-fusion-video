package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadMediaKind;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadResult;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Loads platform media references and uploads them through ComfyUI /upload/image.
 * 支持图片、参考视频、参考音频三类输入，各自校验类型与大小。
 */
@Service
public class ComfyUiInputResourceService {

    private final ComfyUiNativeClient nativeClient;
    private final OkHttpClient baseClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build();

    public ComfyUiInputResourceService(ComfyUiNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    public List<String> uploadImages(ApiConfig apiConfig,
                                     String taskKey,
                                     String field,
                                     List<String> sources) {
        return uploadMedia(apiConfig, taskKey, field, sources, ComfyUiUploadMediaKind.IMAGE);
    }

    public List<String> uploadMedia(ApiConfig apiConfig,
                                    String taskKey,
                                    String field,
                                    List<String> sources,
                                    ComfyUiUploadMediaKind mediaKind) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<String> uploaded = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            MediaBytes media = loadMedia(apiConfig, sources.get(index), mediaKind);
            String extension = mediaKind.extensionFor(media.contentType());
            String fileName = sanitizePart(taskKey) + "-" + sanitizePart(field)
                    + "-" + index + "." + extension;
            ComfyUiUploadResult result = nativeClient.uploadMedia(
                    apiConfig, media.bytes(), fileName, media.contentType(), "ai-fusion-video",
                    mediaKind);
            uploaded.add(result.workflowValue());
        }
        return List.copyOf(uploaded);
    }

    private MediaBytes loadMedia(ApiConfig apiConfig, String source, ComfyUiUploadMediaKind kind) {
        if (StrUtil.isBlank(source)) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + "输入不能为空");
        }
        String value = source.trim();
        if (value.startsWith("data:")) {
            return parseDataUri(value, kind);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadHttp(apiConfig, value, kind);
        }
        throw new BusinessException(400,
                "ComfyUI " + kind.label() + "输入必须先转换为 Data URI 或公网 URL；本地素材请启用 data_uri 传输");
    }

    private MediaBytes parseDataUri(String value, ComfyUiUploadMediaKind kind) {
        int comma = value.indexOf(',');
        if (comma <= 5) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + " Data URI 格式无效");
        }
        String metadata = value.substring(5, comma);
        String[] metadataParts = metadata.split(";");
        boolean base64 = false;
        for (int index = 1; index < metadataParts.length; index++) {
            if ("base64".equalsIgnoreCase(metadataParts[index].trim())) {
                base64 = true;
                break;
            }
        }
        if (!base64) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + " Data URI 必须使用 base64");
        }
        String contentType = metadataParts[0].trim().toLowerCase(Locale.ROOT);
        kind.requireContentType(contentType);
        String encoded = value.substring(comma + 1);
        int maxEncodedLength = 4 * ((kind.maxBytes() + 2) / 3);
        if (encoded.length() > maxEncodedLength) {
            throw new BusinessException(400, "ComfyUI " + kind.limitMessage());
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            requireSize(bytes.length, kind);
            return new MediaBytes(bytes, contentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + " Data URI base64 无效");
        }
    }

    private MediaBytes downloadHttp(ApiConfig apiConfig, String value, ComfyUiUploadMediaKind kind) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + " URL 无效");
        }
        if (uri.getHost() == null) {
            throw new BusinessException(400, "ComfyUI " + kind.label() + " URL 缺少主机");
        }
        Request request = new Request.Builder().url(value)
                .header("Accept", kind.acceptHeader()).get().build();
        OkHttpClient client = AiProxySupport.okHttpClient(baseClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502,
                        "下载 ComfyUI " + kind.label() + "输入失败，HTTP " + response.code());
            }
            long length = response.body().contentLength();
            if (length > kind.maxBytes()) {
                throw new BusinessException(400, "ComfyUI " + kind.limitMessage());
            }
            byte[] bytes = readBounded(response.body().byteStream(), kind.maxBytes(), kind.limitMessage());
            requireSize(bytes.length, kind);
            if (response.body().contentType() == null) {
                throw new BusinessException(502, "ComfyUI " + kind.label() + "输入响应缺少 Content-Type");
            }
            String contentType = response.body().contentType().toString()
                    .split(";", 2)[0].toLowerCase(Locale.ROOT);
            kind.requireContentType(contentType);
            return new MediaBytes(bytes, contentType);
        } catch (IOException e) {
            throw new BusinessException(502,
                    "下载 ComfyUI " + kind.label() + "输入异常: "
                            + StrUtil.blankToDefault(e.getMessage(), "I/O error"));
        }
    }

    private void requireSize(int size, ComfyUiUploadMediaKind kind) {
        if (size <= 0 || size > kind.maxBytes()) {
            throw new BusinessException(400, "ComfyUI " + kind.label()
                    + "输入大小必须在 1B 到 " + kind.maxBytes() / (1024 * 1024) + "MB 之间");
        }
    }

    static byte[] readBounded(InputStream input, int maxBytes, String limitMessage) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maxBytes - read) {
                    throw new BusinessException(400, "ComfyUI " + limitMessage);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    private String sanitizePart(String value) {
        String normalized = StrUtil.blankToDefault(value, "task")
                .replaceAll("[^a-zA-Z0-9_-]", "-");
        return normalized.getBytes(StandardCharsets.UTF_8).length > 64
                ? normalized.substring(0, 64) : normalized;
    }

    private record MediaBytes(byte[] bytes, String contentType) {
    }
}
