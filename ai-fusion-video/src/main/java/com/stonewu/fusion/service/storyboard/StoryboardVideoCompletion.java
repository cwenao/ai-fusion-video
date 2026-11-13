package com.stonewu.fusion.service.storyboard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.mapper.generation.GenerationSubmissionMapper;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.job.GenerationJobRepository;
import com.stonewu.fusion.service.generation.job.GenerationRequestRepository;
import com.stonewu.fusion.service.generation.video.VideoGenerationService;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.resource.ResourceCommandService;
import com.stonewu.fusion.service.resource.ResourceMutations;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.StoryboardFields;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourcePatchCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StoryboardVideoCompletion {
    private static final Set<String> ENDED = Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED",
            "NEEDS_REVIEW", "UNKNOWN_SUBMISSION");

    private final AgentRunMapper runs;
    private final GenerationSubmissionMapper submissions;
    private final GenerationRequestRepository requests;
    private final GenerationJobRepository jobs;
    private final VideoGenerationService videos;
    private final ResourceQueryService resources;
    private final ResourceCommandService commands;
    private final CanvasJson json;

    public boolean requireTarget(ResourceCommandContext context, ObjectNode input) {
        var run = runs.selectByRunId(context.agentRunId());
        if (run == null || run.getProjectId() == null || !StoryboardVideoPipeline.EXECUTOR.equals(run.getAgentType())
                || run.getCanvasScopeJson() != null) {
            return false;
        }
        Target target = target(input);
        var shot = resources.get(CanvasValueType.SHOT, target.id(), context.actor());
        if (!Objects.equals(shot.header().projectId(), run.getProjectId())) {
            throw new BusinessException(403, "生成镜头不属于当前项目");
        }
        ResourceMutations.requireVersion(shot.header(), target.version());
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Object poll(AgentRun waiting, String toolCallId, ObjectNode input) {
        if (waiting.getParentRunId() != null) {
            var parent = runs.selectByRunIdForUpdate(waiting.getParentRunId());
            if (parent == null || parent.getCancelRequestedAt() != null
                    || !Set.of("RUNNING", "WAITING_EXTERNAL").contains(parent.getStatus())) {
                throw new BusinessException(409, "分镜流水线已结束，已生成的视频可在创作记录中查看");
            }
        }
        var run = runs.selectByRunIdForUpdate(waiting.getRunId());
        if (run == null || !"WAITING_EXTERNAL".equals(run.getStatus())
                || !Objects.equals(waiting.getWaitingToolCallId(), run.getWaitingToolCallId())
                || run.getCancelRequestedAt() != null || !run.getWaitExpiresAt().isAfter(runs.selectDatabaseNow())
                || run.getProjectId() == null || run.getCanvasScopeJson() != null
                || !StoryboardVideoPipeline.EXECUTOR.equals(run.getAgentType())) {
            throw new BusinessException(409, "分镜视频等待已结束或执行身份已变化");
        }
        var actor = new CanvasActor(run.getUserId(), run.getActorTeamId());
        var origin = new ResourceCommandContext(actor, "agent", null, run.getRunId(), toolCallId,
                "storyboard-video-waiter", run.getOwnerEpoch());
        var row = submissions.selectAgentInvocation(requests.scope(origin), run.getRunId(), toolCallId);
        if (row == null) {
            throw new BusinessException(404, "此镜头生成调用未取得受理记录");
        }
        var submission = jobs.submission(row.getId());
        if (submission.kind() != GenerationKind.VIDEO || !Objects.equals(run.getProjectId(), submission.projectId())) {
            throw new BusinessException(403, "生成任务不属于当前项目镜头");
        }
        if (!ENDED.contains(submission.state())) {
            return null;
        }
        if (!"SUCCEEDED".equals(submission.state())) {
            var failure = videos.getById(submission.taskId()).getErrorMsg();
            return Map.of("status", "error", "state", submission.state(), "taskId", submission.publicTaskId(),
                    "message", failure == null ? "视频生成未成功结束，未回填镜头" : failure);
        }
        Target target = target(input);
        if (submission.contentReview() != null && !Objects.equals(submission.contentReview().targetId(), target.id())) {
            throw new BusinessException(409, "审查镜头与回填目标不一致");
        }
        var shot = resources.get(CanvasValueType.SHOT, target.id(), actor);
        if (!Objects.equals(shot.header().projectId(), submission.projectId())) {
            throw new BusinessException(403, "回填镜头不属于生成项目");
        }
        var items = videos.listItems(submission.taskId());
        if (items.size() != 1 || !MediaReference.managed(items.getFirst().getVideoUrl())) {
            throw new BusinessException(409, "没有可回填的唯一托管视频");
        }
        String video = items.getFirst().getVideoUrl();
        var fields = json.object(Map.of("generatedVideoUrl", video));
        fields.put("videoPrompt", videos.getById(submission.taskId()).getPrompt());
        var receipt = commands.patch(new ResourcePatchCommand("storyboard-video:" + submission.id(),
                CanvasValueType.SHOT, target.id(), target.version(), fields),
                new ResourceCommandContext(actor, "system", null, run.getRunId(), toolCallId, null, null));
        return Map.of("status", "success", "state", "SUCCEEDED", "taskId", submission.publicTaskId(),
                "storyboardItemId", target.id(), "videoUrl", video, "commandId", receipt.commandId());
    }

    private Target target(ObjectNode input) {
        return new Target(StoryboardFields.id(input.get("reviewTargetId"), "镜头 ID"),
                StoryboardFields.integer(input.get("expectedVersion"), 0, Integer.MAX_VALUE, "镜头版本"));
    }

    private record Target(long id, int version) {
    }
}
