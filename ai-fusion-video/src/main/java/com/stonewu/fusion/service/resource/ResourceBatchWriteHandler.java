package com.stonewu.fusion.service.resource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.repository.resource.ResourceReadRepository;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasErrorCode;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.media.ResourceBindMediaHandler;
import com.stonewu.fusion.service.resource.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;

@Component
@RequiredArgsConstructor
public class ResourceBatchWriteHandler implements ResourceCommandHandler {
    private final ScriptResourceCreateHandler scriptCreate;
    private final ScriptResourcePatchHandler scriptPatch;
    private final StoryboardResourceCreateHandler storyboardCreate;
    private final StoryboardResourcePatchHandler storyboardPatch;
    private final AssetResourceCreateHandler assetCreate;
    private final AssetResourcePatchHandler assetPatch;
    private final ResourceBindMediaHandler mediaBind;
    private final ResourceReadRepository resources;
    private final StoryboardVersionView storyboardVersions;
    private final CanvasJson json;

    private record Key(CanvasValueType type, long id) {
    }

    private record Parent(Key key, int expectedVersion) {
    }

    @Override
    public CanvasValueType resourceType() {
        return SHOT;
    }

    @Override
    public Set<CanvasValueType> resourceTypes() {
        return Set.of(SCRIPT, SCRIPT_EPISODE, SCRIPT_SCENE, STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT,
                ASSET, ASSET_ITEM);
    }

    @Override
    public ResourceOperation operation() {
        return ResourceOperation.BATCH_WRITE;
    }

    @Override
    public Long authorize(ResourceCommand input, ResourceCommandContext context, ResourceCommandReceipt previous) {
        var batch = (ResourceBatchWriteCommand) input;
        var operation = batch.entries().getFirst().operation();
        var delegate = delegate(batch.resourceType(), operation);
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < batch.entries().size(); index++) {
            var entry = batch.entries().get(index);
            if (entry.resourceType() != batch.resourceType() || entry.operation() != operation) {
                throw invalid("批量写入只接受同一操作和同一对象类型");
            }
            switch (entry) {
                case ResourcePatchCommand patch -> {
                    if (patch.patch().isEmpty() || !seen.add(patch.resourceId())) {
                        throw invalid("批量更新的目标 ID 不能重复，修改字段不能为空");
                    }
                }
                case ResourceBindMediaCommand binding -> {
                    if (!seen.add(binding.resourceId())) {
                        throw invalid("批量绑定的目标 ID 不能重复");
                    }
                }
                default -> {
                }
            }
            // 单条新建主资产在重放时也检查已创建资源的权限。
            ResourceCommandReceipt prior = previous == null ? null : new ResourceCommandReceipt(
                    previous.commandId(), previous.requestId(),
                    (ObjectNode) previous.result().path("items").get(index), previous.changes());
            if (!Objects.equals(delegate.authorize(entry, context, prior), batch.projectId())) {
                throw new CanvasException(404, CanvasErrorCode.RESOURCE_DELETED, "业务对象不存在或已删除", Map.of());
            }
        }
        return batch.projectId();
    }

    @Override
    public ResourceMutation apply(ResourceCommand input, ResourceCommandContext context) {
        var batch = (ResourceBatchWriteCommand) input;
        var type = batch.resourceType();
        var delegate = delegate(type, batch.entries().getFirst().operation());
        resources.require(PROJECT, batch.projectId(), true);
        var parents = new ArrayList<Parent>();
        for (var entry : batch.entries()) {
            var parent = checkVersions(entry);
            if (parent != null) {
                requireVersion(parent.key(), parent.expectedVersion());
            }
            parents.add(parent);
        }

        Map<Key, ResourceChange> changes = new LinkedHashMap<>();
        var undo = new ArrayList<ObjectNode>();
        var created = new ArrayList<ResourceChange>();
        var snapshots = new ArrayList<ObjectNode>();
        for (int index = 0; index < batch.entries().size(); index++) {
            var mutation = delegate.apply(withCurrentParent(batch.entries().get(index), parents.get(index)), context);
            snapshots.add(mutation.result());
            undo.add(mutation.previousValues());
            for (var change : mutation.changes()) {
                changes.merge(new Key(change.resourceType(), change.resourceId()), change, ResourceMutations::latestChange);
                if (change.version() == 0) {
                    created.add(change);
                }
            }
        }

        var result = json.object(Map.of());
        var items = result.putArray("items");
        for (var snapshot : snapshots) {
            var key = new Key(type, snapshot.path("id").longValue());
            var finalSnapshot = changes.get(key).version() == snapshot.path("version").intValue()
                    ? snapshot : resources.data(resources.require(type, key.id(), false));
            if (type == ASSET_ITEM) {
                finalSnapshot.put("assetVersion", resources.require(ASSET, finalSnapshot.path("assetId").longValue(),
                        false).version());
            } else if (type == STORYBOARD_EPISODE || type == STORYBOARD_SCENE || type == SHOT) {
                storyboardVersions.attach(type, finalSnapshot);
            }
            items.add(finalSnapshot);
        }
        return new ResourceMutation(result, List.copyOf(changes.values()),
                json.object(Map.of("operations", undo, "created", created)));
    }

    @Override
    public void evict(ResourceCommand input) {
        var batch = (ResourceBatchWriteCommand) input;
        delegate(batch.resourceType(), batch.entries().getFirst().operation()).evict();
    }

    private Parent checkVersions(ResourceCommand entry) {
        var type = entry.resourceType();
        return switch (entry) {
            case ResourceCreateCommand create -> creationParent(create);
            case ResourcePatchCommand patch -> updateParent(type, patch.resourceId(), patch.expectedVersion(),
                    patch.expectedParentVersion());
            case ResourceBindMediaCommand bind -> updateParent(type, bind.resourceId(), bind.expectedVersion(),
                    bind.expectedParentVersion());
            default -> throw invalid("此操作不支持批量写入");
        };
    }

    private Parent creationParent(ResourceCreateCommand command) {
        var type = command.resourceType();
        if (type == ASSET) {
            return null;
        }
        var fields = command.fields();
        var parentType = switch (type) {
            case SCRIPT_EPISODE -> SCRIPT;
            case SCRIPT_SCENE -> SCRIPT_EPISODE;
            case ASSET_ITEM -> ASSET;
            default -> StoryboardFields.parent(type);
        };
        String parentField = switch (type) {
            case SCRIPT_EPISODE -> "scriptId";
            case SCRIPT_SCENE -> "episodeId";
            case ASSET_ITEM -> "assetId";
            default -> StoryboardFields.parentKey(type);
        };
        int version = AssetCommandSupport.version(fields.get("parentVersion"), "parentVersion");
        return new Parent(new Key(parentType, fields.path(parentField).longValue()), version);
    }

    private Parent updateParent(CanvasValueType type, long id, int expected, Integer expectedParent) {
        var header = requireVersion(new Key(type, id), expected);
        if (type == ASSET_ITEM && expectedParent != null) {
            return new Parent(new Key(ASSET, resources.data(header).path("assetId").longValue()), expectedParent);
        }
        if (type == ASSET_ITEM || expectedParent != null) {
            throw invalid("仅更新子资产需要提供父级资产版本");
        }
        return null;
    }

    private ResourceHeader requireVersion(Key key, int expected) {
        var header = resources.require(key.type(), key.id(), false);
        if (header.version() != expected) {
            throw new CanvasException(409, CanvasErrorCode.VERSION_CONFLICT, "业务内容已被其他操作修改",
                    Map.of("resourceType", key.type().code(), "resourceId", key.id(),
                            "currentVersion", header.version(), "expectedVersion", expected));
        }
        ResourceMutations.requireVersion(header, expected);
        return header;
    }

    private ResourceCommand withCurrentParent(ResourceCommand entry, Parent parent) {
        if (parent == null) {
            return entry;
        }
        // 进入整批操作时已核对调用方版本。后续条目使用前面条目写入后的父版本。
        int version = resources.require(parent.key().type(), parent.key().id(), false).version();
        return switch (entry) {
            case ResourceCreateCommand create -> new ResourceCreateCommand(create.requestId(), create.resourceType(),
                    create.projectId(), create.fields().deepCopy().put("parentVersion", version));
            case ResourcePatchCommand patch -> new ResourcePatchCommand(patch.requestId(), patch.resourceType(),
                    patch.resourceId(), patch.expectedVersion(), patch.patch(), version);
            case ResourceBindMediaCommand bind -> new ResourceBindMediaCommand(bind.requestId(), bind.resourceType(),
                    bind.resourceId(), bind.expectedVersion(), version, bind.bindings());
            default -> throw invalid("此操作不支持批量写入");
        };
    }

    private ResourceCommandHandler delegate(CanvasValueType type, ResourceOperation operation) {
        ResourceCommandHandler handler = switch (operation) {
            case CREATE -> switch (type) {
                case SCRIPT_EPISODE, SCRIPT_SCENE -> scriptCreate;
                case STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT -> storyboardCreate;
                case ASSET, ASSET_ITEM -> assetCreate;
                default -> throw invalid("此对象类型不支持批量新增");
            };
            case PATCH -> switch (type) {
                case SCRIPT, SCRIPT_EPISODE, SCRIPT_SCENE -> scriptPatch;
                case STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT -> storyboardPatch;
                case ASSET, ASSET_ITEM -> assetPatch;
                default -> throw invalid("此对象类型不支持批量修改");
            };
            case BIND_MEDIA -> mediaBind;
            default -> throw invalid("此操作不支持批量写入");
        };
        if (!handler.resourceTypes().contains(type)) {
            throw invalid("此对象类型不支持该操作");
        }
        return handler;
    }

    private static CanvasException invalid(String message) {
        return new CanvasException(CanvasErrorCode.INVALID_COMMAND, message);
    }
}
