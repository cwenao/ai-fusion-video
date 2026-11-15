package com.stonewu.fusion.service.resource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.repository.resource.ResourceReadRepository;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasErrorCode;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.resource.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;

@Component
@RequiredArgsConstructor
public class ResourceBatchDeleteHandler implements ResourceCommandHandler {
    private final ScriptResourceDeleteHandler scripts;
    private final StoryboardResourceDeleteHandler storyboards;
    private final AssetResourceDeleteHandler assets;
    private final ResourceReadRepository resources;
    private final CanvasJson json;

    private record Key(CanvasValueType type, long id) {
    }

    @Override
    public CanvasValueType resourceType() {
        return SHOT;
    }

    @Override
    public Set<CanvasValueType> resourceTypes() {
        return Set.of(SCRIPT_SCENE, STORYBOARD_SCENE, SHOT, ASSET, ASSET_ITEM);
    }

    @Override
    public ResourceOperation operation() {
        return ResourceOperation.BATCH_DELETE;
    }

    @Override
    public void evict(ResourceCommand command) {
        delegate(command.resourceType()).evict();
    }

    @Override
    public Long authorize(ResourceCommand input, ResourceCommandContext context, ResourceCommandReceipt previous) {
        var command = (ResourceBatchDeleteCommand) input;
        boolean replay = previous != null;
        if (command.resourceType() == ASSET_ITEM) {
            if (command.parentId() == null || command.expectedParentVersion() == null) {
                throw invalid("批量删除子资产需要指定主资产 ID 和版本");
            }
            requireInProject(ASSET, command.parentId(), command.projectId(), replay);
        } else if (command.parentId() != null || command.expectedParentVersion() != null) {
            throw invalid("仅批量删除子资产需要父级 ID 和版本");
        }
        Set<Long> ids = new HashSet<>();
        for (var target : command.targets()) {
            if (!ids.add(target.id())) {
                throw invalid("批量删除的目标 ID 不能重复");
            }
            requireInProject(command.resourceType(), target.id(), command.projectId(), replay);
        }
        return command.projectId();
    }

    @Override
    public ResourceMutation apply(ResourceCommand input, ResourceCommandContext context) {
        var command = (ResourceBatchDeleteCommand) input;
        // 与单条删除使用相同的项目锁，整批写入持有该锁直到提交。
        resources.require(PROJECT, command.projectId(), true);
        Integer parentVersion = command.expectedParentVersion();
        if (command.resourceType() == ASSET_ITEM) {
            var parent = resources.require(ASSET, command.parentId(), true);
            ResourceMutations.requireVersion(parent, parentVersion);
            for (var target : command.targets()) {
                var child = resources.require(ASSET_ITEM, target.id(), false);
                if (resources.data(child).path("assetId").longValue() != parent.resourceId()) {
                    throw invalid("所有子资产必须属于同一个指定的主资产");
                }
            }
        }
        var handler = delegate(command.resourceType());
        var result = json.object(Map.of());
        var deleted = result.putArray("deleted");
        Map<Key, ResourceChange> changes = new LinkedHashMap<>();
        List<ObjectNode> undo = new ArrayList<>();
        for (var target : command.targets()) {
            var single = new ResourceDeleteCommand(command.requestId(), command.resourceType(), target.id(),
                    target.expectedVersion(), parentVersion);
            var mutation = handler.apply(single, context);
            deleted.add(mutation.result());
            undo.add(mutation.previousValues());
            for (var change : mutation.changes()) {
                changes.merge(new Key(change.resourceType(), change.resourceId()), change,
                        ResourceBatchDeleteHandler::latestChange);
            }
            if (command.resourceType() == ASSET_ITEM) {
                parentVersion = changes.get(new Key(ASSET, command.parentId())).version();
            }
        }
        return new ResourceMutation(result, List.copyOf(changes.values()), json.object(Map.of("operations", undo)));
    }

    private ResourceCommandHandler delegate(CanvasValueType type) {
        return switch (type) {
            case SCRIPT_SCENE -> scripts;
            case STORYBOARD_SCENE, SHOT -> storyboards;
            case ASSET, ASSET_ITEM -> assets;
            default -> throw invalid("此类型不支持批量删除");
        };
    }

    private void requireInProject(CanvasValueType type, long id, long projectId, boolean replay) {
        resources.find(type, id, false, replay)
                .filter(header -> Objects.equals(header.projectId(), projectId))
                .orElseThrow(() -> new CanvasException(404, CanvasErrorCode.RESOURCE_DELETED, "业务对象不存在或已删除", Map.of()));
    }

    private static ResourceChange latestChange(ResourceChange previous, ResourceChange latest) {
        // 同批中的共享父对象只发布最终版本。媒体引用观察器也按最终版本登记一次。
        Set<String> fields = new LinkedHashSet<>(previous.changedFields());
        fields.addAll(latest.changedFields());
        return new ResourceChange(latest.eventId(), latest.resourceType(), latest.resourceId(), latest.projectId(),
                latest.version(), latest.deleted(), List.copyOf(fields));
    }

    private static CanvasException invalid(String message) {
        return new CanvasException(CanvasErrorCode.INVALID_COMMAND, message);
    }
}
