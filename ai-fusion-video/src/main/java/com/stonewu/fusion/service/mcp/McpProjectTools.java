package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.ProjectGrant;
import com.stonewu.fusion.service.mcp.McpProjectResources.PageRequest;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.resource.ResourceCommandService;
import com.stonewu.fusion.service.resource.StoryboardFields;
import com.stonewu.fusion.service.resource.model.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;
import static com.stonewu.fusion.service.mcp.McpProjectValues.id;
import static com.stonewu.fusion.service.mcp.McpProjectValues.ids;
import static com.stonewu.fusion.service.resource.AssetFields.IMAGE_PROMPT;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
class McpProjectTools {
    private static final Set<String> SHOT_PROMPTS = Set.of("firstFramePrompt", "lastFramePrompt", "videoPrompt");

    private final McpProjectCredentials credentials;
    private final McpProjectToolSchemas schemas;
    private final McpProjectResources resources;
    private final McpProjectMedia media;
    private final ResourceCommandService commands;
    private final CanvasJson json;

    private record Outcome(ObjectNode data, ResourceCommandReceipt receipt) {
        static Outcome read(ObjectNode data) {
            return new Outcome(data, null);
        }
    }

    CallToolResult call(McpProjectTool tool, Grant grant, Map<String, Object> arguments) {
        Long projectId = null;
        String requestId = null;
        ObjectNode args = json.object(arguments == null ? Map.of() : arguments);
        try {
            McpProjectToolSchemas.validate(schemas.contract(tool).validator(), args, "arguments");
            projectId = id(args, "projectId");
            var target = credentials.authorizeProject(grant, projectId, tool.write);
            if (tool.write) {
                requestId = args.path("requestId").asText();
            }
            String key = tool.write ? McpProjectValues.requestKey(json, projectId, tool.toolName(), requestId) : null;
            var outcome = execute(tool, target, args, key);
            var output = json.object(Map.of("ok", true, "data", outcome.data()));
            String commandId = null;
            if (outcome.receipt() != null) {
                commandId = outcome.receipt().commandId();
                output.put("requestId", requestId).put("commandId", commandId);
                var versions = output.putArray("versions");
                for (ResourceChange change : outcome.receipt().changes()) {
                    versions.addObject().put("kind", change.resourceType().code())
                            .put("id", Long.toString(change.resourceId())).put("version", change.version());
                }
            }
            audit(grant, projectId, tool, requestId, commandId, "OK");
            return result(output, false);
        } catch (BusinessException exception) {
            var error = failure(tool, args, exception);
            audit(grant, projectId, tool, requestId, null, error.getErrorCode());
            return error(error);
        } catch (RuntimeException unexpected) {
            log.error("MCP 工具异常 credentialId={} projectId={} tool={} type={}", grant.credentialId(),
                    projectId, tool.toolName(), unexpected.getClass().getSimpleName());
            audit(grant, projectId, tool, requestId, null, "INTERNAL_ERROR");
            return error(new McpProjectFailure(500, "INTERNAL_ERROR", "工具执行未完成", Map.of()));
        }
    }

    private Outcome execute(McpProjectTool tool, ProjectGrant grant, ObjectNode args, String key) {
        String kind = args.path("kind").asText();
        return switch (tool) {
            case WORKSPACE_GET -> Outcome.read(resources.workspace(grant, credentials.canWrite(grant)));
            case WORKSPACE_INITIALIZE -> initialize(grant, args, key);
            case SCRIPT_LIST -> list(grant, McpProjectValues.script(kind), args, null);
            case SCRIPT_GET -> scriptDetail(grant, McpProjectValues.script(kind), args);
            case SCRIPT_BATCH_GET -> batchDetail(grant, McpProjectValues.script(kind), args);
            case SCRIPT_CREATE -> create(grant, McpProjectValues.script(kind), args, key);
            case SCRIPT_BATCH_CREATE -> batchCreate(grant, McpProjectValues.script(kind), args, key);
            case SCRIPT_UPDATE -> update(grant, McpProjectValues.script(kind), args, key);
            case SCRIPT_BATCH_UPDATE -> batchUpdate(grant, McpProjectValues.script(kind), args, key);
            case SCRIPT_DELETE -> delete(grant, McpProjectValues.script(kind), args, key);
            case SCRIPT_BATCH_DELETE -> batchDelete(grant, SCRIPT_SCENE, args, key);
            case SCRIPT_REORDER_SCENES -> reorder(grant, SCRIPT_EPISODE, args, key);
            case STORYBOARD_LIST -> list(grant, McpProjectValues.storyboard(kind), args,
                    McpProjectValues.storyboard(args.path("parentKind").asText()));
            case STORYBOARD_GET -> Outcome.read(resources.detail(grant, McpProjectValues.storyboard(kind),
                    id(args, "id"), false));
            case STORYBOARD_BATCH_GET -> batchDetail(grant, McpProjectValues.storyboard(kind), args);
            case STORYBOARD_CREATE -> create(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_BATCH_CREATE -> batchCreate(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_UPDATE -> update(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_BATCH_UPDATE -> batchUpdate(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_DELETE -> delete(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_BATCH_DELETE -> batchDelete(grant, McpProjectValues.storyboard(kind), args, key);
            case STORYBOARD_REORDER -> reorder(grant, McpProjectValues.storyboard(args.path("parentKind").asText()), args, key);
            case STORYBOARD_APPEND_SHOTS -> appendShots(grant, args, key);
            case PROMPT_GET -> prompt(grant, args, null);
            case PROMPT_SET -> prompt(grant, args, key);
            case PROMPT_BATCH_GET, PROMPT_BATCH_SET -> batchPrompt(grant, args, key);
            case ASSET_LIST -> assetList(grant, CanvasValueType.fromCode(kind), args);
            case ASSET_GET -> Outcome.read(resources.detail(grant, CanvasValueType.fromCode(kind), id(args, "id"),
                    false));
            case ASSET_BATCH_GET -> batchDetail(grant, CanvasValueType.fromCode(kind), args);
            case ASSET_CREATE -> create(grant, CanvasValueType.fromCode(kind), args, key);
            case ASSET_BATCH_CREATE -> batchCreate(grant, CanvasValueType.fromCode(kind), args, key);
            case ASSET_UPDATE -> update(grant, CanvasValueType.fromCode(kind), args, key);
            case ASSET_BATCH_UPDATE -> batchUpdate(grant, CanvasValueType.fromCode(kind), args, key);
            case ASSET_BATCH_DELETE -> batchDelete(grant, CanvasValueType.fromCode(kind), args, key);
            case MEDIA_GET -> Outcome.read(media.get(grant, args.path("mediaResourceId").asText()));
            case MEDIA_BATCH_GET -> Outcome.read(media.batchGet(grant, args.path("mediaResourceIds")));
            case MEDIA_BIND -> bindMedia(grant, args, key);
            case MEDIA_BATCH_BIND -> batchBindMedia(grant, args, key);
        };
    }

    private Outcome initialize(ProjectGrant grant, ObjectNode args, String key) {
        var receipt = submit(grant, new InitializeProjectWorkspaceCommand(key, grant.projectId(),
                args.path("expectedVersion").intValue()));
        return new Outcome(resources.workspaceView(grant, receipt.result(), true), receipt);
    }

    private Outcome scriptDetail(ProjectGrant grant, CanvasValueType type, ObjectNode args) {
        return Outcome.read(resources.detail(grant, type, id(args, "id"), includeRawContent(type, args)));
    }

    private Outcome batchDetail(ProjectGrant grant, CanvasValueType type, ObjectNode args) {
        return Outcome.read(resources.batchDetail(grant, type, ids(args, "ids"), includeRawContent(type, args)));
    }

    private boolean includeRawContent(CanvasValueType type, ObjectNode args) {
        if (type == SCRIPT_SCENE && args.has("includeRawContent")) {
            throw McpProjectFailure.invalid("includeRawContent", "原稿参数适用于剧本主档或分集");
        }
        return args.path("includeRawContent").asBoolean(type == SCRIPT_EPISODE);
    }

    private Outcome list(ProjectGrant grant, CanvasValueType child, ObjectNode args, CanvasValueType selectedParent) {
        var parent = selectedParent == null ? McpProjectValues.parent(child) : selectedParent;
        return Outcome.read(resources.children(grant, child, parent, id(args, "parentId"), PageRequest.from(args),
                includeRawContent(child, args)));
    }

    private Outcome assetList(ProjectGrant grant, CanvasValueType type, ObjectNode args) {
        if (type == ASSET) {
            if (args.has("parentId")) {
                throw McpProjectFailure.invalid("parentId", "项目资产列表直接按项目读取");
            }
            return Outcome.read(resources.assets(grant, args.path("assetType").asText(null),
                    args.path("keyword").asText(null), PageRequest.from(args)));
        }
        if (args.has("assetType") || args.has("keyword")) {
            throw McpProjectFailure.invalid(args.has("assetType") ? "assetType" : "keyword",
                    "资产类型和关键词筛选用于资产主档列表");
        }
        return list(grant, type, args, null);
    }

    private Outcome create(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var receipt = submit(grant, createCommand(grant, type, args, (ObjectNode) args.path("fields"), key));
        return new Outcome(created(grant, type, receipt.result(), receipt.changes()), receipt);
    }

    private Outcome batchCreate(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var entries = new ArrayList<ResourceCommand>();
        for (var item : args.path("items")) {
            entries.add(createCommand(grant, type, args, (ObjectNode) item, key));
        }
        var receipt = submitBatch(grant, type, key, entries);
        var data = json.object(Map.of());
        var items = data.putArray("items");
        for (var item : receipt.result().path("items")) {
            items.add(created(grant, type, (ObjectNode) item, receipt.changes()));
        }
        return new Outcome(data, receipt);
    }

    private ResourceCreateCommand createCommand(ProjectGrant grant, CanvasValueType type, ObjectNode args,
                                                ObjectNode input, String key) {
        schemas.validateFields(type, true, input);
        var fields = McpProjectValues.internalFields(input);
        requireReferences(grant, List.of(fields));
        Long projectId = null;
        if (type == ASSET) {
            if (args.has("parentId") || args.has("expectedParentVersion")) {
                throw McpProjectFailure.invalid("parentId", "项目资产主档直接创建在项目中");
            }
            projectId = grant.projectId();
        } else {
            long parentId = id(args, "parentId");
            if (!args.has("expectedParentVersion")) {
                throw McpProjectFailure.invalid("expectedParentVersion", "创建子对象需要父版本");
            }
            resources.requireHeader(grant, McpProjectValues.parent(type), parentId);
            fields.put(McpProjectValues.parentField(type), parentId)
                    .put("parentVersion", args.path("expectedParentVersion").intValue());
        }
        return new ResourceCreateCommand(key, type, projectId, fields);
    }

    private ObjectNode created(ProjectGrant grant, CanvasValueType type, ObjectNode snapshot, List<ResourceChange> changes) {
        var output = json.object(Map.of("resource", resources.written(grant, type, snapshot, changes)));
        if (type == ASSET) {
            var children = output.putArray("items");
            snapshot.path("items").forEach(item ->
                    children.add(resources.written(grant, ASSET_ITEM, (ObjectNode) item, changes)));
            output.set("initialItem", children.get(0));
        }
        return output;
    }

    private Outcome update(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var receipt = submit(grant, updateCommand(grant, type, args, key));
        return new Outcome(written(grant, type, receipt), receipt);
    }

    private Outcome batchUpdate(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var entries = new ArrayList<ResourceCommand>();
        var batchParentVersion = args.get("expectedParentVersion");
        for (var target : args.path("targets")) {
            var targetArgs = (ObjectNode) target;
            if (batchParentVersion != null) {
                if (targetArgs.has("expectedParentVersion")) {
                    throw McpProjectFailure.invalid("expectedParentVersion", "请选择统一填写或逐项填写主资产版本");
                }
                targetArgs = targetArgs.objectNode().setAll(targetArgs);
                targetArgs.set("expectedParentVersion", batchParentVersion);
            }
            entries.add(updateCommand(grant, type, targetArgs, key));
        }
        var receipt = submitBatch(grant, type, key, entries);
        var data = json.object(Map.of());
        var items = data.putArray("items");
        for (var item : receipt.result().path("items")) {
            items.add(resources.written(grant, type, (ObjectNode) item, receipt.changes()));
        }
        return new Outcome(data, receipt);
    }

    private ResourcePatchCommand updateCommand(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var input = (ObjectNode) args.path("patch");
        schemas.validateFields(type, false, input);
        long target = id(args, "id");
        var fields = McpProjectValues.internalFields(input);
        requireReferences(grant, List.of(fields));
        return patchCommand(grant, type, target, args, key, fields);
    }

    private Outcome delete(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        long target = id(args, "id");
        resources.requireHeader(grant, type, target, true);
        var receipt = submit(grant, new ResourceDeleteCommand(key, type, target,
                args.path("expectedVersion").intValue()));
        return new Outcome(json.object(Map.of("id", Long.toString(target), "kind", McpProjectValues.kind(type),
                "deleted", true)), receipt);
    }

    private Outcome batchDelete(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        var targets = new ArrayList<ResourceBatchDeleteCommand.Target>();
        args.path("targets").forEach(target -> targets.add(new ResourceBatchDeleteCommand.Target(
                id(target, "id"), target.path("expectedVersion").intValue())));
        Long parentId = type == ASSET_ITEM ? id(args, "parentId") : null;
        Integer parentVersion = type == ASSET_ITEM ? args.path("expectedParentVersion").intValue() : null;
        var receipt = submit(grant, new ResourceBatchDeleteCommand(key, type, grant.projectId(), parentId,
                parentVersion, targets));
        var output = json.object(Map.of());
        var deleted = output.putArray("deleted");
        receipt.result().path("deleted").forEach(resource -> deleted.addObject()
                .put("id", resource.path("id").asText())
                .put("kind", McpProjectValues.kind(type))
                .put("version", resource.path("version").intValue())
                .put("deleted", true));
        return new Outcome(output, receipt);
    }

    private Outcome reorder(ProjectGrant grant, CanvasValueType parent, ObjectNode args, String key) {
        var child = parent == SCRIPT_EPISODE ? SCRIPT_SCENE : StoryboardFields.child(parent);
        long parentId = id(args, "parentId");
        resources.requireHeader(grant, parent, parentId);
        var ids = new ArrayList<Long>();
        args.path("childIds").forEach(node -> ids.add(McpProjectValues.parseId(node, "childIds")));
        int version = args.path("expectedParentVersion").intValue();
        var command = parent == SCRIPT_EPISODE
                ? new ScriptScenesCommand(key, parentId, version, ScriptScenesCommand.Mode.REORDER, List.of(), ids)
                : new StoryboardChildrenCommand(key, parent, parentId, version, StoryboardChildrenCommand.Mode.REORDER,
                List.of(), ids);
        var receipt = submit(grant, command);
        boolean script = parent == SCRIPT_EPISODE;
        var result = json.object(Map.of("parent", resources.written(grant, parent,
                (ObjectNode) receipt.result().path(script ? "episode" : "parent"), receipt.changes())));
        var children = result.putArray("children");
        receipt.result().path(script ? "scenes" : "children").forEach(node -> children.addObject()
                .put("id", node.path("id").asText()).put("kind", McpProjectValues.kind(child))
                .put("version", node.path("version").intValue()).put("sortOrder", node.path("sortOrder").intValue()));
        return new Outcome(result, receipt);
    }

    private Outcome appendShots(ProjectGrant grant, ObjectNode args, String key) {
        long sceneId = id(args, "sceneId");
        resources.requireHeader(grant, STORYBOARD_SCENE, sceneId);
        var shots = new ArrayList<ObjectNode>();
        args.path("shots").forEach(shot -> shots.add(McpProjectValues.internalFields((ObjectNode) shot)));
        requireReferences(grant, shots);
        var receipt = submit(grant, new StoryboardChildrenCommand(key, STORYBOARD_SCENE, sceneId,
                args.path("expectedParentVersion").intValue(), StoryboardChildrenCommand.Mode.APPEND, shots,
                List.of()));
        var result = json.object(Map.of("parent", resources.written(grant, STORYBOARD_SCENE,
                (ObjectNode) receipt.result().path("parent"), receipt.changes())));
        var items = result.putArray("created");
        var children = receipt.result().path("children");
        for (int index = children.size() - shots.size(); index < children.size(); index++) {
            items.add(resources.written(grant, SHOT, (ObjectNode) children.get(index), receipt.changes()));
        }
        return new Outcome(result, receipt);
    }

    private Outcome prompt(ProjectGrant grant, ObjectNode args, String key) {
        var type = CanvasValueType.fromCode(args.path("targetKind").asText());
        long target = id(args, "targetId");
        String slot = promptSlot(type, args);
        if (key == null) {
            return Outcome.read(resources.prompt(grant, type, target, slot));
        }
        var receipt = submit(grant, promptCommand(grant, type, target, args, key, slot));
        return new Outcome(resources.promptView(type, receipt.result(), slot), receipt);
    }

    private Outcome batchPrompt(ProjectGrant grant, ObjectNode args, String key) {
        var type = CanvasValueType.fromCode(args.path("targetKind").asText());
        String slot = promptSlot(type, args);
        if (key == null) {
            return Outcome.read(resources.batchPrompt(grant, type, ids(args, "targetIds"), slot));
        }
        var entries = new ArrayList<ResourceCommand>();
        for (var target : args.path("targets")) {
            entries.add(promptCommand(grant, type, id(target, "targetId"), (ObjectNode) target, key, slot));
        }
        var receipt = submitBatch(grant, type, key, entries);
        var data = json.object(Map.of());
        var items = data.putArray("items");
        for (var item : receipt.result().path("items")) {
            items.add(resources.promptView(type, (ObjectNode) item, slot));
        }
        return new Outcome(data, receipt);
    }

    private String promptSlot(CanvasValueType type, ObjectNode args) {
        String slot = args.path("slot").asText();
        boolean imagePrompt = IMAGE_PROMPT.equals(slot);
        if (type == SHOT ? !SHOT_PROMPTS.contains(slot)
                : !("aiPrompt".equals(slot) || (type == ASSET_ITEM && imagePrompt))) {
            throw McpProjectFailure.invalid("slot", "提示词槽位与对象类型不匹配");
        }
        return slot;
    }

    private ResourcePatchCommand promptCommand(ProjectGrant grant, CanvasValueType type, long target, ObjectNode args,
                                               String key, String slot) {
        var fields = json.object(Map.of());
        if (IMAGE_PROMPT.equals(slot)) {
            fields.putObject("propertyChanges").set(slot, args.get("text"));
        } else {
            fields.set(slot, args.get("text"));
        }
        return patchCommand(grant, type, target, args, key, fields);
    }

    private Outcome bindMedia(ProjectGrant grant, ObjectNode args, String key) {
        var type = CanvasValueType.fromCode(args.path("targetKind").asText());
        var receipt = submit(grant, bindCommand(grant, type, args, key));
        var data = written(grant, type, receipt);
        data.set("bindings", args.path("bindings"));
        return new Outcome(data, receipt);
    }

    private Outcome batchBindMedia(ProjectGrant grant, ObjectNode args, String key) {
        var type = CanvasValueType.fromCode(args.path("targetKind").asText());
        var entries = new ArrayList<ResourceCommand>();
        for (var target : args.path("targets")) {
            entries.add(bindCommand(grant, type, (ObjectNode) target, key));
        }
        var receipt = submitBatch(grant, type, key, entries);
        var data = json.object(Map.of());
        var items = data.putArray("items");
        var snapshots = receipt.result().path("items");
        for (int index = 0; index < snapshots.size(); index++) {
            var item = items.addObject();
            item.set("resource", resources.written(grant, type, (ObjectNode) snapshots.get(index), receipt.changes()));
            item.set("bindings", args.path("targets").get(index).path("bindings"));
        }
        return new Outcome(data, receipt);
    }

    private ResourceBindMediaCommand bindCommand(ProjectGrant grant, CanvasValueType type, ObjectNode args, String key) {
        long target = id(args, "targetId");
        resources.requireHeader(grant, type, target);
        var parentVersion = parentVersion(type, args);
        var bindings = media.checkedBindings(grant, type, args.path("bindings"));
        return new ResourceBindMediaCommand(key, type, target, args.path("expectedVersion").intValue(), parentVersion,
                bindings);
    }

    private ResourcePatchCommand patchCommand(ProjectGrant grant, CanvasValueType type, long id, ObjectNode args,
                                              String key, ObjectNode patch) {
        resources.requireHeader(grant, type, id);
        return new ResourcePatchCommand(key, type, id, args.path("expectedVersion").intValue(), patch,
                parentVersion(type, args));
    }

    private Integer parentVersion(CanvasValueType type, ObjectNode args) {
        boolean supplied = args.has("expectedParentVersion");
        if (type == ASSET_ITEM && !supplied) {
            throw McpProjectFailure.invalid("expectedParentVersion", "资产项写入需要所属资产版本");
        }
        if (type != ASSET_ITEM && supplied) {
            throw McpProjectFailure.invalid("expectedParentVersion", "此对象更新只接受目标版本");
        }
        return supplied ? args.path("expectedParentVersion").intValue() : null;
    }

    private ResourceCommandReceipt submit(ProjectGrant grant, ResourceCommand command) {
        return commands.execute(command, ResourceCommandContext.user(grant.actor(), null));
    }

    private ResourceCommandReceipt submitBatch(ProjectGrant grant, CanvasValueType type, String key,
                                               List<ResourceCommand> entries) {
        return submit(grant, new ResourceBatchWriteCommand(key, type, grant.projectId(), entries));
    }

    private void requireReferences(ProjectGrant grant, List<ObjectNode> fields) {
        for (var reference : McpProjectValues.references(fields)) {
            resources.requireHeader(grant, reference.type(), reference.id());
        }
    }

    private ObjectNode written(ProjectGrant grant, CanvasValueType type, ResourceCommandReceipt receipt) {
        return json.object(Map.of("resource", resources.written(grant, type, receipt.result(), receipt.changes())));
    }

    private McpProjectFailure failure(McpProjectTool tool, ObjectNode args, BusinessException failure) {
        if (failure instanceof McpProjectFailure direct) {
            return direct;
        }
        if (failure.getCode() == 404 || failure.getCode() == 410) {
            return McpProjectFailure.missing();
        }
        if (failure.getCode() == 403) {
            return McpProjectFailure.denied();
        }
        if (failure instanceof CanvasException canvas) {
            return switch (canvas.getErrorCode()) {
                case VERSION_CONFLICT -> versionFailure(tool, args, canvas);
                case REQUEST_CONFLICT -> new McpProjectFailure(409, "REQUEST_CONFLICT", canvas.getMessage(), Map.of());
                default -> McpProjectFailure.invalid("arguments", canvas.getMessage());
            };
        }
        if (failure instanceof MediaException mediaFailure && failure.getCode() != 400) {
            return new McpProjectFailure(failure.getCode(), mediaFailure.getErrorCode(), failure.getMessage(), Map.of());
        }
        return McpProjectFailure.invalid("arguments", failure.getMessage());
    }

    private McpProjectFailure versionFailure(McpProjectTool tool, ObjectNode args, CanvasException failure) {
        var source = failure.getDetails();
        var details = new LinkedHashMap<String, Object>();
        String resourceKind = (String) source.get("resourceType");
        if (resourceKind != null) {
            details.put("resourceKind", resourceKind);
            details.put("resourceId", source.get("resourceId").toString());
            details.put("currentVersion", source.get("currentVersion"));
        }
        String field = args.has("expectedParentVersion")
                && (tool == McpProjectTool.SCRIPT_CREATE || tool == McpProjectTool.STORYBOARD_CREATE
                    || tool == McpProjectTool.ASSET_CREATE || tool == McpProjectTool.STORYBOARD_APPEND_SHOTS
                    || tool == McpProjectTool.STORYBOARD_REORDER || tool == McpProjectTool.SCRIPT_REORDER_SCENES
                    || "asset".equals(resourceKind)) ? "expectedParentVersion" : "expectedVersion";
        if (source.containsKey("expectedVersion")) {
            details.put("expectedVersion", source.get("expectedVersion"));
        } else if (args.has(field)) {
            details.put("expectedVersion", args.path(field).intValue());
        } else if (resourceKind != null && args.has("targets")) {
            for (var target : args.path("targets")) {
                if (target.path("id").asText().equals(details.get("resourceId"))) {
                    details.put("expectedVersion", target.path("expectedVersion").intValue());
                    break;
                }
            }
        }
        return new McpProjectFailure(409, "VERSION_CONFLICT", failure.getMessage(), details);
    }

    private CallToolResult error(McpProjectFailure failure) {
        return result(json.object(Map.of("ok", false, "error", Map.of("code", failure.getErrorCode(),
                "message", failure.getMessage(), "details", failure.getDetails()))), true);
    }

    private CallToolResult result(ObjectNode value, boolean error) {
        return CallToolResult.builder().isError(error).structuredContent(value).addTextContent(json.write(value)).build();
    }

    private void audit(Grant grant, Long projectId, McpProjectTool tool, String requestId, String commandId, String code) {
        log.info("项目 MCP credentialId={} userId={} projectId={} tool={} requestId={} commandId={} result={}",
                grant.credentialId(), grant.actor().userId(), projectId, tool.toolName(), requestId, commandId,
                code);
    }
}
