package com.stonewu.fusion.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.repository.resource.StoryboardMutationRepository;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.resource.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;

@Component
@RequiredArgsConstructor
public class StoryboardResourcePatchHandler implements ResourceCommandHandler {
    private final StoryboardCommandSupport support;
    private final StoryboardMutationRepository writes;
    private final StoryboardFields fields;
    private final StoryboardRelations relations;
    private final StoryboardVersionView versions;

    @Override
    public CanvasValueType resourceType() {
        return STORYBOARD;
    }

    @Override
    public Set<CanvasValueType> resourceTypes() {
        return Set.of(STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT);
    }

    @Override
    public ResourceOperation operation() {
        return ResourceOperation.PATCH;
    }

    @Override
    public void evict() {
        support.evict();
    }

    @Override
    public Long authorize(ResourceCommand input, ResourceCommandContext context, ResourceCommandReceipt previous) {
        var command = (ResourcePatchCommand) input;
        return support.authorize(command.resourceType(), command.resourceId(), context, false);
    }

    @Override
    public ResourceMutation apply(ResourceCommand input, ResourceCommandContext context) {
        var command = (ResourcePatchCommand) input;
        var change = writes.lock(command.resourceType(), command.resourceId());
        ResourceMutations.requireVersion(change.target, command.expectedVersion());
        var patch = command.patch();
        if (patch.has("customDataChanges")) {
            patch = mergeCustomData(command.resourceType(), patch, change.data(change.target).path("customData"));
        }
        var values = fields.values(command.resourceType(), patch, false);
        relations.validate(change.board, command.resourceType(), patch, command.resourceId());
        change.patch(change.target, values);
        change.finish();
        return change.result(
                versions.attach(command.resourceType(), change.current(command.resourceType(), command.resourceId())));
    }

    private ObjectNode mergeCustomData(CanvasValueType type, ObjectNode patch, JsonNode current) {
        if (type != SHOT || patch.has("customData") || !(patch.get("customDataChanges") instanceof ObjectNode changes)) {
            throw StoryboardFields.invalid("customDataChanges 仅用于镜头，须为对象，不能与 customData 同时提交");
        }
        if (!current.isMissingNode() && !current.isNull() && !current.isObject()) {
            throw StoryboardFields.invalid("已有自定义镜头数据不是对象");
        }
        var merged = current instanceof ObjectNode object ? object.deepCopy() : patch.objectNode();
        changes.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                merged.remove(entry.getKey());
            } else {
                merged.set(entry.getKey(), entry.getValue());
            }
        });
        var result = patch.deepCopy();
        result.remove("customDataChanges");
        result.set("customData", merged);
        return result;
    }
}
