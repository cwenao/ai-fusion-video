package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.run.kernel.AgentKernelSnapshotPayload;
import com.stonewu.fusion.service.ai.tool.generation.GenerateImageToolExecutor;
import com.stonewu.fusion.service.ai.tool.generation.GetGenerationModelCapabilitiesToolExecutor;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.agent.node.CanvasAgentNodeGeneration;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ProjectMediaCompletion;
import com.stonewu.fusion.service.generation.StoryboardFrameImageOptions;
import com.stonewu.fusion.service.generation.job.GenerationSubmissionService;
import com.stonewu.fusion.service.generation.review.GenerationReviewAdmission;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import io.agentscope.core.tool.ToolSuspendException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryboardFrameImageToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanvasJson json = new CanvasJson(mapper);
    private final AiModelService models = mock(AiModelService.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final StoryboardFrameImageOptions frameOptions = new StoryboardFrameImageOptions(runs, models, json);
    private final GenerationModelCapabilityService capabilities = mock(GenerationModelCapabilityService.class);
    private final CanvasAgentNodeGeneration canvas = mock(CanvasAgentNodeGeneration.class);
    private final GenerationSubmissionService submissions = mock(GenerationSubmissionService.class);
    private final ProjectMediaCompletion completion = mock(ProjectMediaCompletion.class);
    private final ResourceCommandContext mutation = new ResourceCommandContext(
            new CanvasActor(19L, null), "agent", null, "frame-child", "image-call", "worker", 1L);
    private final ToolExecutionContext context = ToolExecutionContext.forInvocation(19L, 1, 19L, mutation);

    @Test
    void capabilityLookupAndSubmissionUseParentSelectionEvenIfToolProposesOtherDimensions() {
        var chosen = selection(1);
        when(capabilities.buildImageCapabilitySnapshot(chosen))
                .thenReturn(JSONUtil.createObj().set("modelId", 42).set("supportsReferenceImages", false));
        var query = new GetGenerationModelCapabilitiesToolExecutor(models, capabilities, canvas, frameOptions);
        var result = JSONUtil.parseObj(query.execute("{\"modelType\":\"image\"}", context));
        assertEquals(42, result.getJSONObject("image").getInt("modelId"));
        when(models.getById(99L)).thenReturn(AiModel.builder().id(99L).modelType(2).status(1).build());
        assertEquals("此模型与本次首尾帧选择的生图模型不一致",
                JSONUtil.parseObj(query.execute("{\"modelId\":99}", context)).getStr("message"));

        when(completion.requireTarget(eq(mutation), any(), eq(GenerationKind.IMAGE))).thenReturn(true);
        assertThrows(ToolSuspendException.class, () -> generate().execute("""
                {"prompt":"雨夜站台","width":1024,"height":1024,"reviewTargetId":75,"frameType":"first","expectedVersion":4}
                """, context));
        var task = ArgumentCaptor.forClass(ImageTask.class);
        verify(submissions).image(task.capture(), any(), eq(mutation), isNull(), isNull());
        assertEquals(42L, task.getValue().getModelId());
        assertEquals("9:16", task.getValue().getAspectRatio());
        assertEquals("720x1280", task.getValue().getResolution());
        assertNull(task.getValue().getWidth());
        assertNull(task.getValue().getHeight());
    }

    @Test
    void disabledSelectionFailsWithoutSubmittingOrChoosingAnotherModel() {
        selection(0);
        var failure = JSONUtil.parseObj(generate().execute("{\"prompt\":\"雨夜站台\"}", context));
        assertEquals("error", failure.getStr("status"));
        verifyNoInteractions(submissions);
    }

    private GenerateImageToolExecutor generate() {
        return new GenerateImageToolExecutor(models, submissions, capabilities, json, canvas,
                mock(GenerationReviewAdmission.class), completion, frameOptions);
    }

    private AiModel selection(int status) {
        var chosen = AiModel.builder().id(42L).modelType(2).status(status).build();
        when(models.getById(42L)).thenReturn(chosen);
        var snapshot = new AgentKernelSnapshotPayload(2, "storyboard_frame_gen", "分镜首尾帧生成", "分镜首尾帧",
                "生成分镜首尾帧", Map.of("frameImageModelId", "42", "frameImageAspectRatio", "9:16",
                "frameImageResolution", "720x1280"), 5, "9", 1, "test", "test", mapper.createObjectNode(),
                List.of(), "1.4.0");
        var deadline = LocalDateTime.of(2026, 11, 13, 19, 0);
        when(runs.selectByRunId("frame-root")).thenReturn(AgentRun.builder().runId("frame-root").projectId(5L)
                .agentType("storyboard_frame_gen").agentDefinitionSnapshotJson(json.write(snapshot))
                .deadlineAt(deadline).build());
        when(runs.selectByRunId("frame-child")).thenReturn(AgentRun.builder().runId("frame-child").projectId(5L)
                .agentType("storyboard_frame_executor").parentRunId("frame-root").deadlineAt(deadline).build());
        return chosen;
    }
}
