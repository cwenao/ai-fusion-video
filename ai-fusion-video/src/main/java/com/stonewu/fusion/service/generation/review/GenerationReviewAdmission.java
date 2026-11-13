package com.stonewu.fusion.service.generation.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.job.GenerationJobException;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.stonewu.fusion.service.generation.review.GenerationContentReview.*;

@Component
@RequiredArgsConstructor
public class GenerationReviewAdmission {
    private final AgentRunMapper runs;
    private final CanvasAccessPolicy access;
    private final ResourceQueryService resources;
    private final GenerationReviewModels models;
    private final CanvasJson json;

    public String pipeline(AiChatReqVO request, CanvasActor actor) {
        if (request.getGenerationReviewModelId() == null) {
            return null;
        }
        if (request.getProjectId() == null || request.getCanvasContext() != null || request.getContext() == null) {
            throw GenerationJobException.invalid("AI 审查需要明确的项目和生成对象");
        }
        long projectId = request.getProjectId();
        var context = json.object(request.getContext());
        Target target = switch (Objects.toString(request.getAgentType(), "")) {
            case "asset_image_gen" -> Target.ASSET;
            case "storyboard_video_gen" -> {
                if (context.path("promptOnly").asBoolean()) {
                    throw GenerationJobException.invalid("仅生成提示词时不能启用 AI 审查");
                }
                yield Target.SHOT_VIDEO;
            }
            case "storyboard_frame_gen" -> switch (context.path("frameType").asText()) {
                case "first" -> Target.SHOT_FIRST_FRAME;
                case "last" -> Target.SHOT_LAST_FRAME;
                default -> throw GenerationJobException.invalid("AI 审查需要明确首帧或尾帧");
            };
            default -> throw GenerationJobException.invalid("此任务不支持生成后 AI 审查");
        };
        access.requireProjectEditor(projectId, actor);
        boolean asset = target == Target.ASSET;
        var targetIds = ids(context.path(asset ? "selectedAssetItemIds" : "selectedStoryboardItemIds"));
        Long storyboardId = asset ? null : id(context.path("storyboardId"));
        Long sceneId = context.hasNonNull("storyboardSceneId") ? id(context.get("storyboardSceneId")) : null;
        if (storyboardId != null) {
            require(CanvasValueType.STORYBOARD, storyboardId, projectId, actor);
        }
        if (sceneId != null) {
            require(CanvasValueType.STORYBOARD_SCENE, sceneId, projectId, actor);
        }
        String framePrompt = target == Target.SHOT_FIRST_FRAME || target == Target.SHOT_LAST_FRAME
                ? context.path("framePrompt").asText() : null;
        GenerationKind kind = target == Target.SHOT_VIDEO ? GenerationKind.VIDEO : GenerationKind.IMAGE;
        return json.write(new Plan(models.freeze(request.getGenerationReviewModelId(), kind), kind, target,
                projectId, storyboardId, sceneId, targetIds, framePrompt));
    }

    public Selection agent(ResourceCommandContext context, GenerationKind kind, JsonNode suppliedTarget) {
        var run = runs.selectOne(new LambdaQueryWrapper<AgentRun>().select(AgentRun::getGenerationReviewJson,
                        AgentRun::getProjectId, AgentRun::getAgentType)
                .eq(AgentRun::getRunId, context.agentRunId()).eq(AgentRun::getUserId, context.actor().userId()));
        if (run == null || run.getGenerationReviewJson() == null) {
            return null;
        }
        var plan = json.read(run.getGenerationReviewJson(), Plan.class);
        String agentType = switch (plan.target()) {
            case ASSET -> "asset_image_executor";
            case SHOT_VIDEO -> "storyboard_video_executor";
            case SHOT_FIRST_FRAME, SHOT_LAST_FRAME -> "storyboard_frame_executor";
        };
        if (plan.kind() != kind || !Objects.equals(plan.projectId(), run.getProjectId())
                || !agentType.equals(run.getAgentType())) {
            throw GenerationJobException.invalid("当前生成不在已确认的 AI 审查范围内");
        }
        long target = id(suppliedTarget);
        if (!plan.targetIds().contains(target)) {
            throw GenerationJobException.invalid("审查对象必须是用户本次选中的镜头或子资产");
        }
        return new Selection(plan.model().id(), plan, target);
    }

    public Criteria freeze(Selection selection, GenerationKind kind, String prompt, boolean hasReferences,
                           Long projectId, CanvasActor actor) {
        if (selection == null) {
            return null;
        }
        if (prompt == null || prompt.isBlank()) {
            throw GenerationJobException.invalid("启用 AI 审查时请填写生成提示词");
        }
        var plan = selection.plan();
        if (plan == null) {
            return new Criteria(models.freeze(selection.modelId(), kind), null, null, json.object(Map.of()), prompt,
                    hasReferences);
        }
        if (!Objects.equals(projectId, plan.projectId())) {
            throw GenerationJobException.invalid("生成项目与审查范围不一致");
        }
        ObjectNode data = json.object(Map.of());
        if (plan.target() == Target.ASSET) {
            var item = require(CanvasValueType.ASSET_ITEM, selection.targetId(), projectId, actor).data();
            var asset = require(CanvasValueType.ASSET, id(item.path("assetId")), projectId, actor).data();
            data.set("子资产", fields(item, "name", "itemType", "properties"));
            data.set("资产", fields(asset, "name", "type", "description", "properties"));
            if (plan.sceneId() != null) {
                data.set("使用场次", scene(plan.sceneId(), projectId, actor));
            }
        } else {
            var shot = require(CanvasValueType.SHOT, selection.targetId(), projectId, actor).data();
            if (shot.path("storyboardId").asLong() != plan.storyboardId()) {
                throw GenerationJobException.invalid("审查镜头不属于所选分镜");
            }
            data.set("镜头", fields(shot, "shotNumber", "autoShotNumber", "content", "sceneExpectation", "shotType",
                    "cameraMovement", "cameraAngle", "focalLength", "duration", "sound", "dialogue", "soundEffect",
                    "music", "transition", "remark"));
            if (shot.hasNonNull("storyboardSceneId")) {
                data.set("场次", scene(id(shot.get("storyboardSceneId")), projectId, actor));
            }
            if (plan.framePrompt() != null) {
                data.put("用户确认的帧提示词", plan.framePrompt());
            }
        }
        return new Criteria(plan.model(), plan.target(), selection.targetId(), data, prompt, hasReferences);
    }

    private ObjectNode scene(long id, long projectId, CanvasActor actor) {
        return fields(require(CanvasValueType.STORYBOARD_SCENE, id, projectId, actor).data(),
                "sceneNumber", "sceneHeading", "location", "timeOfDay", "intExt");
    }

    private ResourceSnapshot require(CanvasValueType type, long id, long projectId, CanvasActor actor) {
        var snapshot = resources.get(type, id, actor);
        if (!Objects.equals(snapshot.header().projectId(), projectId)) {
            throw GenerationJobException.invalid("审查对象不属于当前项目");
        }
        return snapshot;
    }

    private ObjectNode fields(ObjectNode source, String... keys) {
        var result = json.object(Map.of());
        for (String key : keys) {
            if (source.hasNonNull(key)) {
                result.set(key, source.get(key));
            }
        }
        return result;
    }

    private List<Long> ids(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            throw GenerationJobException.invalid("启用 AI 审查时至少选择一个镜头或子资产");
        }
        var result = new ArrayList<Long>();
        values.forEach(value -> result.add(id(value)));
        return result.stream().distinct().toList();
    }

    private long id(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw GenerationJobException.invalid("请提供有效的审查镜头或子资产编号");
        }
        return value.longValue();
    }
}
