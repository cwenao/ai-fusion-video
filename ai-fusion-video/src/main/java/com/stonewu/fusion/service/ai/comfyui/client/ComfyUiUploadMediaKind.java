package com.stonewu.fusion.service.ai.comfyui.client;

import com.stonewu.fusion.common.BusinessException;

import java.util.Locale;
import java.util.Map;

/**
 * ComfyUI 输入媒体类别：图片、参考视频、参考音频，各自的 MIME 白名单与大小上限。
 */
public enum ComfyUiUploadMediaKind {
    IMAGE("图片", "image/", "image/*", 20 * 1024 * 1024, Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/jpg", "jpg",
            "image/webp", "webp", "image/gif", "gif", "image/bmp", "bmp",
            "image/tiff", "tiff")),
    VIDEO("参考视频", "video/", "video/*", 200 * 1024 * 1024, Map.of(
            "video/mp4", "mp4", "video/webm", "webm", "video/quicktime", "mov",
            "video/x-matroska", "mkv", "video/x-msvideo", "avi", "video/x-m4v", "m4v")),
    AUDIO("参考音频", "audio/", "audio/*", 50 * 1024 * 1024, Map.of(
            "audio/wav", "wav", "audio/x-wav", "wav", "audio/wave", "wav",
            "audio/mpeg", "mp3", "audio/flac", "flac", "audio/x-flac", "flac",
            "audio/mp4", "m4a", "audio/x-m4a", "m4a", "audio/ogg", "ogg",
            "audio/aac", "aac"));

    private final String label;
    private final String typePrefix;
    private final String acceptHeader;
    private final int maxBytes;
    private final Map<String, String> extensions;

    ComfyUiUploadMediaKind(String label, String typePrefix, String acceptHeader, int maxBytes,
                           Map<String, String> extensions) {
        this.label = label;
        this.typePrefix = typePrefix;
        this.acceptHeader = acceptHeader;
        this.maxBytes = maxBytes;
        this.extensions = extensions;
    }

    public String label() {
        return label;
    }

    public String typePrefix() {
        return typePrefix;
    }

    public String acceptHeader() {
        return acceptHeader;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public String limitMessage() {
        return "单个" + label + "输入不能超过 " + maxBytes / (1024 * 1024) + "MB";
    }

    public void requireContentType(String contentType) {
        if (contentType == null || !extensions.containsKey(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(400, "ComfyUI 输入文件不是受支持的" + label + "格式");
        }
    }

    public String extensionFor(String contentType) {
        String extension = contentType == null ? null : extensions.get(contentType.toLowerCase(Locale.ROOT));
        if (extension == null) {
            throw new BusinessException(400, "ComfyUI 输入文件不是受支持的" + label + "格式");
        }
        return extension;
    }
}
