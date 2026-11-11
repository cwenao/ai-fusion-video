package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.VideoTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** ResolutionSelector 是尺寸的唯一权威；平台只映射比例与百万像素档位，不覆盖输出连线。 */
public final class ComfyUiResolutionSelector {
    private static final String MARKER = "comfyuiResolutionSelector";
    private static final String TIERS = "comfyuiResolutionTiers";
    private static final Map<String, String> ASPECT_RATIOS = aspectRatios();

    private ComfyUiResolutionSelector() {
    }

    public static void describe(ObjectNode workflow, List<ComfyUiInputBinding> bindings,
                                JSONObject configured, JSONObject result) {
        Selector selector = selector(workflow, bindings);
        if (selector == null) {
            return;
        }
        if (configured.containsKey("defaultAspectRatio")
                && !selector.defaultAspectRatio().equals(configured.get("defaultAspectRatio"))) {
            throw invalid("配置默认比例必须与工作流默认比例一致：" + selector.defaultAspectRatio());
        }
        List<String> supportedRatios = configured.containsKey("supportedAspectRatios")
                ? supportedRatios(configured.get("supportedAspectRatios")) : new ArrayList<>(ASPECT_RATIOS.keySet());
        if (!supportedRatios.contains(selector.defaultAspectRatio())) {
            throw invalid("支持的比例必须包含工作流默认比例：" + selector.defaultAspectRatio());
        }
        Map<String, BigDecimal> tiers = new LinkedHashMap<>();
        if (configured.containsKey(TIERS)) {
            JSONObject declared = object(configured.get(TIERS), "分辨率档位必须是 JSON 对象");
            for (Map.Entry<String, Object> entry : declared.entrySet()) {
                if (entry.getKey().isBlank() || !entry.getKey().equals(entry.getKey().trim())) {
                    throw invalid("分辨率档位名称不能为空或包含首尾空格");
                }
                tiers.put(entry.getKey(), megapixels(entry.getValue()));
            }
            if (tiers.isEmpty()) {
                throw invalid("分辨率档位不能为空");
            }
        } else {
            tiers.put(selector.defaultMegapixels().toPlainString() + "MP", selector.defaultMegapixels());
        }
        String defaultResolution = null;
        if (configured.containsKey("defaultResolution")) {
            Object value = configured.get("defaultResolution");
            if (!(value instanceof String name) || !tiers.containsKey(name)) {
                throw invalid("默认分辨率必须是已声明的档位");
            }
            defaultResolution = name;
        } else {
            for (Map.Entry<String, BigDecimal> tier : tiers.entrySet()) {
                if (tier.getValue().compareTo(selector.defaultMegapixels()) == 0) {
                    defaultResolution = tier.getKey();
                    break;
                }
            }
            if (defaultResolution == null) {
                throw invalid("分辨率档位必须包含工作流默认百万像素值，或明确配置默认分辨率");
            }
        }
        result.set(MARKER, new JSONObject().set("nodeId", selector.nodeId()).set("multiple", selector.multiple())
                .set("defaultAspectRatio", selector.defaultAspectRatio())
                .set("defaultMegapixels", selector.defaultMegapixels()));
        result.set(TIERS, new JSONObject(tiers));
        result.set("supportedAspectRatios", supportedRatios).set("supportedResolutions", new ArrayList<>(tiers.keySet()));
        result.set("defaultAspectRatio", selector.defaultAspectRatio()).set("defaultResolution", defaultResolution);
        // 删除模型的像素默认值，避免调用方把选择器默认比例重新解释为方形宽高。
        result.remove("defaultWidth");
        result.remove("defaultHeight");
        result.remove("supportedSizes");
        result.set("supportAutoSize", false);
    }

    public static void validateTask(ImageTask task, JSONObject config) {
        if (!hasSelector(config)) {
            return;
        }
        JSONObject marker = object(config.get(MARKER), "ResolutionSelector 能力标记必须是 JSON 对象");
        String ratio = nonBlank(task.getRatio());
        String aspectRatio = nonBlank(task.getAspectRatio());
        if (ratio != null && aspectRatio != null && !ratio.equals(aspectRatio)) {
            throw invalid("ratio 与 aspectRatio 比例不能冲突");
        }
        if (ratio == null) {
            ratio = aspectRatio == null ? config.getStr("defaultAspectRatio") : aspectRatio;
        }
        if (!supportedRatios(config.get("supportedAspectRatios")).contains(ratio)) {
            throw invalid("不支持的画面比例：" + ratio);
        }
        String resolution = nonBlank(task.getResolution());
        if (resolution == null) {
            resolution = config.getStr("defaultResolution");
        }
        JSONObject tiers = object(config.get(TIERS), "分辨率档位必须是 JSON 对象");
        if (resolution == null || !tiers.containsKey(resolution)) {
            throw invalid("不支持的分辨率档位：" + resolution);
        }
        BigDecimal mp = megapixels(tiers.get(resolution));
        if (task.getWidth() != null || task.getHeight() != null) {
            if (task.getWidth() == null || task.getHeight() == null || task.getWidth() <= 0 || task.getHeight() <= 0) {
                throw invalid("显式宽高必须同时填写且均为正数");
            }
            int multiple = multiple(marker.get("multiple"));
            String[] parts = ratio.split(":");
            int widthRatio = Integer.parseInt(parts[0]);
            int heightRatio = Integer.parseInt(parts[1]);
            double scale = Math.sqrt(mp.doubleValue() * 1024 * 1024 / (widthRatio * heightRatio));
            // Python round 使用 ties-to-even，不能用 Math.round 的半入规则。
            int width = (int) Math.rint(widthRatio * scale / multiple) * multiple;
            int height = (int) Math.rint(heightRatio * scale / multiple) * multiple;
            if (task.getWidth() != width || task.getHeight() != height) {
                throw invalid("显式宽高与选择器比例和分辨率不一致，应为 " + width + "×" + height);
            }
        }
        task.setRatio(ratio);
        task.setAspectRatio(ratio);
        task.setResolution(resolution);
    }

    public static Map<String, Object> taskValues(ImageTask task, JSONObject config) {
        if (!hasSelector(config)) {
            return Map.of();
        }
        validateTask(task, config);
        JSONObject tiers = object(config.get(TIERS), "分辨率档位必须是 JSON 对象");
        return Map.of("aspectRatio", task.getRatio(), "megapixels", megapixels(tiers.get(task.getResolution())));
    }

    public static void validateTask(VideoTask task, JSONObject config) {
        if (!hasSelector(config)) {
            return;
        }
        String ratio = nonBlank(task.getRatio());
        if (ratio == null) {
            ratio = config.getStr("defaultAspectRatio");
        }
        if (!supportedRatios(config.get("supportedAspectRatios")).contains(ratio)) {
            throw invalid("不支持的画面比例：" + ratio);
        }
        String resolution = nonBlank(task.getResolution());
        if (resolution == null) {
            resolution = config.getStr("defaultResolution");
        }
        JSONObject tiers = object(config.get(TIERS), "分辨率档位必须是 JSON 对象");
        if (resolution == null || !tiers.containsKey(resolution)) {
            throw invalid("不支持的分辨率档位：" + resolution);
        }
        megapixels(tiers.get(resolution));
        task.setRatio(ratio);
        task.setResolution(resolution);
    }

    public static Map<String, Object> taskValues(VideoTask task, JSONObject config) {
        if (!hasSelector(config)) {
            return Map.of();
        }
        validateTask(task, config);
        JSONObject tiers = object(config.get(TIERS), "分辨率档位必须是 JSON 对象");
        return Map.of("aspectRatio", task.getRatio(), "megapixels", megapixels(tiers.get(task.getResolution())));
    }

    public static Map<String, Object> renderValues(ObjectNode workflow, List<ComfyUiInputBinding> bindings,
                                                   Map<String, Object> supplied) {
        Map<String, Object> values = supplied == null ? new LinkedHashMap<>() : new LinkedHashMap<>(supplied);
        if (selector(workflow, bindings) == null) {
            return values;
        }
        if (values.get("width") != null || values.get("height") != null) {
            throw invalid("ResolutionSelector 参数不能与显式 width/height 混用，请通过比例和分辨率设置尺寸");
        }
        if (values.get("aspectRatio") != null) {
            String ratio = shortRatio(values.get("aspectRatio"));
            values.put("aspectRatio", ASPECT_RATIOS.get(ratio));
        }
        if (values.get("megapixels") != null) {
            values.put("megapixels", megapixels(values.get("megapixels")));
        }
        if (values.get("resolutionMultiple") != null) {
            values.put("resolutionMultiple", multiple(values.get("resolutionMultiple")));
        }
        if (values.get("upscaleMegapixels") != null) {
            values.put("upscaleMegapixels", megapixels(values.get("upscaleMegapixels")));
        }
        return values;
    }

    private static Selector selector(ObjectNode workflow, List<ComfyUiInputBinding> bindings) {
        List<ComfyUiInputBinding> ratios = bindings.stream()
                .filter(binding -> "aspectRatio".equals(binding.businessField())).toList();
        List<ComfyUiInputBinding> pixels = bindings.stream()
                .filter(binding -> "megapixels".equals(binding.businessField())).toList();
        if (ratios.isEmpty() && pixels.isEmpty()) {
            return null;
        }
        if (ratios.size() != 1 || pixels.size() != 1) {
            throw invalid("aspectRatio 与 megapixels 必须成对且各自唯一绑定");
        }
        ComfyUiInputBinding ratio = ratios.getFirst();
        ComfyUiInputBinding pixel = pixels.getFirst();
        if (!"aspect_ratio".equals(ratio.inputName()) || !"string".equals(ratio.valueType())
                || !"megapixels".equals(pixel.inputName()) || !"number".equals(pixel.valueType())
                || ratio.nodeId() == null || !ratio.nodeId().equals(pixel.nodeId())
                || ratio.index() != null || pixel.index() != null || ratio.optional() || pixel.optional()) {
            throw invalid("aspectRatio(string) 与 megapixels(number) 必须直接绑定同一 ResolutionSelector，不允许 index/optional");
        }
        String nodeId = ratio.nodeId();
        for (ComfyUiInputBinding binding : bindings) {
            if ("resolutionMultiple".equals(binding.businessField())
                    && (!nodeId.equals(binding.nodeId()) || !"multiple".equals(binding.inputName())
                    || !"integer".equals(binding.valueType()) || binding.index() != null || binding.optional())) {
                throw invalid("resolutionMultiple(integer) 必须直接绑定同一 ResolutionSelector 的 multiple");
            }
        }
        JsonNode node = workflow.path(nodeId);
        if (!"ResolutionSelector".equals(node.path("class_type").asText())) {
            throw invalid("比例和百万像素必须绑定 ResolutionSelector 节点");
        }
        for (ComfyUiInputBinding binding : bindings) {
            if (nodeId.equals(binding.nodeId()) && ("aspect_ratio".equals(binding.inputName())
                    || "megapixels".equals(binding.inputName())) && binding != ratio && binding != pixel) {
                throw invalid("ResolutionSelector 的比例和百万像素输入不能被其他业务字段重复绑定");
            }
        }
        JsonNode inputs = node.path("inputs");
        JsonNode savedRatio = inputs.path("aspect_ratio");
        if (!savedRatio.isTextual() || !ASPECT_RATIOS.containsValue(savedRatio.textValue())) {
            throw invalid("工作流默认比例必须是 ResolutionSelector 的真实枚举标签");
        }
        JsonNode savedMp = inputs.path("megapixels");
        if (!savedMp.isNumber()) {
            throw invalid("工作流默认百万像素必须是数值");
        }
        JsonNode savedMultiple = inputs.path("multiple");
        if (!savedMultiple.isIntegralNumber()) {
            throw invalid("ResolutionSelector multiple 必须是整数");
        }
        boolean consumed = false;
        for (JsonNode consumer : workflow) {
            JsonNode consumerInputs = consumer.path("inputs");
            JsonNode width = consumerInputs.path("width");
            JsonNode height = consumerInputs.path("height");
            if (references(width, nodeId) || references(height, nodeId)) {
                if (!output(width, nodeId, 0) || !output(height, nodeId, 1)) {
                    throw invalid("选择器消费者的 width/height 必须成对连接同一 ResolutionSelector 的输出槽 0/1");
                }
                consumed = true;
            }
        }
        if (!consumed) {
            throw invalid("ResolutionSelector 必须有实际消费其宽高输出槽 0/1 的节点");
        }
        return new Selector(nodeId, multiple(savedMultiple.numberValue()), shortRatio(savedRatio.textValue()),
                megapixels(savedMp.numberValue()));
    }

    private static boolean references(JsonNode input, String nodeId) {
        return input.isArray() && !input.isEmpty() && input.get(0).isTextual() && nodeId.equals(input.get(0).textValue());
    }

    private static boolean output(JsonNode input, String nodeId, int slot) {
        return references(input, nodeId) && input.size() == 2 && input.get(1).isIntegralNumber()
                && input.get(1).canConvertToInt() && input.get(1).intValue() == slot;
    }

    private static List<String> supportedRatios(Object value) {
        if (!(value instanceof Iterable<?> entries)) {
            throw invalid("supportedAspectRatios 必须是比例数组");
        }
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof String ratio) || !ASPECT_RATIOS.containsKey(ratio)) {
                throw invalid("不支持的画面比例：" + entry);
            }
            supported.add(ratio);
        }
        if (supported.isEmpty()) {
            throw invalid("支持的画面比例不能为空");
        }
        return new ArrayList<>(supported);
    }

    private static String shortRatio(Object value) {
        if (value instanceof String ratio) {
            if (ASPECT_RATIOS.containsKey(ratio)) {
                return ratio;
            }
            for (Map.Entry<String, String> entry : ASPECT_RATIOS.entrySet()) {
                if (entry.getValue().equals(ratio)) {
                    return entry.getKey();
                }
            }
        }
        throw invalid("比例必须是支持的短比例或准确的 ResolutionSelector 枚举标签：" + value);
    }

    private static BigDecimal megapixels(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw invalid("megapixels 必须是有限数值，不能是字符串");
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
        if (decimal.compareTo(new BigDecimal("0.1")) < 0 || decimal.compareTo(new BigDecimal("16")) > 0
                || decimal.scale() > 1) {
            throw invalid("megapixels 必须在 0.1 到 16 之间，且步长为 0.1");
        }
        return decimal;
    }

    private static int multiple(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 8 || number.doubleValue() > 128 || number.doubleValue() % 4 != 0) {
            throw invalid("ResolutionSelector multiple 必须在 8 到 128 之间，且步长为 4");
        }
        return number.intValue();
    }

    private static JSONObject object(Object value, String message) {
        if (!(value instanceof JSONObject object)) {
            throw invalid(message);
        }
        return object;
    }

    private static boolean hasSelector(JSONObject config) {
        return config != null && config.containsKey(MARKER);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> aspectRatios() {
        Map<String, String> ratios = new LinkedHashMap<>();
        ratios.put("1:1", "1:1 (Square)");
        ratios.put("2:3", "2:3 (Portrait Photo)");
        ratios.put("3:2", "3:2 (Photo)");
        ratios.put("3:4", "3:4 (Portrait Standard)");
        ratios.put("4:3", "4:3 (Standard)");
        ratios.put("9:16", "9:16 (Portrait Widescreen)");
        ratios.put("16:9", "16:9 (Widescreen)");
        ratios.put("21:9", "21:9 (Ultrawide)");
        return ratios;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }

    private record Selector(String nodeId, int multiple, String defaultAspectRatio, BigDecimal defaultMegapixels) {
    }
}
