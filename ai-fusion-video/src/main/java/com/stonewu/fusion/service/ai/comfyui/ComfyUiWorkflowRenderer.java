package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Applies explicit platform bindings to a deep copy of an immutable workflow version.
 */
@Component
@RequiredArgsConstructor
public class ComfyUiWorkflowRenderer {

    private final ObjectMapper objectMapper;
    private final ComfyUiWorkflowDocumentService documentService;

    public ObjectNode render(int modelType,
                             ComfyUiWorkflowVersion version,
                             Map<String, Object> values) {
        if (version == null) {
            throw new BusinessException(400, "ComfyUI 工作流版本不能为空");
        }
        ObjectNode workflow = documentService.parseApiWorkflow(version.getApiWorkflowJson()).deepCopy();
        List<ComfyUiInputBinding> bindings = documentService.parseInputBindings(
                modelType, version.getApiWorkflowJson(), version.getInputBindingsJson());
        values = dimensionValues(workflow, bindings, values);
        validateRequiredMedia(bindings, values);
        Map<String, JsonNode> writtenInputs = new HashMap<>();
        Set<String> detachedLoaders = new HashSet<>();
        for (ComfyUiInputBinding binding : bindings) {
            Object supplied = values == null ? null : values.get(binding.businessField());
            if (binding.optional() && (supplied == null
                    || supplied instanceof List<?> items && binding.index() >= items.size())) {
                ObjectNode slotInputs = (ObjectNode) workflow.path(binding.nodeId()).path("inputs");
                detachedLoaders.add(slotInputs.get(binding.inputName()).get(0).asText());
                slotInputs.remove(binding.inputName());
                continue;
            }
            if (values == null || !values.containsKey(binding.businessField())) {
                continue;
            }
            Object rawValue = values.get(binding.businessField());
            if (rawValue == null) {
                continue;
            }
            Object selectedValue = selectIndexedValue(rawValue, binding);
            JsonNode renderedValue = convertValue(selectedValue, binding);
            String targetNodeId = binding.nodeId();
            String targetInputName = binding.inputName();
            ObjectNode nodeInputs = (ObjectNode) workflow.path(targetNodeId).path("inputs");
            JsonNode current = nodeInputs.get(binding.inputName());
            if (isUploadedMedia(binding.valueType()) && isLink(current)) {
                JsonNode sourceNode = workflow.path(current.get(0).asText());
                // 上传媒体连线只支持对应加载节点的槽位 0；其他链路必须明确绑定加载节点的文件字段。
                String fileInput = loaderFileInput(binding.valueType(),
                        sourceNode.path("class_type").asText());
                if (fileInput == null || current.get(1).asInt() != 0
                        || !sourceNode.path("inputs").path(fileInput).isTextual()) {
                    throw new BusinessException(400,
                            "上传媒体请直接绑定加载节点的文件输入；连线仅支持 LoadImage/VHS_LoadVideo/"
                                    + "VHS_LoadAudioUpload 的槽位 0 输出: "
                                    + binding.nodeId() + "." + binding.inputName());
                }
                targetNodeId = current.get(0).asText();
                targetInputName = fileInput;
                nodeInputs = (ObjectNode) sourceNode.get("inputs");
            }
            String targetKey = targetNodeId + "." + targetInputName;
            JsonNode previous = writtenInputs.putIfAbsent(targetKey, renderedValue);
            if (previous != null && !previous.equals(renderedValue)) {
                throw new BusinessException(400, "多个媒体绑定不能覆盖同一个加载输入: " + targetKey);
            }
            nodeInputs.set(targetInputName, renderedValue);
        }
        // Remove only detached loaders without another consumer; never leave placeholder images in the prompt.
        for (String loader : detachedLoaders) {
            boolean referenced = false;
            for (JsonNode node : workflow) {
                for (JsonNode input : node.path("inputs")) {
                    if (isLink(input) && loader.equals(input.get(0).asText())) {
                        referenced = true;
                    }
                }
            }
            if (!referenced) {
                workflow.remove(loader);
            }
        }
        return workflow;
    }

    public void validateDimensions(int modelType, ComfyUiWorkflowVersion version, Map<String, Object> values) {
        ObjectNode workflow = documentService.parseApiWorkflow(version.getApiWorkflowJson());
        var bindings = documentService.parseInputBindings(modelType,
                version.getApiWorkflowJson(), version.getInputBindingsJson());
        dimensionValues(workflow, bindings, values);
        validateRequiredMedia(bindings, values);
    }

    private void validateMediaCoverage(List<ComfyUiInputBinding> bindings, Map<String, Object> values) {
        for (String field : List.of("referenceImages", "firstFrame", "lastFrame",
                "referenceVideos", "referenceAudios")) {
            Object raw = values.get(field);
            if (raw == null) {
                continue;
            }
            int count = raw instanceof List<?> items ? items.size() : 1;
            Set<Integer> covered = new HashSet<>();
            boolean wholeList = false;
            for (ComfyUiInputBinding binding : bindings) {
                if (!field.equals(binding.businessField())) {
                    continue;
                }
                if (binding.index() == null) {
                    if ("string_list".equals(binding.valueType())) {
                        wholeList = true;
                    } else {
                        covered.add(0);
                    }
                } else {
                    if (binding.index() >= count) {
                        if (binding.optional()) {
                            continue;
                        }
                        throw new BusinessException(400, "工作流输入 " + field + " 缺少索引 " + binding.index());
                    }
                    covered.add(binding.index());
                }
            }
            if (wholeList) {
                continue;
            }
            for (int index = 0; index < count; index++) {
                if (!covered.contains(index)) {
                    throw new BusinessException(400, "ComfyUI 工作流输入 " + field + " 的索引 " + index
                            + "（第 " + (index + 1) + " 项）未绑定，不能忽略素材；请扩展工作流绑定或减少输入");
                }
            }
        }
    }

    private void validateRequiredMedia(List<ComfyUiInputBinding> bindings, Map<String, Object> values) {
        validateMediaCoverage(bindings, values);
        for (var binding : bindings) {
            if (!binding.optional() && binding.index() != null && isUploadedMedia(binding.valueType())
                    && (values == null || values.get(binding.businessField()) == null)) {
                throw new BusinessException(400, "工作流输入 " + binding.businessField() + " 缺少索引 " + binding.index());
            }
        }
    }

    private Map<String, Object> dimensionValues(ObjectNode workflow, List<ComfyUiInputBinding> bindings,
                                                Map<String, Object> supplied) {
        var values = ComfyUiResolutionSelector.renderValues(workflow, bindings, supplied);
        for (var binding : bindings) {
            if (("width".equals(binding.businessField()) || "height".equals(binding.businessField()))
                    && values.get(binding.businessField()) != null
                    && isLink(workflow.path(binding.nodeId()).path("inputs").path(binding.inputName()))) {
                throw new BusinessException(400, "图片宽高绑定不能覆盖工作流连线，请绑定选择器的比例和百万像素参数: "
                        + binding.nodeId() + "." + binding.inputName());
            }
        }
        return values;
    }

    private static boolean isUploadedMedia(String valueType) {
        return "uploaded_image".equals(valueType)
                || "uploaded_video".equals(valueType)
                || "uploaded_audio".equals(valueType);
    }

    /**
     * 每种上传类型允许重定向的加载节点与文件输入；其他加载器必须直接绑定文件字段。
     */
    private static String loaderFileInput(String valueType, String classType) {
        if ("uploaded_image".equals(valueType) && "LoadImage".equals(classType)) {
            return "image";
        }
        if ("uploaded_video".equals(valueType) && "VHS_LoadVideo".equals(classType)) {
            return "video";
        }
        if ("uploaded_audio".equals(valueType) && "VHS_LoadAudioUpload".equals(classType)) {
            return "audio";
        }
        return null;
    }

    /**
     * ComfyUI 连线格式：["源节点ID", 槽位索引]。
     */
    private static boolean isLink(JsonNode value) {
        return value != null && value.isArray() && value.size() == 2
                && value.get(0).isTextual() && value.get(1).isInt();
    }

    private Object selectIndexedValue(Object rawValue, ComfyUiInputBinding binding) {
        if (binding.index() == null) {
            if (rawValue instanceof List<?> values
                    && binding.valueType().startsWith("uploaded_")
                    && values.size() == 1) {
                return values.getFirst();
            }
            return rawValue;
        }
        if (!(rawValue instanceof List<?> values) || binding.index() >= values.size()) {
            throw new BusinessException(400,
                    "工作流输入 " + binding.businessField() + " 缺少索引 " + binding.index());
        }
        return values.get(binding.index());
    }

    private JsonNode convertValue(Object value, ComfyUiInputBinding binding) {
        try {
            return switch (binding.valueType()) {
                case "string", "uploaded_image", "uploaded_video", "uploaded_audio" ->
                        objectMapper.getNodeFactory().textNode(requireText(value, binding));
                case "integer" -> objectMapper.getNodeFactory().numberNode(toLong(value, binding));
                case "number" -> objectMapper.getNodeFactory().numberNode(toDouble(value, binding));
                case "boolean" -> objectMapper.getNodeFactory().booleanNode(toBoolean(value, binding));
                case "string_list" -> stringList(value, binding);
                default -> throw new BusinessException(400,
                        "不支持的 ComfyUI 输入绑定类型: " + binding.valueType());
            };
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    "工作流输入 " + binding.businessField() + " 不是有效数字");
        }
    }

    private String requireText(Object value, ComfyUiInputBinding binding) {
        String text = value == null ? "" : value.toString();
        if (text.isBlank()) {
            throw new BusinessException(400, "工作流输入不能为空: " + binding.businessField());
        }
        return text;
    }

    private long toLong(Object value, ComfyUiInputBinding binding) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(requireText(value, binding));
    }

    private double toDouble(Object value, ComfyUiInputBinding binding) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(requireText(value, binding));
    }

    private boolean toBoolean(Object value, ComfyUiInputBinding binding) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = requireText(value, binding);
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw new BusinessException(400,
                    "工作流输入 " + binding.businessField() + " 不是有效布尔值");
        }
        return Boolean.parseBoolean(text);
    }

    private ArrayNode stringList(Object value, ComfyUiInputBinding binding) {
        if (!(value instanceof List<?> values)) {
            throw new BusinessException(400,
                    "工作流输入 " + binding.businessField() + " 必须是列表");
        }
        ArrayNode result = objectMapper.createArrayNode();
        for (Object item : values) {
            if (item == null || item.toString().isBlank()) {
                throw new BusinessException(400,
                        "工作流输入 " + binding.businessField() + " 包含空值");
            }
            result.add(item.toString());
        }
        return result;
    }
}
