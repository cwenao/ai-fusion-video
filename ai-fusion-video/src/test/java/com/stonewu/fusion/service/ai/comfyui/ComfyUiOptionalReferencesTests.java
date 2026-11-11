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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Protect optional 0–5 image slots through capability, upload and prompt preparation, without weakening required slots. */
class ComfyUiOptionalReferencesTests {
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);
    private final ComfyUiInputResourceService resources = mock(ComfyUiInputResourceService.class);
    private final ComfyUiNativeClient client = mock(ComfyUiNativeClient.class);
    private final ComfyUiGenerationExecutor executor = new ComfyUiGenerationExecutor(
            mock(ApiConfigService.class), mock(ComfyUiWorkflowService.class), documents, renderer,
            resources, mock(ComfyUiOutputResolver.class), client);
    private final AiModel model = AiModel.builder().modelType(2).build();

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void preparesExactlyTheProvidedReferencesAndNeverUsesDefaultImages(int count) {
        when(resources.uploadMedia(any(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(call -> call.getArgument(3));
        var version = version();
        var refs = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            refs.add("uploaded-" + i + ".png");
        }
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version);
        var prompt = executor.prepare(context, "task", Map.of("referenceImages", refs)).prompt();
        for (int i = 0; i < 5; i++) {
            assertThat(prompt.path("679").path("inputs").has("images.image_" + (i + 1))).isEqualTo(i < count);
            assertThat(prompt.has(Integer.toString(901 + i))).isEqualTo(i < count);
            if (i < count) {
                assertThat(prompt.path(Integer.toString(901 + i)).path("inputs").path("image").asText())
                        .isEqualTo(refs.get(i));
            }
        }
        assertThat(version.getApiWorkflowJson()).contains("placeholder.png");
        verifyNoInteractions(client);
    }

    @Test
    void missingAndNullReferenceFieldBothRemoveAllOptionalLinks() {
        var nullInputs = new HashMap<String, Object>();
        nullInputs.put("referenceImages", null);
        for (Map<String, Object> inputs : List.of(Map.<String, Object>of(), nullInputs)) {
            var result = renderer.render(2, version(), inputs);
            assertThat(result.path("679").path("inputs")).isEmpty();
            assertThat(result.has("901")).isFalse();
        }
    }

    @Test
    void rejectsSixImagesBeforeUploadingAndKeepsRequiredSlotErrors() {
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().build(), null, version());
        assertThatThrownBy(() -> executor.prepare(context, "task", Map.of("referenceImages",
                List.of("1.png", "2.png", "3.png", "4.png", "5.png", "6.png"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("索引 5").hasMessageContaining("未绑定");
        verifyNoInteractions(resources, client);
        var required = version();
        required.setInputBindingsJson(required.getInputBindingsJson().replace(":true", ":false"));
        assertThatThrownBy(() -> renderer.render(2, required, Map.of("referenceImages", List.of("one.png"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺少索引 1");
    }

    @Test
    void exposesZeroMinimumAndFiveMaximumAndAllowsANarrowerUsageLimit() {
        var resolver = new ComfyUiWorkflowCapabilities(mock(ComfyUiWorkflowService.class), documents);
        var result = resolver.describe(model, version(), new JSONObject());
        assertThat(result.getInt("minReferenceImages")).isZero();
        assertThat(result.getInt("maxReferenceImages")).isEqualTo(5);
        assertThat(result.getBool("supportReferenceImages")).isTrue();
        assertThat(result.getJSONObject("comfyuiInputs").getJSONObject("referenceImages")
                .getInt("minimumWhenProvided")).isZero();
        var limited = resolver.describe(model, version(), new JSONObject().set("maxReferenceImages", 2));
        assertThat(limited.getInt("maxReferenceImages")).isEqualTo(2);
    }

    @Test
    void rejectsOptionalScalarOrUnlinkedFileBindings() {
        var invalid = version();
        invalid.setInputBindingsJson("""
                {"referenceImages":[{"nodeId":"901","inputName":"image","valueType":"uploaded_image",
                "index":0,"optional":true}]}
                """);
        assertThatThrownBy(() -> documents.parseInputBindings(2, invalid.getApiWorkflowJson(),
                invalid.getInputBindingsJson())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("可选参考图必须");
    }

    private ComfyUiWorkflowVersion version() {
        var graph = json.createObjectNode();
        var slots = graph.putObject("679").put("class_type", "TextEncodeQwenImage21").putObject("inputs");
        var bindingObject = json.createObjectNode();
        var bindings = bindingObject.putArray("referenceImages");
        for (int i = 0; i < 5; i++) {
            String id = Integer.toString(901 + i);
            graph.putObject(id).put("class_type", "LoadImage").putObject("inputs").put("image", "placeholder.png");
            slots.putArray("images.image_" + (i + 1)).add(id).add(0);
            bindings.addObject().put("nodeId", "679").put("inputName", "images.image_" + (i + 1))
                    .put("index", i).put("valueType", "uploaded_image").put("optional", true);
        }
        return ComfyUiWorkflowVersion.builder().apiWorkflowJson(graph.toString())
                .inputBindingsJson(bindingObject.toString()).build();
    }
}
