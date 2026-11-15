package com.stonewu.fusion.service.mcp;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.repository.resource.AssetReadRepository;
import com.stonewu.fusion.repository.resource.ResourceReadRepository;
import com.stonewu.fusion.repository.resource.StoryboardReadRepository;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.ProjectGrant;
import com.stonewu.fusion.service.media.MediaSourceFields;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.model.ResourceChange;
import com.stonewu.fusion.service.resource.model.ResourceHeader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;
import static com.stonewu.fusion.service.resource.AssetFields.IMAGE_PROMPT;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
class McpProjectResources {
    private final ResourceReadRepository resources;
    private final StoryboardReadRepository storyboards;
    private final AssetReadRepository assets;
    private final ResourceQueryService queries;
    private final McpProjectToolSchemas schemas;
    private final McpProjectMedia media;
    private final CanvasJson json;

    record PageRequest(int number, int size) {
        static PageRequest from(ObjectNode args) {
            return new PageRequest(args.path("page").asInt(1), args.path("pageSize").asInt(20));
        }
    }

    private record Parent(CanvasValueType type, long id) {
    }

    ResourceHeader requireHeader(ProjectGrant grant, CanvasValueType type, long id) {
        return requireHeader(grant, type, id, false);
    }

    ResourceHeader requireHeader(ProjectGrant grant, CanvasValueType type, long id, boolean includeDeleted) {
        return resources.find(type, id, false, includeDeleted)
                .filter(header -> Objects.equals(header.projectId(), grant.projectId()))
                .orElseThrow(McpProjectFailure::missing);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode workspace(ProjectGrant grant, boolean writable) {
        return workspaceView(grant, queries.workspace(grant.projectId(), grant.actor()), writable);
    }

    ObjectNode workspaceView(ProjectGrant grant, ObjectNode snapshot, boolean writable) {
        var result = json.object(Map.of("project", Map.of("id", Long.toString(grant.projectId()),
                "name", snapshot.path("project").path("name").asText(),
                "version", snapshot.path("projectVersion").intValue())));
        for (String key : List.of("script", "storyboard")) {
            var node = snapshot.path(key);
            if (node.isNull()) {
                result.putNull(key);
            } else {
                result.putObject(key).put("id", node.path("id").asText())
                        .put("version", node.path("version").intValue());
            }
        }
        if (writable) {
            result.set("mediaUpload", media.uploadOptions(grant.projectId()));
        }
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode detail(ProjectGrant grant, CanvasValueType type, long id, boolean includeRawContent) {
        requireHeader(grant, type, id);
        var source = queries.get(type, id, grant.actor()).data();
        var result = view(grant, type, source, false, includeRawContent);
        var parent = parent(type, source);
        if (parent != null) {
            result.put("parentVersion", requireHeader(grant, parent.type(), parent.id()).version());
        }
        if (media.hasBindingFields(type)) {
            media.readBindings(grant, type, source, result.putArray("mediaBindings"));
        }
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode children(ProjectGrant grant, CanvasValueType child, CanvasValueType parentType, long parentId,
                               PageRequest page, boolean includeRawContent) {
        var header = requireHeader(grant, parentType, parentId);
        IPage<Long> found = switch (child) {
            case SCRIPT_EPISODE, SCRIPT_SCENE -> resources.pageScriptChildren(child, parentId, page.number(), page.size());
            case ASSET_ITEM -> assets.pageItems(parentId, page.number(), page.size());
            default -> storyboards.pageChildren(child, parentType, parentId, page.number(), page.size());
        };
        var result = page(grant, child, found.getRecords(), found.getTotal(), page, includeRawContent);
        return result.put("parentId", Long.toString(parentId)).put("parentVersion", header.version());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode assets(ProjectGrant grant, String type, String keyword, PageRequest page) {
        long projectId = grant.projectId();
        var allowed = List.of(projectId);
        var ids = assets.ids(grant.actor(), allowed, projectId, type, keyword, page.number(), page.size());
        return page(grant, ASSET, ids, assets.count(grant.actor(), allowed, projectId, type, keyword), page, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode prompt(ProjectGrant grant, CanvasValueType type, long id, String slot) {
        requireHeader(grant, type, id);
        var data = queries.get(type, id, grant.actor()).data();
        return promptView(type, data, slot);
    }

    ObjectNode promptView(CanvasValueType type, ObjectNode data, String slot) {
        var result = json.object(Map.of("targetKind", type.code(), "targetId", data.path("id").asText(),
                "slot", slot, "version", data.path("version").intValue()));
        var text = IMAGE_PROMPT.equals(slot) ? data.path("properties").path(slot) : data.path(slot);
        if (text.isMissingNode()) {
            result.putNull("text");
        } else {
            result.set("text", text);
        }
        if (type == ASSET_ITEM) {
            result.put("parentId", data.path("assetId").asText())
                    .put("parentVersion", data.path("assetVersion").intValue());
        }
        return result;
    }

    ObjectNode written(ProjectGrant grant, CanvasValueType type, ObjectNode source, List<ResourceChange> changes) {
        var result = view(grant, type, source, false, false);
        var parent = parent(type, source);
        if (parent != null) {
            changes.stream().filter(change -> change.resourceType() == parent.type() && change.resourceId() == parent.id())
                    .findFirst().ifPresent(change -> result.put("parentVersion", change.version()));
            if (type == ASSET_ITEM) {
                result.put("parentVersion", source.path("assetVersion").intValue());
            }
        }
        return result;
    }

    private ObjectNode page(ProjectGrant grant, CanvasValueType type, List<Long> ids, long total, PageRequest page,
                            boolean includeRawContent) {
        var result = json.object(Map.of("page", page.number(), "pageSize", page.size(),
                "hasMore", (long) page.number() * page.size() < total));
        var items = result.putArray("items");
        for (long id : ids) {
            items.add(view(grant, type, queries.get(type, id, grant.actor()).data(), true, includeRawContent));
        }
        return result;
    }

    private ObjectNode view(ProjectGrant grant, CanvasValueType type, ObjectNode source, boolean summary,
                            boolean includeRawContent) {
        var result = json.object(Map.of("id", source.path("id").asText(), "kind", McpProjectValues.kind(type),
                "projectId", Long.toString(grant.projectId()), "version", source.path("version").intValue()));
        var parent = parent(type, source);
        if (parent != null) {
            result.put("parentId", Long.toString(parent.id()));
        }
        var fields = result.putObject("fields");
        copy(source, fields, List.of("title", "name", "type", "itemType", "episodeNumber", "sceneNumber",
                "sceneHeading", "shotNumber", "shotType", "duration", "sortOrder", "totalEpisodes", "totalScenes"));
        if (!summary) {
            copy(source, fields, schemas.readFields(type));
            copy(source, fields, List.of("aiPrompt", "firstFramePrompt", "lastFramePrompt", "videoPrompt"));
            if (type == ASSET || type == ASSET_ITEM) {
                copyWithoutMedia(type, source, fields, "properties");
            } else if (type == SHOT) {
                copyWithoutMedia(type, source, fields, "customData");
                copy(source, fields, List.of("autoShotNumber", "storyboardVersion", "episodeVersion", "sceneVersion"));
            } else if (type == STORYBOARD_EPISODE) {
                copy(source, fields, List.of("storyboardVersion", "composeStatus", "composeJobId",
                        "composeErrorMsg", "composedAt", "bgmTaskId", "bgmErrorMsg"));
            } else if (type == STORYBOARD_SCENE) {
                copy(source, fields, List.of("storyboardVersion", "episodeVersion"));
            } else if (type == STORYBOARD) {
                copy(source, fields, List.of("totalDuration"));
            }
            copy(source, fields, List.of("createTime", "updateTime"));
        }
        if (type == SCRIPT_EPISODE) {
            fields.set("synopsis", source.get("synopsis"));
        }
        if (includeRawContent) {
            copy(source, fields, List.of("rawContent"));
        } else {
            fields.remove("rawContent");
        }
        return result;
    }

    private void copyWithoutMedia(CanvasValueType type, ObjectNode source, ObjectNode target, String field) {
        if (source.get(field) instanceof ObjectNode properties) {
            var data = properties.deepCopy();
            String prefix = field + ".";
            for (String path : MediaSourceFields.fields(type).keySet()) {
                if (path.startsWith(prefix)) {
                    data.remove(path.substring(prefix.length()));
                }
            }
            target.set(field, data);
        } else {
            target.putNull(field);
        }
    }

    private void copy(ObjectNode source, ObjectNode target, Iterable<String> names) {
        for (String name : names) {
            JsonNode value = source.get(name);
            if (value != null) {
                target.set(name, McpProjectValues.externalField(name, value));
            }
        }
    }

    private Parent parent(CanvasValueType type, ObjectNode data) {
        return switch (type) {
            case SCRIPT_EPISODE, SCRIPT_SCENE, STORYBOARD_EPISODE, STORYBOARD_SCENE, ASSET_ITEM ->
                    new Parent(McpProjectValues.parent(type), data.path(McpProjectValues.parentField(type)).longValue());
            case SHOT -> shotParent(data);
            default -> null;
        };
    }

    private Parent shotParent(ObjectNode data) {
        if (data.hasNonNull("storyboardSceneId")) {
            return new Parent(STORYBOARD_SCENE, data.path("storyboardSceneId").longValue());
        }
        if (data.hasNonNull("storyboardEpisodeId")) {
            return new Parent(STORYBOARD_EPISODE, data.path("storyboardEpisodeId").longValue());
        }
        return new Parent(STORYBOARD, data.path("storyboardId").longValue());
    }
}
