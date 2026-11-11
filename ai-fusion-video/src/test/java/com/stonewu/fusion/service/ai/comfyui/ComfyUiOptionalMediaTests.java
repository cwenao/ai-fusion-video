package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadMediaKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Protect optional reference video/audio slots: upload routing, slot pruning and capability limits. */
class ComfyUiOptionalMediaTests {
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);
    private final ComfyUiInputResourceService resources = mock(ComfyUiInputResourceService.class);
    private final ComfyUiNativeClient client = mock(ComfyUiNativeClient.class);
    private final ComfyUiGenerationExecutor executor = new ComfyUiGenerationExecutor(
            mock(ApiConfigService.class), mock(ComfyUiWorkflowService.class), documents, renderer,
            resources, mock(ComfyUiOutputResolver.class), client);
    private final AiModel model = AiModel.builder().modelType(3).build();

    @Test
    void preparesOnlyTheProvidedVideoAndAudioSlots() {
        when(resources.uploadMedia(any(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(call -> call.getArgument(3));
        var version = version();
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version);
        var prompt = executor.prepare(context, "task", Map.of(
                "referenceVideos", List.of("clip.mp4"),
                "referenceAudios", List.of("voice-a.wav", "voice-b.wav"))).prompt();
        var main = prompt.path("1").path("inputs");
        assertThat(main.has("ref_videos.ref_video_0")).isTrue();
        assertThat(main.has("ref_videos.ref_video_1")).isFalse();
        assertThat(main.has("ref_audios.ref_audio_0")).isTrue();
        assertThat(main.has("ref_audios.ref_audio_1")).isTrue();
        assertThat(prompt.path("30").path("inputs").path("video").asText()).isEqualTo("clip.mp4");
        assertThat(prompt.has("31")).isFalse();
        assertThat(prompt.path("32").path("inputs").path("audio").asText()).isEqualTo("voice-a.wav");
        assertThat(prompt.path("33").path("inputs").path("audio").asText()).isEqualTo("voice-b.wav");
        verify(resources).uploadMedia(any(), eq("task"), eq("referenceVideos"), anyList(),
                eq(ComfyUiUploadMediaKind.VIDEO));
        verify(resources).uploadMedia(any(), eq("task"), eq("referenceAudios"), anyList(),
                eq(ComfyUiUploadMediaKind.AUDIO));
        verifyNoInteractions(client);
    }

    @Test
    void missingOptionalFieldsRemoveEverySlotAndLoader() {
        var result = renderer.render(3, version(), Map.of());
        var main = result.path("1").path("inputs");
        assertThat(main.has("ref_videos.ref_video_0")).isFalse();
        assertThat(main.has("ref_videos.ref_video_1")).isFalse();
        assertThat(main.has("ref_audios.ref_audio_0")).isFalse();
        assertThat(main.has("ref_audios.ref_audio_1")).isFalse();
        for (String loader : List.of("30", "31", "32", "33")) {
            assertThat(result.has(loader)).isFalse();
        }
    }

    @Test
    void rejectsOptionalBindingsOnWrongLoaderClasses() {
        var invalid = version();
        invalid.setInputBindingsJson("""
                {"referenceVideos":[{"nodeId":"1","inputName":"ref_videos.ref_video_0",
                "valueType":"uploaded_video","index":0,"optional":true}],
                "referenceAudios":[{"nodeId":"1","inputName":"ref_audios.ref_audio_0",
                "valueType":"uploaded_audio","index":0,"optional":true}]}
                """.replace("ref_audios.ref_audio_0", "ref_videos.ref_video_1"));
        assertThatThrownBy(() -> documents.parseInputBindings(3, invalid.getApiWorkflowJson(),
                invalid.getInputBindingsJson())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("可选参考音频必须");
        var mismatched = version();
        mismatched.setInputBindingsJson("""
                {"referenceVideos":[{"nodeId":"1","inputName":"ref_videos.ref_video_0",
                "valueType":"uploaded_audio","index":0,"optional":true}]}
                """);
        assertThatThrownBy(() -> documents.parseInputBindings(3, mismatched.getApiWorkflowJson(),
                mismatched.getInputBindingsJson())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("绑定类型与业务字段不匹配");
    }

    @Test
    void rejectsUnboundExtraVideosBeforeUploading() {
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version());
        assertThatThrownBy(() -> executor.prepare(context, "task", Map.of(
                "referenceVideos", List.of("a.mp4", "b.mp4", "c.mp4"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("索引 2").hasMessageContaining("未绑定");
        verifyNoInteractions(resources, client);
    }

    @Test
    void exposesVideoAndAudioMaximumsWithZeroMinimum() {
        var resolver = new ComfyUiWorkflowCapabilities(mock(ComfyUiWorkflowService.class), documents);
        var result = resolver.describe(model, version(), new JSONObject());
        assertThat(result.getBool("supportReferenceVideos")).isTrue();
        assertThat(result.getBool("supportReferenceAudios")).isTrue();
        assertThat(result.getInt("maxReferenceVideos")).isEqualTo(2);
        assertThat(result.getInt("maxReferenceAudios")).isEqualTo(2);
        var inputs = result.getJSONObject("comfyuiInputs");
        assertThat(inputs.getJSONObject("referenceVideos").getInt("minimumWhenProvided")).isZero();
        assertThat(inputs.getJSONObject("referenceAudios").getInt("minimumWhenProvided")).isZero();
    }

    private ComfyUiWorkflowVersion version() {
        var graph = json.createObjectNode();
        var slots = graph.putObject("1").put("class_type", "MiniMaxH3ReferenceToVideo").putObject("inputs");
        graph.putObject("30").put("class_type", "VHS_LoadVideo").putObject("inputs")
                .put("video", "placeholder-a.mp4");
        graph.putObject("31").put("class_type", "VHS_LoadVideo").putObject("inputs")
                .put("video", "placeholder-b.mp4");
        graph.putObject("32").put("class_type", "VHS_LoadAudioUpload").putObject("inputs")
                .put("audio", "placeholder-a.wav");
        graph.putObject("33").put("class_type", "VHS_LoadAudioUpload").putObject("inputs")
                .put("audio", "placeholder-b.wav");
        slots.putArray("ref_videos.ref_video_0").add("30").add(0);
        slots.putArray("ref_videos.ref_video_1").add("31").add(0);
        slots.putArray("ref_audios.ref_audio_0").add("32").add(0);
        slots.putArray("ref_audios.ref_audio_1").add("33").add(0);
        var bindings = json.createObjectNode();
        var videos = bindings.putArray("referenceVideos");
        videos.addObject().put("nodeId", "1").put("inputName", "ref_videos.ref_video_0")
                .put("valueType", "uploaded_video").put("index", 0).put("optional", true);
        videos.addObject().put("nodeId", "1").put("inputName", "ref_videos.ref_video_1")
                .put("valueType", "uploaded_video").put("index", 1).put("optional", true);
        var audios = bindings.putArray("referenceAudios");
        audios.addObject().put("nodeId", "1").put("inputName", "ref_audios.ref_audio_0")
                .put("valueType", "uploaded_audio").put("index", 0).put("optional", true);
        audios.addObject().put("nodeId", "1").put("inputName", "ref_audios.ref_audio_1")
                .put("valueType", "uploaded_audio").put("index", 1).put("optional", true);
        return ComfyUiWorkflowVersion.builder().apiWorkflowJson(graph.toString())
                .inputBindingsJson(bindings.toString()).build();
    }
}
