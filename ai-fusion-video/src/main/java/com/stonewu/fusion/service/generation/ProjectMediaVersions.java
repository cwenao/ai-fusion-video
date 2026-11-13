package com.stonewu.fusion.service.generation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.mapper.resource.ResourceRevisionMapper;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.ProjectMediaCompletion.Binding;
import com.stonewu.fusion.service.generation.review.GenerationContentReview.Target;
import com.stonewu.fusion.service.resource.ResourceMutations;
import com.stonewu.fusion.service.resource.model.ResourcePatchCommand;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
class ProjectMediaVersions {
    private final ResourceRevisionMapper revisions;

    void check(AgentRun run, Binding binding, ResourceSnapshot resource) {
        int current = resource.header().version();
        if (!compatibleFrames(run, binding, current)) {
            ResourceMutations.requireVersion(resource.header(), binding.version());
        }
        if (binding.parentId() != null) {
            int parentVersion = resource.data().path("assetVersion").intValue();
            if (parentVersion != binding.parentVersion() && !compatibleAsset(run, binding, parentVersion)) {
                throw new BusinessException(409, "主资产已被其他操作修改，已生成素材可在创作记录查看");
            }
        }
    }

    ResourcePatchCommand patch(AgentRun run, String requestId, Binding binding, ResourceSnapshot resource,
                               ObjectNode fields) {
        // 若回填事务已提交但恢复事件还未保存，按原参数重放同一命令回执。
        var previous = revisions.generationWritebackRequest(run.getUserId(), requestId);
        if (previous != null) {
            return new ResourcePatchCommand(requestId, binding.resourceType(), binding.id(), previous.version(),
                    fields, previous.parentVersion());
        }
        check(run, binding, resource);
        Integer parentVersion = binding.parentId() == null ? null : resource.data().path("assetVersion").intValue();
        return new ResourcePatchCommand(requestId, binding.resourceType(), binding.id(), resource.header().version(),
                fields, parentVersion);
    }

    private boolean compatibleFrames(AgentRun run, Binding binding, int current) {
        if (current != (long) binding.version() + 1) {
            return false;
        }
        String opposite = switch (binding.target()) {
            case SHOT_FIRST_FRAME -> "last";
            case SHOT_LAST_FRAME -> "first";
            default -> null;
        };
        if (opposite == null) {
            return false;
        }
        var history = revisions.generationChanges(CanvasValueType.SHOT.code(), binding.id(), binding.version(), current);
        return history.size() == 1 && sameActor(run, history.getFirst()) && "storyboard_frame_executor"
                .equals(history.getFirst().agentType()) && opposite.equals(history.getFirst().frameType());
    }

    private boolean compatibleAsset(AgentRun run, Binding binding, int current) {
        int expected = binding.parentVersion();
        if (current <= expected || run.getParentRunId() == null) {
            return false;
        }
        var history = revisions.generationChanges(CanvasValueType.ASSET.code(), binding.parentId(), expected, current);
        return history.size() == current - expected && history.stream().allMatch(change -> sameActor(run, change)
                && "asset_image_executor".equals(change.agentType()) && run.getParentRunId().equals(change.parentRunId()));
    }

    private boolean sameActor(AgentRun run, ResourceRevisionMapper.GenerationChange change) {
        return Objects.equals(run.getUserId(), change.userId()) && Objects.equals(run.getActorTeamId(), change.teamId());
    }
}
