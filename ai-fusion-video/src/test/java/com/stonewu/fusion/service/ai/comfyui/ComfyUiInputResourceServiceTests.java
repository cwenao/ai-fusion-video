package com.stonewu.fusion.service.ai.comfyui;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadMediaKind;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComfyUiInputResourceServiceTests {

    @Mock
    private ComfyUiNativeClient nativeClient;

    @Test
    void uploadImagesDecodesSupportedDataUriBeforeNativeUpload() {
        ComfyUiInputResourceService service = new ComfyUiInputResourceService(nativeClient);
        ApiConfig apiConfig = ApiConfig.builder().id(7L).build();
        byte[] image = "png-data".getBytes(StandardCharsets.UTF_8);
        String source = "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
        when(nativeClient.uploadMedia(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ComfyUiUploadResult("task-referenceImages-0.png",
                        "ai-fusion-video", "input"));

        assertThat(service.uploadImages(apiConfig, "task", "referenceImages", List.of(source)))
                .containsExactly("ai-fusion-video/task-referenceImages-0.png");

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(nativeClient).uploadMedia(eq(apiConfig), bytes.capture(),
                eq("task-referenceImages-0.png"), eq("image/png"), eq("ai-fusion-video"),
                eq(ComfyUiUploadMediaKind.IMAGE));
        assertThat(bytes.getValue()).isEqualTo(image);
    }

    @Test
    void uploadMediaDecodesReferenceVideoDataUriWithVideoExtension() {
        ComfyUiInputResourceService service = new ComfyUiInputResourceService(nativeClient);
        ApiConfig apiConfig = ApiConfig.builder().id(7L).build();
        byte[] video = "mp4-data".getBytes(StandardCharsets.UTF_8);
        String source = "data:video/mp4;base64," + Base64.getEncoder().encodeToString(video);
        when(nativeClient.uploadMedia(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ComfyUiUploadResult("task-referenceVideos-0.mp4",
                        "ai-fusion-video", "input"));

        assertThat(service.uploadMedia(apiConfig, "task", "referenceVideos", List.of(source),
                ComfyUiUploadMediaKind.VIDEO))
                .containsExactly("ai-fusion-video/task-referenceVideos-0.mp4");
        verify(nativeClient).uploadMedia(eq(apiConfig), any(), eq("task-referenceVideos-0.mp4"),
                eq("video/mp4"), eq("ai-fusion-video"), eq(ComfyUiUploadMediaKind.VIDEO));
    }

    @Test
    void uploadMediaRejectsMismatchedContentTypeForDeclaredKind() {
        ComfyUiInputResourceService service = new ComfyUiInputResourceService(nativeClient);
        ApiConfig apiConfig = ApiConfig.builder().id(7L).build();
        String source = "data:audio/wav;base64,"
                + Base64.getEncoder().encodeToString("wav-data".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadMedia(apiConfig, "task", "referenceVideos",
                List.of(source), ComfyUiUploadMediaKind.VIDEO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("受支持的参考视频格式");
    }

    @Test
    void readBoundedStopsBeforeWritingPastConfiguredLimit() {
        byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ComfyUiInputResourceService.readBounded(
                new ByteArrayInputStream(body), 8, "单个输入不能超过 20MB"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 20MB");
    }
}
