package com.stonewu.fusion.service.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasErrorCode;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.StoryboardFields;
import com.stonewu.fusion.service.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 从镜头关联的子资产 ID 解析引用，保留镜头内的关联顺序。 */
@Component
@RequiredArgsConstructor
public class StoryboardAssetReferences {
    private final ResourceQueryService resources;
    private final SystemConfigService configuration;
    private final CanvasJson json;

    public References resolve(ObjectNode shot, Long projectId, CanvasActor actor, Map<Long, ObjectNode> cached) {
        var characters = resolveList(shot, "characterIds", "character", projectId, actor, cached);
        var props = resolveList(shot, "propIds", "prop", projectId, actor, cached);
        var scene = shot.hasNonNull("sceneAssetItemId")
                ? reference(StoryboardFields.id(shot.get("sceneAssetItemId"), "sceneAssetItemId"), "scene", projectId,
                actor, cached) : null;
        return new References(characters, props, scene);
    }

    private List<ObjectNode> resolveList(ObjectNode shot, String key, String type, Long projectId, CanvasActor actor,
                                         Map<Long, ObjectNode> cached) {
        if (!shot.hasNonNull(key)) {
            return List.of();
        }
        JsonNode ids = shot.get(key);
        if (!ids.isArray()) {
            throw StoryboardFields.invalid("镜头资产引用不是 ID 数组");
        }
        List<ObjectNode> values = new ArrayList<>();
        for (JsonNode id : ids) {
            values.add(reference(StoryboardFields.id(id, key), type, projectId, actor, cached));
        }
        return values;
    }

    private ObjectNode reference(long id, String type, Long projectId, CanvasActor actor, Map<Long, ObjectNode> cached) {
        var item = cached.computeIfAbsent(id, ignored -> {
            try {
                var snapshot = resources.get(CanvasValueType.ASSET_ITEM, id, actor);
                if (!Objects.equals(snapshot.header().projectId(), projectId)) {
                    return unavailable(id, CanvasErrorCode.INVALID_RESOURCE_REFERENCE);
                }
                var data = snapshot.data();
                var parent = resources.get(CanvasValueType.ASSET, data.path("assetId").longValue(), actor);
                return data.put("assetItemId", id).put("assetType", parent.data().path("type").textValue())
                        .put("assetName", parent.data().path("name").textValue());
            } catch (CanvasException failure) {
                if (failure.getErrorCode() != CanvasErrorCode.RESOURCE_DELETED
                        && failure.getErrorCode() != CanvasErrorCode.ACCESS_DENIED) {
                    throw failure;
                }
                return unavailable(id, failure.getErrorCode());
            }
        });
        if (!item.path("unavailable").asBoolean() && !type.equals(item.path("assetType").textValue())) {
            return unavailable(id, CanvasErrorCode.INVALID_RESOURCE_REFERENCE);
        }
        return item;
    }

    private ObjectNode unavailable(long id, CanvasErrorCode errorCode) {
        return json.object(Map.of("assetItemId", id, "unavailable", true, "errorCode", errorCode));
    }

    public String mediaSource(String stored) {
        return MediaReference.managed(stored) ? stored : configuration.resolvePublicUrl(stored);
    }

    public record References(List<ObjectNode> characters, List<ObjectNode> props, ObjectNode scene) {
        public List<ObjectNode> ordered() {
            List<ObjectNode> values = new ArrayList<>(characters);
            values.addAll(props);
            if (scene != null) {
                values.add(scene);
            }
            return values;
        }
    }
}
