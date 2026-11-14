package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McpProjectValues {
    private static final Map<String, CanvasValueType> REFERENCES = Map.of(
            "assetId", CanvasValueType.ASSET, "sceneAssetId", CanvasValueType.ASSET,
            "character_asset_id", CanvasValueType.ASSET, "characterAssetIds", CanvasValueType.ASSET,
            "propAssetIds", CanvasValueType.ASSET, "scriptEpisodeId", CanvasValueType.SCRIPT_EPISODE,
            "sceneAssetItemId", CanvasValueType.ASSET_ITEM, "characterIds", CanvasValueType.ASSET_ITEM,
            "propIds", CanvasValueType.ASSET_ITEM);

    record Reference(CanvasValueType type, long id) {
    }

    private McpProjectValues() {
    }

    static String requestKey(CanvasJson json, long projectId, String operation, String requestId) {
        return "mcp:" + McpProjectCredentials.digest(json.write(List.of(Long.toString(projectId), operation, requestId)));
    }

    static long id(JsonNode args, String field) {
        return parseId(args.path(field), field);
    }

    static CanvasValueType script(String kind) {
        return switch (kind) {
            case "script" -> CanvasValueType.SCRIPT;
            case "episode" -> CanvasValueType.SCRIPT_EPISODE;
            case "scene" -> CanvasValueType.SCRIPT_SCENE;
            default -> throw McpProjectFailure.invalid("kind", "剧本对象类型无效");
        };
    }

    static CanvasValueType storyboard(String kind) {
        return switch (kind) {
            case "storyboard" -> CanvasValueType.STORYBOARD;
            case "episode" -> CanvasValueType.STORYBOARD_EPISODE;
            case "scene" -> CanvasValueType.STORYBOARD_SCENE;
            case "shot" -> CanvasValueType.SHOT;
            default -> throw McpProjectFailure.invalid("kind", "分镜对象类型无效");
        };
    }

    static String kind(CanvasValueType type) {
        return switch (type) {
            case SCRIPT_EPISODE, STORYBOARD_EPISODE -> "episode";
            case SCRIPT_SCENE, STORYBOARD_SCENE -> "scene";
            default -> type.code();
        };
    }

    static CanvasValueType parent(CanvasValueType child) {
        return switch (child) {
            case SCRIPT_EPISODE -> CanvasValueType.SCRIPT;
            case SCRIPT_SCENE -> CanvasValueType.SCRIPT_EPISODE;
            case STORYBOARD_EPISODE -> CanvasValueType.STORYBOARD;
            case STORYBOARD_SCENE -> CanvasValueType.STORYBOARD_EPISODE;
            case SHOT -> CanvasValueType.STORYBOARD_SCENE;
            case ASSET_ITEM -> CanvasValueType.ASSET;
            default -> throw new IllegalArgumentException("对象没有父容器");
        };
    }

    static String parentField(CanvasValueType child) {
        return switch (child) {
            case SCRIPT_EPISODE -> "scriptId";
            case SCRIPT_SCENE, STORYBOARD_SCENE -> "episodeId";
            case STORYBOARD_EPISODE -> "storyboardId";
            case SHOT -> "storyboardSceneId";
            case ASSET_ITEM -> "assetId";
            default -> throw new IllegalArgumentException("对象没有父容器");
        };
    }

    static ObjectNode internalFields(ObjectNode fields) {
        return (ObjectNode) convert(fields, true);
    }

    static JsonNode externalField(String field, JsonNode value) {
        return convertField(field, value, false);
    }

    static Set<Reference> references(List<ObjectNode> rows) {
        Set<Reference> result = new LinkedHashSet<>();
        rows.forEach(row -> collect(row, result));
        return result;
    }

    private static void collect(JsonNode value, Set<Reference> result) {
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                var type = REFERENCES.get(entry.getKey());
                var field = entry.getValue();
                if (type == null) {
                    collect(field, result);
                } else if (field.isArray()) {
                    field.forEach(item -> result.add(new Reference(type, item.longValue())));
                } else if (!field.isNull()) {
                    result.add(new Reference(type, field.longValue()));
                }
            });
        } else if (value.isArray()) {
            value.forEach(item -> collect(item, result));
        }
    }

    private static JsonNode convert(JsonNode value, boolean internal) {
        if (value.isObject()) {
            var object = JsonNodeFactory.instance.objectNode();
            value.fields().forEachRemaining(entry -> object.set(entry.getKey(),
                    convertField(entry.getKey(), entry.getValue(), internal)));
            return object;
        }
        if (value.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> array.add(convert(item, internal)));
            return array;
        }
        return value;
    }

    private static JsonNode convertField(String field, JsonNode value, boolean internal) {
        if (value.isNull()) {
            return value;
        }
        if (REFERENCES.containsKey(field)) {
            if (value.isArray()) {
                var array = JsonNodeFactory.instance.arrayNode();
                value.forEach(item -> array.add(convertField(field, item, internal)));
                return array;
            }
            return internal ? LongNode.valueOf(parseId(value, field)) : TextNode.valueOf(value.asText());
        }
        return convert(value, internal);
    }

    private static long parseId(JsonNode value, String field) {
        try {
            long id = Long.parseLong(value.asText());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException invalid) {
            throw McpProjectFailure.invalid(field, "ID 超出正整数范围或格式无效");
        }
        throw McpProjectFailure.invalid(field, "ID 必须为正整数");
    }
}
