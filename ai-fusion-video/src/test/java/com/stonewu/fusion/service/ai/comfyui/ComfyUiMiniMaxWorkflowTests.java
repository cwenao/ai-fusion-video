package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real MiniMax graph contract: required first picture, exact optional media, selector links and second-pass output. */
class ComfyUiMiniMaxWorkflowTests {
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);
    private final ComfyUiInputResourceService resources = mock(ComfyUiInputResourceService.class);
    private final ComfyUiNativeClient client = mock(ComfyUiNativeClient.class);
    private final AiModel model = AiModel.builder().modelType(3).build();
    private final ComfyUiGenerationExecutor executor = new ComfyUiGenerationExecutor(
            mock(ApiConfigService.class), mock(ComfyUiWorkflowService.class), documents, renderer,
            resources, new ComfyUiOutputResolver(), client);

    @ParameterizedTest
    @CsvSource({"1,0,0", "2,0,1", "3,1,0", "4,1,1", "5,0,2", "6,2,2", "1,2,0", "1,0,2"})
    void preservesExactlyProvidedMediaAndEverySelectorParameter(int pictures, int videos, int audios) throws IOException {
        when(resources.uploadMedia(any(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(call -> call.getArgument(3));
        var version = version();
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version);
        var values = new HashMap<String, Object>();
        values.put("referenceImages", names(pictures, ".png"));
        values.put("referenceVideos", names(videos, ".mp4"));
        values.put("referenceAudios", names(audios, ".wav"));
        values.put("aspectRatio", "9:16");
        values.put("megapixels", 0.6);
        values.put("resolutionMultiple", 64);
        values.put("upscaleMegapixels", 1.2);
        values.put("duration", 5.5);
        values.put("prompt", "gentle motion");
        values.put("seed", 12345);
        var prompt = executor.prepare(context, "test", values).prompt();
        for (int i = 0; i < 6; i++) {
            assertThat(prompt.path("1").path("inputs").has("ref_images.ref_image_" + i)).isEqualTo(i < pictures);
            assertThat(prompt.has(Integer.toString(18 + i))).isEqualTo(i < pictures);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(prompt.path("1").path("inputs").has("ref_videos.ref_video_" + i)).isEqualTo(i < videos);
            assertThat(prompt.has(Integer.toString(30 + i))).isEqualTo(i < videos);
            assertThat(prompt.path("1").path("inputs").has("ref_audios.ref_audio_" + i)).isEqualTo(i < audios);
            assertThat(prompt.has(Integer.toString(32 + i))).isEqualTo(i < audios);
        }
        assertThat(prompt.path("52").path("inputs").path("aspect_ratio").asText()).isEqualTo("9:16 (Portrait Widescreen)");
        assertThat(prompt.path("52").path("inputs").path("megapixels").asDouble()).isEqualTo(0.6);
        assertThat(prompt.path("52").path("inputs").path("multiple").asInt()).isEqualTo(64);
        assertThat(prompt.path("62").path("inputs").path("value").asDouble()).isEqualTo(1.2);
        assertThat(prompt.path("55").path("inputs").path("value").asDouble()).isEqualTo(5.5);
        assertThat(prompt.path("101").path("inputs").path("prompt").asText()).isEqualTo("gentle motion");
        assertThat(prompt.path("65").path("inputs").path("noise_seed").asLong()).isEqualTo(12345);
        assertThat(prompt.path("1").path("inputs").path("width")).isEqualTo(json.readTree("[\"52\",0]"));
        assertThat(prompt.path("1").path("inputs").path("height")).isEqualTo(json.readTree("[\"52\",1]"));
        assertThat(documents.parseOutputBindings(3, version.getApiWorkflowJson(), version.getOutputBindingsJson()))
                .containsExactly(new ComfyUiOutputBinding("98", "video", "primary"));
        // The refinement floor must stay linked to the actual rounded selector dimensions.
        // FLOAT output 0 preserves fractional MP; output 1 would truncate and reintroduce downscaling.
        assertThat(prompt.path("93").path("inputs").path("mode.megapixels"))
                .isEqualTo(json.readTree("[\"113\",0]"));
        assertThat(prompt.path("113").path("inputs").path("values.a"))
                .isEqualTo(json.readTree("[\"62\",0]"));
        assertThat(prompt.path("113").path("inputs").path("values.b"))
                .isEqualTo(json.readTree("[\"52\",0]"));
        assertThat(prompt.path("113").path("inputs").path("values.c"))
                .isEqualTo(json.readTree("[\"52\",1]"));
        verifyNoInteractions(client);
    }

    @Test
    void missingNullAndEmptyFirstPictureFailBeforeAnyUpload() throws IOException {
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version());
        var missing = new HashMap<String, Object>();
        var nullImages = new HashMap<String, Object>();
        nullImages.put("referenceImages", null);
        for (var values : List.of(missing, nullImages, Map.<String, Object>of("referenceImages", List.of()))) {
            assertThatThrownBy(() -> executor.prepare(context, "task", values))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("缺少索引 0");
        }
        verifyNoInteractions(resources, client);
    }

    @Test
    void trialRendererRejectsUnmappedExtraInputsInsteadOfSilentlyIgnoringThem() throws IOException {
        var values = Map.<String, Object>of("referenceImages", names(7, ".png"));
        var version = version();
        assertThatThrownBy(() -> renderer.render(3, version, values))
                .isInstanceOf(BusinessException.class).hasMessageContaining("索引 6").hasMessageContaining("未绑定");
        assertThatThrownBy(() -> renderer.render(3, version, Map.of(
                "referenceImages", names(1, ".png"), "referenceVideos", names(3, ".mp4"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("索引 2").hasMessageContaining("未绑定");
    }

    @Test
    void reportsOneRequiredPictureAndZeroRequiredAvInputs() throws IOException {
        var capabilities = new ComfyUiWorkflowCapabilities(mock(ComfyUiWorkflowService.class), documents);
        var result = capabilities.describe(model, version(), new JSONObject().set("minImageInputs", 1));
        assertThat(result.getInt("minReferenceImages")).isEqualTo(1);
        assertThat(result.getInt("maxReferenceImages")).isEqualTo(6);
        assertThat(result.getInt("maxReferenceVideos")).isEqualTo(2);
        assertThat(result.getInt("maxReferenceAudios")).isEqualTo(2);
        assertThat(result.getJSONObject("comfyuiResolutionSelector").getStr("nodeId")).isEqualTo("52");
        assertThat(result.getJSONObject("comfyuiInputs").getJSONObject("referenceVideos").getInt("minimumWhenProvided")).isZero();
    }

    @Test
    void invalidMultipleFailsBeforeAnyMediaUpload() throws IOException {
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version());
        assertThatThrownBy(() -> executor.prepare(context, "task", Map.of(
                "referenceImages", List.of("a.png"), "resolutionMultiple", 30)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("multiple");
        verifyNoInteractions(resources, client);
    }

    private List<String> names(int count, String extension) {
        return IntStream.range(0, count).mapToObj(index -> "media-" + index + extension).toList();
    }

    private ComfyUiWorkflowVersion version() throws IOException {
        try (var input = getClass().getResourceAsStream("/comfyui/minimax-h3-version.json")) {
            var fixture = json.readTree(input);
            return ComfyUiWorkflowVersion.builder().apiWorkflowJson(fixture.path("apiWorkflowJson").asText())
                    .inputBindingsJson(fixture.path("inputBindingsJson").asText())
                    .outputBindingsJson(fixture.path("outputBindingsJson").asText()).build();
        }
    }
}
