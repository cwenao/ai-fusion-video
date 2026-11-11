package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.canvas.capability.CanvasCapabilityRegistry;
import com.stonewu.fusion.service.canvas.capability.CanvasConfigValidator;
import com.stonewu.fusion.service.canvas.model.CanvasNodeKind;
import com.stonewu.fusion.service.generation.image.ImageGenerationService;
import com.stonewu.fusion.service.generation.image.strategy.comfyui.ComfyUiImageStrategy;
import com.stonewu.fusion.service.generation.job.GenerationLeaseScope;
import com.stonewu.fusion.service.generation.job.RemoteGenerationJob;
import com.stonewu.fusion.service.media.GenerationMediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Protect the ordinary image-task path from replacing production selector links with square defaults. */
class ComfyUiSelectorIntegrationTests {
    private static final long WORKFLOW_SEED = 930225228534713L;
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);
    private ObjectNode fixture;

    @BeforeEach
    void loadProductionDerivedVersion() throws IOException {
        try (var source = getClass().getResourceAsStream("/comfyui/qwen-selector-version.json")) {
            assertThat(source).isNotNull();
            fixture = (ObjectNode) json.readTree(source);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "16:9, 1MP, 16:9 (Widescreen), 1",
            "16:9, 2MP, 16:9 (Widescreen), 2",
            "9:16, 1MP, 9:16 (Portrait Widescreen), 1",
            "9:16, 2MP, 9:16 (Portrait Widescreen), 2",
            "1:1, 1MP, 1:1 (Square), 1",
            "1:1, 2MP, 1:1 (Square), 2"
    })
    void ordinaryImageParametersReachSelectorWithoutInjectingPixelDefaults(
            String ratio, String resolution, String selectorLabel, double megapixels) {
        var version = version();
        var before = publishedJson(version);
        var values = prepareStrategyValues(version, task(ratio, resolution));

        assertThat(values).containsEntry("aspectRatio", ratio);
        var prompt = renderer.render(2, version, values);

        assertSelectorDimensions(prompt, selectorLabel, megapixels);
        assertThat(prompt.at("/728/inputs/prompt").asText()).isEqualTo("ordinary selector image");
        assertThat(prompt.at("/680/inputs/batch_size").asInt()).isEqualTo(2);
        assertThat(prompt.at("/678/inputs/seed").isIntegralNumber()).isTrue();
        assertThat(prompt.at("/678/inputs/seed").longValue()).isEqualTo(((Number) values.get("seed")).longValue());
        assertUnrelatedDefaults(prompt);
        assertThat(publishedJson(version)).isEqualTo(before);
    }

    @Test
    void omittedRatioAndResolutionUseWorkflowDefaultsInsteadOfLegacySquareDefaults() {
        var version = version();
        var before = publishedJson(version);
        var values = prepareStrategyValues(version, task(null, null));

        var prompt = renderer.render(2, version, values);

        assertSelectorDimensions(prompt, "16:9 (Widescreen)", 2);
        assertUnrelatedDefaults(prompt);
        assertThat(publishedJson(version)).isEqualTo(before);
    }

    @Test
    void renderingWithoutOverridesKeepsWorkflowSelectorAndFixedSamplerDefaults() {
        var version = version();
        var before = publishedJson(version);

        var prompt = renderer.render(2, version, Map.of());

        assertSelectorDimensions(prompt, "16:9 (Widescreen)", 2);
        assertThat(prompt.at("/678/inputs/seed").longValue()).isEqualTo(WORKFLOW_SEED);
        assertThat(prompt.at("/680/inputs/batch_size").asInt()).isEqualTo(1);
        assertUnrelatedDefaults(prompt);
        assertThat(publishedJson(version)).isEqualTo(before);
    }

    @Test
    void canvasConfigurationAcceptsSelectorInputsWithNumericMegapixels() {
        var capability = new CanvasCapabilityRegistry().require(CanvasNodeKind.COMFYUI);
        var validator = new CanvasConfigValidator();
        var config = json.createObjectNode()
                .put("modelId", 5L).put("workflowVersionId", 20L).put("resultType", "image")
                .put("aspectRatio", "9:16").put("megapixels", 1.0);

        assertThat(capability.configFields().get("megapixels").type()).isEqualTo("number");
        assertThatCode(() -> validator.validate(capability, config)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsLegacyLinkedDimensionsOrMixedSelectorPixelsBeforeUploading(boolean legacyBindings) {
        var version = version();
        if (legacyBindings) {
            ObjectNode bindings = ((ObjectNode) fixture.path("bindings")).deepCopy();
            bindings.remove(List.of("aspectRatio", "megapixels"));
            for (String dimension : List.of("width", "height")) {
                bindings.putArray(dimension).addObject()
                        .put("nodeId", "680").put("inputName", dimension).put("valueType", "integer");
            }
            version.setInputBindingsJson(bindings.toString());
        }
        var before = publishedJson(version);
        var resources = mock(ComfyUiInputResourceService.class);
        var client = mock(ComfyUiNativeClient.class);
        var executor = new ComfyUiGenerationExecutor(
                mock(ApiConfigService.class), mock(ComfyUiWorkflowService.class), documents, renderer,
                resources, new ComfyUiOutputResolver(), client);
        var model = AiModel.builder().id(5L).modelType(2).build();
        var context = new ComfyUiExecutionContext(model, ApiConfig.builder().id(7L).build(), null, version);
        Map<String, Object> values = Map.of(
                "width", 512, "height", 512,
                "aspectRatio", "9:16", "megapixels", 1,
                "referenceImages", List.of("https://example.invalid/reference.png"));

        // A provided reference would trigger upload if dimension validation moved after resource handling.
        assertThatThrownBy(() -> executor.prepare(context, "selector-conflict", values))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(legacyBindings ? "宽高" : "width/height");

        verifyNoInteractions(resources, client);
        assertThat(publishedJson(version)).isEqualTo(before);
    }

    private Map<String, Object> prepareStrategyValues(ComfyUiWorkflowVersion version, ImageTask task) {
        var executor = mock(ComfyUiGenerationExecutor.class);
        var models = mock(AiModelService.class);
        var records = mock(ImageGenerationService.class);
        var media = mock(GenerationMediaService.class);
        var strategy = new ComfyUiImageStrategy(models, records, executor, media);
        var model = selectorModel(version);
        var api = ApiConfig.builder().id(7L).platform(ComfyUiWorkflowService.PLATFORM).build();
        var context = new ComfyUiExecutionContext(model, api, null, version);
        var claim = new RemoteGenerationJob.Claim(UUID.randomUUID().toString(), "selector-test-worker", 1);
        var captured = new LinkedHashMap<String, Object>();
        when(executor.resolveContext(model, version.getId(), api)).thenReturn(context);
        when(executor.prepare(eq(context), eq(claim.id()), anyMap())).thenAnswer(call -> {
            Map<String, Object> supplied = call.getArgument(2);
            captured.putAll(supplied);
            return new ComfyUiPreparedSubmission(context, UUID.randomUUID().toString(), json.createObjectNode());
        });

        try (var ignored = GenerationLeaseScope.enter(claim)) {
            strategy.prepare(task, model, api);
        }

        assertThat(captured).doesNotContainKeys("width", "height");
        verify(executor).prepare(eq(context), eq(claim.id()), anyMap());
        verify(executor, never()).submit(any());
        verifyNoInteractions(models, records, media);
        return captured;
    }

    private AiModel selectorModel(ComfyUiWorkflowVersion version) {
        JSONObject configured = new JSONObject()
                .set("comfyuiResolutionTiers", new JSONObject().set("1MP", 1).set("2MP", 2))
                .set("defaultResolution", "2MP");
        JSONObject frozen = new JSONObject();
        ComfyUiResolutionSelector.describe(documents.parseApiWorkflow(version.getApiWorkflowJson()),
                documents.parseInputBindings(2, version.getApiWorkflowJson(), version.getInputBindingsJson()),
                configured, frozen);
        // Old frozen model snapshots may still contain these defaults; the selector must remain authoritative.
        frozen.set("defaultWidth", 512).set("defaultHeight", 512);
        return AiModel.builder().id(5L).modelType(2).config(frozen.toString()).build();
    }

    private ImageTask task(String ratio, String resolution) {
        return ImageTask.builder().id(10L).modelId(5L).workflowVersionId(20L)
                .taskId("ordinary-selector-task").prompt("ordinary selector image").count(2)
                .ratio(ratio).resolution(resolution).build();
    }

    private ComfyUiWorkflowVersion version() {
        return ComfyUiWorkflowVersion.builder().id(20L).workflowId(3L).published(true)
                .apiWorkflowJson(fixture.path("api").toString())
                .inputBindingsJson(fixture.path("bindings").toString())
                .outputBindingsJson(fixture.path("outputs").toString()).build();
    }

    private List<String> publishedJson(ComfyUiWorkflowVersion version) {
        return List.of(version.getApiWorkflowJson(), version.getInputBindingsJson(), version.getOutputBindingsJson());
    }

    private void assertSelectorDimensions(ObjectNode prompt, String selectorLabel, double megapixels) {
        assertThat(prompt.at("/681/inputs/aspect_ratio")).isEqualTo(json.getNodeFactory().textNode(selectorLabel));
        assertThat(prompt.at("/681/inputs/megapixels").isNumber()).isTrue();
        assertThat(prompt.at("/681/inputs/megapixels").doubleValue()).isEqualTo(megapixels);
        assertThat(prompt.at("/681/inputs/multiple").asInt()).isEqualTo(32);
        assertThat(prompt.at("/680/inputs/width")).isEqualTo(json.valueToTree(List.of("681", 0)));
        assertThat(prompt.at("/680/inputs/height")).isEqualTo(json.valueToTree(List.of("681", 1)));
    }

    private void assertUnrelatedDefaults(ObjectNode prompt) {
        assertThat(prompt.at("/678/inputs/cfg").asInt()).isEqualTo(1);
        assertThat(prompt.at("/678/inputs/steps").asInt()).isEqualTo(25);
        assertThat(prompt.at("/678/inputs/denoise").asInt()).isEqualTo(1);
        assertThat(prompt.at("/678/inputs/scheduler").asText()).isEqualTo("simple");
        assertThat(prompt.at("/678/inputs/sampler_name").asText()).isEqualTo("euler");
        assertThat(prompt.at("/679/inputs/negative_prompt")).isEqualTo(json.getNodeFactory().textNode(""));
    }
}
