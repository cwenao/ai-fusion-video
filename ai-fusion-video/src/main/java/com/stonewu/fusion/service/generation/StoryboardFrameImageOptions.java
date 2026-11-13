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
public class StoryboardFrameImageOptions {
    private static final String MODEL = "frameImageModelId";
    private static final String RATIO = "frameImageAspectRatio";
    private static final String RESOLUTION = "frameImageResolution";

    private final AgentRunMapper runs;
    private final AiModelService models;
    private final CanvasJson json;

    public Selection selected(ToolExecutionContext context) {
        var mutation = context.getMutationContext();
        if (mutation == null || context.getCanvasScope() != null) {
            return null;
        }
        var child = runs.selectByRunId(mutation.agentRunId());
        if (child == null || child.getProjectId() == null || child.getParentRunId() == null
                || !"storyboard_frame_executor".equals(child.getAgentType())) {
            return null;
        }
        var parent = runs.selectByRunId(child.getParentRunId());
        if (parent == null || !"storyboard_frame_gen".equals(parent.getAgentType())) {
            return null;
        }
        var variables = json.read(parent.getAgentDefinitionSnapshotJson(), AgentKernelSnapshotPayload.class)
                .promptVariables();
        if (!variables.containsKey(MODEL)) {
            // 旧项目流水线没有生图模型选择，沿用原任务的默认模型规则。
            return null;
        }
        return new Selection(requireModel(models, variables.get(MODEL)), variables.get(RATIO), variables.get(RESOLUTION));
    }

    public static void validateRequest(AiModelService models, Map<String, Object> context) {
        if (context != null && context.containsKey(MODEL)) {
            requireModel(models, context.get(MODEL));
            validateParameter(context, RATIO);
            validateParameter(context, RESOLUTION);
        }
    }

    private static AiModel requireModel(AiModelService models, Object value) {
        Long id;
        try {
            id = Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            throw new BusinessException(400, "请选择有效的首尾帧生图模型");
        }
        var model = models.getById(id);
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())
                || !Integer.valueOf(2).equals(model.getModelType())) {
            throw new BusinessException(400, "所选首尾帧生图模型不存在、已停用或类型不匹配");
        }
        return model;
    }

    private static void validateParameter(Map<String, Object> context, String key) {
        if (context.containsKey(key) && (!(context.get(key) instanceof String value)
                || value.isBlank() || value.length() > 64)) {
            throw new BusinessException(400, "首尾帧比例或分辨率参数无效");
        }
    }

    public record Selection(AiModel model, String aspectRatio, String resolution) {
    }
}
