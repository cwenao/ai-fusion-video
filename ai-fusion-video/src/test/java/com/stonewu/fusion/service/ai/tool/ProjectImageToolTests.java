package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
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
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ProjectMediaCompletion;
import com.stonewu.fusion.service.generation.ProjectImageOptions;
import com.stonewu.fusion.service.generation.job.GenerationSubmissionService;
import com.stonewu.fusion.service.generation.review.GenerationReviewAdmission;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceHeader;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import io.agentscope.core.tool.ToolSuspendException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

class ProjectImageToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanvasJson json = new CanvasJson(mapper);
    private final AiModelService models = mock(AiModelService.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ProjectImageOptions projectImageOptions = new ProjectImageOptions(runs, models, json);
    private final GenerationModelCapabilityService capabilities = mock(GenerationModelCapabilityService.class);
    private final CanvasAgentNodeGeneration canvas = mock(CanvasAgentNodeGeneration.class);
    private final GenerationSubmissionService submissions = mock(GenerationSubmissionService.class);
    private final ProjectMediaCompletion completion = mock(ProjectMediaCompletion.class);
    private final ResourceCommandContext mutation = new ResourceCommandContext(
            new CanvasActor(19L, null), "agent", null, "image-child", "image-call", "worker", 1L);
    private final ToolExecutionContext context = ToolExecutionContext.forInvocation(19L, 1, 19L, mutation);

    @ParameterizedTest
    @CsvSource({"storyboard_frame, frameImage", "asset_image, assetImage"})
    void capabilityLookupAndSubmissionUseParentSelectionEvenIfToolProposesOtherDimensions(String agent, String prefix) {
        var chosen = selection(agent, prefix, 1);
        when(capabilities.buildImageCapabilitySnapshot(chosen))
                .thenReturn(JSONUtil.createObj().set("modelId", 42).set("supportsReferenceImages", false));
        var query = new GetGenerationModelCapabilitiesToolExecutor(models, capabilities, canvas, projectImageOptions);
        var result = JSONUtil.parseObj(query.execute("{\"modelType\":\"image\"}", context));
        assertEquals(42, result.getJSONObject("image").getInt("modelId"));
        when(models.getById(99L)).thenReturn(AiModel.builder().id(99L).modelType(2).status(1).build());
        assertEquals("此模型与本次选择的生图模型不一致",
                JSONUtil.parseObj(query.execute("{\"modelId\":99}", context)).getStr("message"));

        var type = agent.equals("asset_image") ? CanvasValueType.ASSET_ITEM : CanvasValueType.SHOT;
        var resource = mapper.createObjectNode();
        if (type == CanvasValueType.ASSET_ITEM) {
            resource.putObject("properties").put("imagePrompt", "雨夜站台");
        }
        var target = new ResourceSnapshot(new ResourceHeader(type, 75L, 5L, 4, false), resource);
        when(completion.requireTarget(eq(mutation), any(), eq(GenerationKind.IMAGE))).thenReturn(target);
        assertThrows(ToolSuspendException.class, () -> generate().execute("""
                {"prompt":"雨夜站台","width":1024,"height":1024,"reviewTargetId":75,"frameType":"first","assetId":23,"expectedVersion":4,"expectedParentVersion":2}
                """, context));
        var task = ArgumentCaptor.forClass(ImageTask.class);
        verify(submissions).image(task.capture(), any(), eq(mutation), isNull(), isNull());
        assertEquals("雨夜站台", task.getValue().getPrompt());
        assertEquals(42L, task.getValue().getModelId());
        assertEquals("9:16", task.getValue().getAspectRatio());
        assertEquals("720x1280", task.getValue().getResolution());
        assertNull(task.getValue().getWidth());
        assertNull(task.getValue().getHeight());
    }

    @ParameterizedTest
    @CsvSource({"storyboard_frame, frameImage", "asset_image, assetImage"})
    void disabledSelectionFailsWithoutSubmittingOrChoosingAnotherModel(String agent, String prefix) {
        selection(agent, prefix, 0);
        var failure = JSONUtil.parseObj(generate().execute("{\"prompt\":\"雨夜站台\"}", context));
        assertEquals("error", failure.getStr("status"));
        verifyNoInteractions(submissions);
    }

    @Test
    void savedMarkdownIsSubmittedUnchangedWithTheSelectedModelAndReferenceImages() {
        selection("asset_image", "assetImage", 1);
        String saved = """
                ## 主体与结构

                - 墨绿色珐琅保温壶
                - **银色**旋盖
                """;
        var resource = mapper.createObjectNode();
        resource.putObject("properties").put("imagePrompt", saved).put("appearanceDescription", "旧外观");
        resource.put("aiPrompt", "上一次生成的提示词");
        var target = new ResourceSnapshot(new ResourceHeader(CanvasValueType.ASSET_ITEM, 75L, 5L, 4, false),
                resource);
        when(completion.requireTarget(eq(mutation), any(), eq(GenerationKind.IMAGE))).thenReturn(target);
        assertThrows(ToolSuspendException.class, () -> generate().execute("""
                {"prompt":"智能体临时生成的正文","negativePrompt":"额外限制","reviewTargetId":75,
                 "assetId":23,"expectedVersion":4,"expectedParentVersion":2,
                 "imageUrls":["https://example.com/reference.png"]}
                """, context));
        var task = ArgumentCaptor.forClass(ImageTask.class);
        verify(submissions).image(task.capture(), any(), eq(mutation), isNull(), isNull());
        assertEquals(saved, task.getValue().getPrompt());
        assertEquals(42L, task.getValue().getModelId());
        assertEquals(List.of("https://example.com/reference.png"),
                JSONUtil.parseArray(task.getValue().getRefImageUrls()).toList(String.class));
    }

    @Test
    void missingSavedPromptDoesNotSubmitEvenWithAppearanceAndHistoricalPrompt() {
        var resource = mapper.createObjectNode();
        resource.putObject("properties").put("appearanceDescription", "青绿陶瓷提灯");
        resource.put("aiPrompt", "上一次生成的提示词");
        var target = new ResourceSnapshot(new ResourceHeader(CanvasValueType.ASSET_ITEM, 75L, 5L, 4, false),
                resource);
        when(completion.requireTarget(eq(mutation), any(), eq(GenerationKind.IMAGE))).thenReturn(target);
        var response = JSONUtil.parseObj(generate().execute("""
                {"prompt":"青绿陶瓷提灯","reviewTargetId":75,"assetId":23,
                 "expectedVersion":4,"expectedParentVersion":2}
                """, context));
        assertEquals("error", response.getStr("status"));
        assertEquals("请先为当前子资产生成或填写生图提示词并保存", response.getStr("message"));
        verifyNoInteractions(submissions);
    }

    @Test
    void assetRequestRejectsNonImageModelAndOptionsWithoutModelWhileLegacyRequestsStillWork() {
        when(models.getById(99L)).thenReturn(AiModel.builder().id(99L).modelType(3).status(1).build());
        ProjectImageOptions.validateRequest(models, "asset_image_gen", Map.of("selectedAssetIds", List.of(23)));
        assertThrows(BusinessException.class, () -> ProjectImageOptions.validateRequest(models, "asset_image_gen",
                Map.of("assetImageModelId", 99)));
        assertThrows(BusinessException.class, () -> ProjectImageOptions.validateRequest(models, "asset_image_gen",
                Map.of("assetImageResolution", "720x1280")));
    }

    private GenerateImageToolExecutor generate() {
        return new GenerateImageToolExecutor(models, submissions, capabilities, json, canvas,
                mock(GenerationReviewAdmission.class), completion, projectImageOptions);
    }

    private AiModel selection(String agent, String prefix, int status) {
        var chosen = AiModel.builder().id(42L).modelType(2).status(status).build();
        when(models.getById(42L)).thenReturn(chosen);
        var snapshot = new AgentKernelSnapshotPayload(2, agent + "_gen", "项目图片生成", "项目图片",
                "生成项目图片", Map.of(prefix + "ModelId", "42", prefix + "AspectRatio", "9:16",
                prefix + "Resolution", "720x1280"), 5, "9", 1, "test", "test", mapper.createObjectNode(),
                List.of(), "1.4.0");
        var deadline = LocalDateTime.of(2026, 11, 13, 19, 0);
        when(runs.selectByRunId("image-root")).thenReturn(AgentRun.builder().runId("image-root").projectId(5L)
                .agentType(agent + "_gen").agentDefinitionSnapshotJson(json.write(snapshot))
                .deadlineAt(deadline).build());
        when(runs.selectByRunId("image-child")).thenReturn(AgentRun.builder().runId("image-child").projectId(5L)
                .agentType(agent + "_executor").parentRunId("image-root").deadlineAt(deadline).build());
        return chosen;
    }
}
