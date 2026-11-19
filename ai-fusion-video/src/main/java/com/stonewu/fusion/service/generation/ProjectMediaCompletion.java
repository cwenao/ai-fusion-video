package com.stonewu.fusion.service.generation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.entity.resource.ResourceCommandEntity;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.mapper.generation.GenerationSubmissionMapper;
import com.stonewu.fusion.mapper.project.ProjectMapper;
import com.stonewu.fusion.mapper.resource.ResourceCommandMapper;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.ai.run.kernel.AgentKernelSnapshotPayload;
import com.stonewu.fusion.service.generation.image.ImageGenerationService;
import com.stonewu.fusion.service.generation.job.GenerationJobRepository;
import com.stonewu.fusion.service.generation.job.GenerationRequestRepository;
import com.stonewu.fusion.service.generation.video.VideoGenerationService;
import com.stonewu.fusion.service.generation.review.GenerationContentReview.Target;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.resource.ResourceCommandService;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.StoryboardFields;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceCommandReceipt;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ProjectMediaCompletion {
    private static final String VIDEO_WRITEBACK_PREFIX = "storyboard-video:";
    private static final Set<String> ENDED = Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED",
            "NEEDS_REVIEW", "UNKNOWN_SUBMISSION");

    private final AgentRunMapper runs;
    private final GenerationSubmissionMapper submissions;
    private final GenerationRequestRepository requests;
    private final GenerationJobRepository jobs;
    private final VideoGenerationService videos;
    private final ImageGenerationService images;
    private final ResourceQueryService resources;
    private final ResourceCommandService commands;
    private final ResourceCommandMapper writebackCommands;
    private final CanvasJson json;
    private final ProjectMapper projects;
    private final ProjectMediaVersions versions;

    public ResourceSnapshot requireTarget(ResourceCommandContext context, ObjectNode input, GenerationKind kind) {
        var run = runs.selectByRunId(context.agentRunId());
        var pipeline = run == null ? null : ProjectMediaPipeline.forExecutor(run.getAgentType());
        if (pipeline == null || run.getProjectId() == null || pipeline.kind() != kind
                || (run.getCanvasScopeJson() != null && pipeline != ProjectMediaPipeline.ASSET)) {
            return null;
        }
        var binding = binding(run, pipeline, input);
        var resource = requireResource(run, binding, context.actor());
        versions.check(run, binding, resource);
        return resource;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResourceCommandReceipt existingVideoWriteback(ResourceCommandContext context, long shotId, String video) {
        if (context.canvasId() != null || !MediaReference.managed(video)) {
            return null;
        }
        var run = runs.selectByRunId(context.agentRunId());
        if (run == null || run.getProjectId() == null || run.getCanvasScopeJson() != null
                || ProjectMediaPipeline.forExecutor(run.getAgentType()) != ProjectMediaPipeline.VIDEO
                || !Objects.equals(run.getUserId(), context.actor().userId())
                || !Objects.equals(run.getActorTeamId(), context.actor().teamId())) {
            return null;
        }
        var committed = writebackCommands.selectList(Wrappers.lambdaQuery(ResourceCommandEntity.class)
                .eq(ResourceCommandEntity::getAgentRunId, run.getRunId())
                .eq(ResourceCommandEntity::getActorUserId, context.actor().userId())
                .eq(ResourceCommandEntity::getOrigin, "system")
                .likeRight(ResourceCommandEntity::getRequestId, VIDEO_WRITEBACK_PREFIX)
                .isNotNull(ResourceCommandEntity::getCommittedAt));
        for (var row : committed) {
            var receipt = json.read(row.getResultJson(), ResourceCommandReceipt.class);
            if (receipt.result().path("id").asLong() != shotId
                    || !video.equals(receipt.result().path("generatedVideoUrl").textValue())) {
                continue;
            }
            var shot = resources.get(CanvasValueType.SHOT, shotId, context.actor());
            if (!Objects.equals(shot.header().projectId(), run.getProjectId())) {
                throw new BusinessException(403, "回填对象不属于当前项目");
            }
            if (!video.equals(shot.data().path("generatedVideoUrl").textValue())) {
                throw new BusinessException(409, "镜头已经换用其他视频，已生成的视频可在创作记录查看");
            }
            return receipt;
        }
        return null;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Object poll(AgentRun waiting, String toolCallId, ObjectNode input) {
        if (waiting.getParentRunId() != null) {
            var parent = runs.selectByRunIdForUpdate(waiting.getParentRunId());
            if (parent == null || parent.getCancelRequestedAt() != null
                    || !Set.of("RUNNING", "WAITING_EXTERNAL").contains(parent.getStatus())) {
                throw new BusinessException(409, "项目流水线已结束，已生成的素材可在创作记录中查看");
            }
        }
        var run = runs.selectByRunIdForUpdate(waiting.getRunId());
        if (run == null || !"WAITING_EXTERNAL".equals(run.getStatus())
                || !Objects.equals(waiting.getWaitingToolCallId(), run.getWaitingToolCallId())
                || run.getCancelRequestedAt() != null || !run.getWaitExpiresAt().isAfter(runs.selectDatabaseNow())
                || run.getProjectId() == null || run.getCanvasScopeJson() != null) {
            throw new BusinessException(409, "项目生成等待已结束或执行身份已变化");
        }
        var pipeline = ProjectMediaPipeline.forExecutor(run.getAgentType());
        if (pipeline == null) {
            throw new BusinessException(403, "当前运行不支持项目生成回填");
        }
        var actor = new CanvasActor(run.getUserId(), run.getActorTeamId());
        var origin = new ResourceCommandContext(actor, "agent", null, run.getRunId(), toolCallId,
                "project-media-waiter", run.getOwnerEpoch());
        var row = submissions.selectAgentInvocation(requests.scope(origin), run.getRunId(), toolCallId);
        if (row == null) {
            throw new BusinessException(404, "此生成调用未取得受理记录");
        }
        var submission = jobs.submission(row.getId());
        if (submission.kind() != pipeline.kind() || !Objects.equals(run.getProjectId(), submission.projectId())) {
            throw new BusinessException(403, "生成任务不属于当前项目或媒体类型");
        }
        if (!ENDED.contains(submission.state())) {
            return null;
        }
        boolean video = pipeline == ProjectMediaPipeline.VIDEO;
        var videoTask = video ? videos.getById(submission.taskId()) : null;
        var imageTask = video ? null : images.getById(submission.taskId());
        if (!"SUCCEEDED".equals(submission.state())) {
            String failure = video ? videoTask.getErrorMsg() : imageTask.getErrorMsg();
            return Map.of("status", "error", "state", submission.state(), "taskId", submission.publicTaskId(),
                    "message", failure == null ? "生成未成功结束，无法回填素材" : failure);
        }
        var binding = binding(run, pipeline, input);
        if (submission.contentReview() != null && (submission.contentReview().target() != binding.target()
                || !Objects.equals(submission.contentReview().targetId(), binding.id()))) {
            throw new BusinessException(409, "审查对象与回填目标不一致");
        }
        String media;
        if (video) {
            var items = videos.listItems(submission.taskId());
            media = items.size() == 1 ? items.getFirst().getVideoUrl() : null;
        } else {
            var items = images.listItems(submission.taskId());
            media = items.size() == 1 ? items.getFirst().getImageUrl() : null;
        }
        if (!MediaReference.managed(media)) {
            throw new BusinessException(409, "没有可回填的唯一托管素材");
        }
        projects.lock(run.getProjectId());
        var resource = requireResource(run, binding, actor);
        String requestId = (video ? VIDEO_WRITEBACK_PREFIX : "project-image:") + submission.id();
        var fields = json.object(Map.of(binding.mediaField(), media));
        fields.put(binding.promptField(), video ? videoTask.getPrompt() : imageTask.getPrompt());
        if (binding.target() == Target.ASSET) {
            fields.put("sourceType", 2);
        }
        var command = versions.patch(run, requestId, binding, resource, fields);
        var receipt = commands.patch(command,
                new ResourceCommandContext(actor, "system", null, run.getRunId(), toolCallId, null, null));
        var result = json.object(Map.of("status", "success", "state", "SUCCEEDED", "taskId", submission.publicTaskId(),
                binding.target() == Target.ASSET ? "assetItemId" : "storyboardItemId", binding.id(),
                video ? "videoUrl" : "imageUrl", media, "commandId", receipt.commandId()));
        if (video) {
            result.put("autoBackfilled", true).set("version", receipt.result().get("version"));
        }
        return result;
    }

    private Binding binding(AgentRun run, ProjectMediaPipeline pipeline, ObjectNode input) {
        long id = StoryboardFields.id(input.get("reviewTargetId"), "回填对象 ID");
        int version = StoryboardFields.integer(input.get("expectedVersion"), 0, Integer.MAX_VALUE, "回填对象版本");
        return switch (pipeline) {
            case VIDEO -> new Binding(Target.SHOT_VIDEO, id, version, null, null, "generatedVideoUrl", "videoPrompt");
            case FRAME -> {
                var kernel = json.read(run.getAgentDefinitionSnapshotJson(), AgentKernelSnapshotPayload.class);
                String frame = input.path("frameType").asText();
                if (!Set.of("first", "last").contains(frame)
                        || !frame.equals(kernel.promptVariables().get("frameType"))) {
                    throw new BusinessException(400, "回填帧类型必须与本次选中的首帧或尾帧一致");
                }
                yield new Binding(frame.equals("first") ? Target.SHOT_FIRST_FRAME : Target.SHOT_LAST_FRAME,
                        id, version, null, null, frame + "FrameImageUrl", frame + "FramePrompt");
            }
            case ASSET -> new Binding(Target.ASSET, id, version, StoryboardFields.id(input.get("assetId"), "主资产 ID"),
                    StoryboardFields.integer(input.get("expectedParentVersion"), 0, Integer.MAX_VALUE, "主资产版本"),
                    "imageUrl", "aiPrompt");
        };
    }

    private ResourceSnapshot requireResource(AgentRun run, Binding binding, CanvasActor actor) {
        var resource = resources.get(binding.resourceType(), binding.id(), actor);
        if (!Objects.equals(resource.header().projectId(), run.getProjectId())) {
            throw new BusinessException(403, "回填对象不属于当前项目");
        }
        if (binding.parentId() != null && resource.data().path("assetId").asLong() != binding.parentId()) {
            throw new BusinessException(400, "子资产不属于指定主资产");
        }
        return resource;
    }

    record Binding(Target target, long id, int version, Long parentId, Integer parentVersion,
                   String mediaField, String promptField) {
        CanvasValueType resourceType() {
            return target == Target.ASSET ? CanvasValueType.ASSET_ITEM : CanvasValueType.SHOT;
        }
    }
}
