package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.ImageTask;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 保护选择器的真实尺寸权威：错误参数不能被默默回退成方图，也不能断开宽高连线。 */
class ComfyUiResolutionSelectorTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyScalarBindingsAndTasksRemainUntouched() {
        ObjectNode workflow = workflow();
        ((ObjectNode) workflow.path("680").path("inputs")).put("width", 512).put("height", 512);
        List<ComfyUiInputBinding> bindings = List.of(
                new ComfyUiInputBinding("width", "680", "width", "integer", null),
                new ComfyUiInputBinding("height", "680", "height", "integer", null));
        JSONObject config = JSONUtil.parseObj("{\"defaultWidth\":512,\"defaultHeight\":512,\"supportedSizes\":[\"512x512\"]}");
        String before = config.toString();
        ComfyUiResolutionSelector.describe(workflow, bindings, new JSONObject(), config);
        assertThat(config.toString()).isEqualTo(before);
        Map<String, Object> supplied = Map.of("width", 512, "height", 512);
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings, supplied))
                .isEqualTo(supplied).isNotSameAs(supplied);
        ImageTask task = ImageTask.builder().ratio("16:9").aspectRatio("1:1").width(512).build();
        ComfyUiResolutionSelector.validateTask(task, config);
        assertThat(task.getAspectRatio()).isEqualTo("1:1");
        assertThat(task.getHeight()).isNull();
        assertThat(ComfyUiResolutionSelector.taskValues(task, config)).isEmpty();
    }

    @Test
    void derivesCapabilitiesFromSavedSelectorInsteadOfModelSquareDefaults() {
        ObjectNode workflow = workflow();
        ObjectNode beforeWorkflow = workflow.deepCopy();
        JSONObject configured = JSONUtil.parseObj("""
                {"defaultWidth":1024,"defaultHeight":1024,"defaultAspectRatio":"16:9",
                 "supportedSizes":["1024x1024"],"supportAutoSize":true}
                """);
        String beforeConfig = configured.toString();
        JSONObject result = JSONUtil.parseObj(configured.toString());
        ComfyUiResolutionSelector.describe(workflow, bindings(), configured, result);

        JSONObject marker = result.getJSONObject("comfyuiResolutionSelector");
        assertThat(marker.getStr("nodeId")).isEqualTo("681");
        assertThat(marker.getInt("multiple")).isEqualTo(32);
        assertThat(marker.getStr("defaultAspectRatio")).isEqualTo("16:9");
        assertThat(marker.getBigDecimal("defaultMegapixels")).isEqualByComparingTo("2");
        assertThat(result.getJSONArray("supportedAspectRatios").toList(String.class))
                .containsExactly("1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9");
        assertThat(result.getJSONArray("supportedResolutions").toList(String.class)).containsExactly("2MP");
        assertThat(result.getJSONObject("comfyuiResolutionTiers").getBigDecimal("2MP")).isEqualByComparingTo("2");
        assertThat(result.getStr("defaultAspectRatio")).isEqualTo("16:9");
        assertThat(result.getStr("defaultResolution")).isEqualTo("2MP");
        assertThat(result.containsKey("defaultWidth")).isFalse();
        assertThat(result.containsKey("defaultHeight")).isFalse();
        assertThat(result.containsKey("supportedSizes")).isFalse();
        assertThat(result.getBool("supportAutoSize")).isFalse();
        assertThat(workflow).isEqualTo(beforeWorkflow);
        assertThat(configured.toString()).isEqualTo(beforeConfig);
    }

    @Test
    void configuredDefaultRatioCannotSilentlyOverrideTheWorkflowDefault() {
        rejects(() -> describe("{\"defaultAspectRatio\":\"1:1\"}"), "配置默认比例");
    }

    @Test
    void missingTierConfigurationUsesTheSavedMegapixelsNotAHardCodedDefault() {
        ObjectNode workflow = workflow();
        ((ObjectNode) workflow.path("681").path("inputs")).put("megapixels", 2.3);
        JSONObject result = new JSONObject();
        ComfyUiResolutionSelector.describe(workflow, bindings(), new JSONObject(), result);
        assertThat(result.getStr("defaultResolution")).isEqualTo("2.3MP");
        assertThat(result.getJSONObject("comfyuiResolutionTiers").getBigDecimal("2.3MP"))
                .isEqualByComparingTo("2.3");
    }

    @Test
    void tierNumbersAreNormalizedAndModelCanOnlyNarrowTheRatios() {
        JSONObject config = describe("""
                {"comfyuiResolutionTiers":{"小图":1.00,"标准":2.0},"supportedAspectRatios":["1:1","16:9"]}
                """);
        assertThat(config.getJSONObject("comfyuiResolutionTiers").getBigDecimal("小图")).isEqualTo(BigDecimal.ONE);
        assertThat(config.getJSONArray("supportedAspectRatios").toList(String.class)).containsExactly("1:1", "16:9");
        assertThat(config.getStr("defaultResolution")).isEqualTo("标准");
        ImageTask task = ImageTask.builder().aspectRatio(" 1:1 ").resolution(" 小图 ").build();
        assertThat(ComfyUiResolutionSelector.taskValues(task, config))
                .containsOnlyKeys("aspectRatio", "megapixels").containsEntry("aspectRatio", "1:1")
                .containsEntry("megapixels", BigDecimal.ONE);
        assertThat(task.getRatio()).isEqualTo("1:1");
        assertThat(task.getAspectRatio()).isEqualTo("1:1");
        assertThat(task.getResolution()).isEqualTo("小图");
        assertThat(task.getWidth()).isNull();
        assertThat(task.getHeight()).isNull();
    }

    @Test
    void explicitlyDeclaredDefaultTierMayDifferFromTheWorkflowDefault() {
        JSONObject config = describe("{\"comfyuiResolutionTiers\":{\"1MP\":1},\"defaultResolution\":\"1MP\"}");
        assertThat(config.getStr("defaultResolution")).isEqualTo("1MP");
        assertThat(config.getJSONObject("comfyuiResolutionSelector").getBigDecimal("defaultMegapixels"))
                .isEqualByComparingTo("2");
        assertThat(ComfyUiResolutionSelector.taskValues(new ImageTask(), config)).containsEntry("megapixels", BigDecimal.ONE);
        rejects(() -> describe("{\"comfyuiResolutionTiers\":{\"1MP\":1}}"), "工作流默认");
        rejects(() -> describe("{\"comfyuiResolutionTiers\":{\"1MP\":1},\"defaultResolution\":\"2MP\"}"), "默认分辨率");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "\"2MP\"", "{}",
            "{\"bad\":\"2\"}", "{\"bad\":true}", "{\"bad\":null}", "{\"bad\":0}",
            "{\"bad\":0.05}", "{\"bad\":16.1}", "{\"bad\":1.15}", "{\"bad\":1.00000000000001}"})
    void invalidTierDeclarationsNeverFallBackToTheWorkflowDefault(String tiers) {
        rejects(() -> describe("{\"comfyuiResolutionTiers\":" + tiers + "}"), "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"1:1\"]", "[\"16:9\",\"4:5\"]", "\"16:9\""})
    void configuredRatiosCannotInventSupportOrExcludeTheSavedDefault(String ratios) {
        rejects(() -> describe("{\"supportedAspectRatios\":" + ratios + "}"), "比例");
    }

    @ParameterizedTest
    @MethodSource("invalidBindings")
    void selectorBindingsMustBePairedUniqueTypedAndUnindexed(List<ComfyUiInputBinding> bindings) {
        ObjectNode workflow = workflow();
        rejects(() -> ComfyUiResolutionSelector.describe(workflow, bindings, new JSONObject(), new JSONObject()), "");
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings, Map.of()), "");
    }

    private static Stream<List<ComfyUiInputBinding>> invalidBindings() {
        ComfyUiInputBinding ratio = bindings().getFirst();
        ComfyUiInputBinding mp = bindings().getLast();
        return Stream.of(
                List.of(ratio), List.of(mp), List.of(ratio, ratio, mp), List.of(ratio, mp, mp),
                List.of(new ComfyUiInputBinding("aspectRatio", "681", "ratio", "string", null), mp),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "681", "value", "number", null)),
                List.of(new ComfyUiInputBinding("aspectRatio", "681", "aspect_ratio", "number", null), mp),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "681", "megapixels", "string", null)),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "681", "megapixels", "integer", null)),
                List.of(new ComfyUiInputBinding("aspectRatio", "681", "aspect_ratio", "string", 0), mp),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "681", "megapixels", "number", 0)),
                List.of(new ComfyUiInputBinding("aspectRatio", "681", "aspect_ratio", "string", null, true), mp),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "681", "megapixels", "number", null, true)),
                List.of(ratio, new ComfyUiInputBinding("megapixels", "682", "megapixels", "number", null)),
                List.of(new ComfyUiInputBinding("aspectRatio", "680", "aspect_ratio", "string", null),
                        new ComfyUiInputBinding("megapixels", "680", "megapixels", "number", null)),
                List.of(ratio, mp, new ComfyUiInputBinding("prompt", "681", "aspect_ratio", "string", null)));
    }

    @Test
    void disconnectedOrHalfConnectedSelectorsDoNotAdvertiseSupport() {
        ObjectNode workflow = workflow();
        workflow.remove("680");
        rejects(() -> ComfyUiResolutionSelector.describe(workflow, bindings(), new JSONObject(), new JSONObject()), "实际消费");
        workflow.set("680", workflow().get("680"));
        ObjectNode inputs = (ObjectNode) workflow.path("680").path("inputs");
        inputs.put("height", 1088);
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of()), "成对连接");
        inputs.putArray("height").add("682").add(1);
        rejects(() -> ComfyUiResolutionSelector.describe(workflow, bindings(), new JSONObject(), new JSONObject()), "成对连接");
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,0", "1,1", "0,2"})
    void consumersMustUseWidthSlotZeroAndHeightSlotOne(int widthSlot, int heightSlot) {
        ObjectNode workflow = workflow();
        ObjectNode inputs = (ObjectNode) workflow.path("680").path("inputs");
        inputs.putArray("width").add("681").add(widthSlot);
        inputs.putArray("height").add("681").add(heightSlot);
        rejects(() -> ComfyUiResolutionSelector.describe(workflow, bindings(), new JSONObject(), new JSONObject()), "输出槽 0/1");
    }

    @Test
    void savedSelectorInputsMustActuallyBeRunnable() {
        ObjectNode workflow = workflow();
        ObjectNode inputs = (ObjectNode) workflow.path("681").path("inputs");
        inputs.put("aspect_ratio", "16:9");
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of()), "真实枚举");
        inputs.put("aspect_ratio", "16:9 (Widescreen)").put("megapixels", "2");
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of()), "必须是数值");
        inputs.put("megapixels", 2.05);
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of()), "步长");
        inputs.put("megapixels", 2).put("multiple", 10);
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of()), "multiple");
    }

    @Test
    void absentTaskOptionsUseDerivedDefaultsWithoutPretendingPixelDimensionsWereExplicit() {
        ImageTask task = new ImageTask();
        Map<String, Object> values = ComfyUiResolutionSelector.taskValues(task, describe("{}"));
        assertThat(values).containsOnlyKeys("aspectRatio", "megapixels")
                .containsEntry("aspectRatio", "16:9").containsEntry("megapixels", new BigDecimal("2"));
        assertThat(task.getRatio()).isEqualTo("16:9");
        assertThat(task.getAspectRatio()).isEqualTo("16:9");
        assertThat(task.getResolution()).isEqualTo("2MP");
        assertThat(task.getWidth()).isNull();
        assertThat(task.getHeight()).isNull();
    }

    @Test
    void taskRejectsConflictingAliasesAndOptionsOutsideDeclaredCapabilities() {
        JSONObject config = describe("{\"supportedAspectRatios\":[\"1:1\",\"16:9\"]}");
        rejects(() -> ComfyUiResolutionSelector.taskValues(
                ImageTask.builder().ratio("16:9").aspectRatio("1:1").build(), config), "不能冲突");
        rejects(() -> ComfyUiResolutionSelector.validateTask(ImageTask.builder().ratio("4:3").build(), config), "不支持的画面比例");
        rejects(() -> ComfyUiResolutionSelector.validateTask(ImageTask.builder().ratio("4:5").build(), config), "不支持的画面比例");
        rejects(() -> ComfyUiResolutionSelector.validateTask(
                ImageTask.builder().resolution("1920x1080").build(), config), "不支持的分辨率档位");
        rejects(() -> ComfyUiResolutionSelector.validateTask(ImageTask.builder().resolution("1MP").build(), config), "不支持的分辨率档位");
    }

    @ParameterizedTest
    @CsvSource({"16:9,1920,1088", "9:16,1088,1920", "1:1,1440,1440"})
    void explicitDimensionsMustMatchRealSelectorRoundingAndAreNeverSentAsBindings(String ratio, int width, int height) {
        ImageTask task = ImageTask.builder().ratio(ratio).width(width).height(height).build();
        Map<String, Object> values = ComfyUiResolutionSelector.taskValues(task, describe("{}"));
        assertThat(values).containsOnlyKeys("aspectRatio", "megapixels").containsEntry("aspectRatio", ratio);
        assertThat(task.getWidth()).isEqualTo(width);
        assertThat(task.getHeight()).isEqualTo(height);
    }

    @ParameterizedTest
    @CsvSource({"1920,1080", "1024,1024", "1920,", ",1088", "0,1088", "1920,-1"})
    void incompatibleOrPartialExplicitDimensionsFailWith400(Integer width, Integer height) {
        JSONObject config = describe("{}");
        ImageTask task = ImageTask.builder().ratio("16:9").width(width).height(height).build();
        rejects(() -> ComfyUiResolutionSelector.validateTask(task, config), "宽高");
    }

    @Test
    void selectedTierRatherThanWorkflowDefaultDeterminesDimensionValidation() {
        JSONObject config = describe("{\"comfyuiResolutionTiers\":{\"1MP\":1,\"2MP\":2}}");
        ImageTask task = ImageTask.builder().ratio("1:1").resolution("1MP").width(1024).height(1024).build();
        assertThat(ComfyUiResolutionSelector.taskValues(task, config)).containsEntry("megapixels", BigDecimal.ONE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1:1|1:1 (Square)", "2:3|2:3 (Portrait Photo)", "3:2|3:2 (Photo)",
            "3:4|3:4 (Portrait Standard)", "4:3|4:3 (Standard)", "9:16|9:16 (Portrait Widescreen)",
            "16:9|16:9 (Widescreen)", "21:9|21:9 (Ultrawide)"})
    void rendererTranslatesAllEightRealEnumsAndPreservesExactLabels(String ratio, String label) {
        ObjectNode workflow = workflow();
        ObjectNode before = workflow.deepCopy();
        Map<String, Object> supplied = Map.of("aspectRatio", ratio, "megapixels", 1.0, "prompt", "test");
        Map<String, Object> values = ComfyUiResolutionSelector.renderValues(workflow, bindings(), supplied);
        assertThat(values).containsEntry("aspectRatio", label).containsEntry("megapixels", BigDecimal.ONE)
                .containsEntry("prompt", "test").doesNotContainKeys("width", "height");
        assertThat(supplied).containsEntry("aspectRatio", ratio);
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of("aspectRatio", label)))
                .containsOnlyKeys("aspectRatio").containsEntry("aspectRatio", label);
        assertThat(workflow).isEqualTo(before);
    }

    @Test
    void rendererLeavesOmittedFieldsOmittedAndAllowsNullPixelPlaceholders() {
        ObjectNode workflow = workflow();
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings(), null)).isEmpty();
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of("prompt", "test")))
                .containsOnlyKeys("prompt");
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("width", null);
        supplied.put("height", null);
        supplied.put("aspectRatio", "1:1");
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings(), supplied))
                .containsEntry("aspectRatio", "1:1 (Square)").doesNotContainKey("megapixels");
        assertThat(ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of("megapixels", 2)))
                .containsOnlyKeys("megapixels");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.1, 16.0})
    void rendererAcceptsBothMegapixelRangeEndpoints(double value) {
        Map<String, Object> values = ComfyUiResolutionSelector.renderValues(workflow(), bindings(), Map.of("megapixels", value));
        assertThat((BigDecimal) values.get("megapixels")).isEqualByComparingTo(BigDecimal.valueOf(value));
    }

    @ParameterizedTest
    @MethodSource("invalidMegapixels")
    void rendererRejectsNonNumericNonFiniteOutOfRangeAndOffStepMegapixels(Object value) {
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow(), bindings(), Map.of("megapixels", value)), "megapixels");
    }

    private static Stream<Object> invalidMegapixels() {
        return Stream.of("2", true, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                0, -1, 0.05, 16.1, 1.05, new BigDecimal("1.00000000000001"));
    }

    @Test
    void rendererRejectsPretendEnumLabelsAndAnyExplicitPixelOverride() {
        ObjectNode workflow = workflow();
        for (Object ratio : List.of("4:5", "16:9 (Wrong)", 1)) {
            rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of("aspectRatio", ratio)), "比例");
        }
        for (String field : List.of("width", "height")) {
            rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, bindings(), Map.of(field, 1920)), "不能与显式");
        }
        List<ComfyUiInputBinding> mixed = new ArrayList<>(bindings());
        mixed.add(new ComfyUiInputBinding("width", "680", "width", "integer", null));
        mixed.add(new ComfyUiInputBinding("height", "680", "height", "integer", null));
        rejects(() -> ComfyUiResolutionSelector.renderValues(workflow, mixed,
                Map.of("aspectRatio", "16:9", "megapixels", 2, "width", 1920, "height", 1088)), "不能与显式");
    }

    private ObjectNode workflow() {
        ObjectNode workflow = json.createObjectNode();
        ObjectNode selector = workflow.putObject("681");
        selector.put("class_type", "ResolutionSelector");
        selector.putObject("inputs").put("aspect_ratio", "16:9 (Widescreen)").put("megapixels", 2).put("multiple", 32);
        ObjectNode latent = workflow.putObject("680");
        latent.put("class_type", "EmptyLatentImage");
        ObjectNode inputs = latent.putObject("inputs");
        inputs.putArray("width").add("681").add(0);
        inputs.putArray("height").add("681").add(1);
        inputs.put("batch_size", 1);
        return workflow;
    }

    private static List<ComfyUiInputBinding> bindings() {
        return List.of(new ComfyUiInputBinding("aspectRatio", "681", "aspect_ratio", "string", null),
                new ComfyUiInputBinding("megapixels", "681", "megapixels", "number", null));
    }

    private JSONObject describe(String configured) {
        JSONObject source = JSONUtil.parseObj(configured);
        JSONObject result = JSONUtil.parseObj(source.toString());
        ComfyUiResolutionSelector.describe(workflow(), bindings(), source, result);
        return result;
    }

    private static void rejects(ThrowingCallable action, String message) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(400);
            assertThat(error.getMessage()).contains(message);
        });
    }
}
