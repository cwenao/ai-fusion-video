package com.stonewu.fusion.service.generation;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.run.kernel.AgentKernelSnapshotPayload;
import com.stonewu.fusion.service.canvas.CanvasJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 从父流水线的持久化快照读取用户确认的生图参数。 */
@Component
@RequiredArgsConstructor
public class ProjectImageOptions {
    private static final Fields FRAME = new Fields("frameImageModelId", "frameImageAspectRatio", "frameImageResolution");
    private static final Fields ASSET = new Fields("assetImageModelId", "assetImageAspectRatio", "assetImageResolution");

    private final AgentRunMapper runs;
    private final AiModelService models;
    private final CanvasJson json;

    public Selection selected(ToolExecutionContext context) {
        var mutation = context.getMutationContext();
        if (mutation == null || context.getCanvasScope() != null) {
            return null;
        }
        var child = runs.selectByRunId(mutation.agentRunId());
        if (child == null || child.getProjectId() == null || child.getParentRunId() == null) {
            return null;
        }
        var pipeline = ProjectMediaPipeline.forExecutor(child.getAgentType());
        if (pipeline != ProjectMediaPipeline.FRAME && pipeline != ProjectMediaPipeline.ASSET) {
            return null;
        }
        var parent = runs.selectByRunId(child.getParentRunId());
        if (parent == null || !pipeline.root().equals(parent.getAgentType())) {
            return null;
        }
        var fields = fields(pipeline.root());
        var variables = json.read(parent.getAgentDefinitionSnapshotJson(), AgentKernelSnapshotPayload.class)
                .promptVariables();
        if (!variables.containsKey(fields.model())) {
            // 兼容未携带生图选择的既有调用及持久化任务。
            return null;
        }
        return new Selection(requireModel(models, variables.get(fields.model())),
                variables.get(fields.ratio()), variables.get(fields.resolution()), pipeline);
    }

    public static void validateRequest(AiModelService models, String agentType, Map<String, Object> context) {
        var fields = fields(agentType);
        if (fields == null || context == null) {
            return;
        }
        if (context.containsKey(fields.model()) || context.containsKey(fields.ratio())
                || context.containsKey(fields.resolution())) {
            requireModel(models, context.get(fields.model()));
            validateParameter(context, fields.ratio());
            validateParameter(context, fields.resolution());
        }
    }

    private static Fields fields(String agentType) {
        if (ProjectMediaPipeline.FRAME.root().equals(agentType)) {
            return FRAME;
        }
        if (ProjectMediaPipeline.ASSET.root().equals(agentType)) {
            return ASSET;
        }
        return null;
    }

    private static AiModel requireModel(AiModelService models, Object value) {
        Long id;
        try {
            id = Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            throw new BusinessException(400, "请选择有效的生图模型");
        }
        var model = models.getById(id);
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())
                || !Integer.valueOf(2).equals(model.getModelType())) {
            throw new BusinessException(400, "所选生图模型不存在、已停用或类型不匹配");
        }
        return model;
    }

    private static void validateParameter(Map<String, Object> context, String key) {
        if (context.containsKey(key) && (!(context.get(key) instanceof String value)
                || value.isBlank() || value.length() > 64)) {
            throw new BusinessException(400, "画面比例或分辨率参数无效");
        }
    }

    private record Fields(String model, String ratio, String resolution) {
    }

    public record Selection(AiModel model, String aspectRatio, String resolution, ProjectMediaPipeline pipeline) {
    }
}
