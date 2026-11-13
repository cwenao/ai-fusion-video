package com.stonewu.fusion.service.generation.review;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.AgentRun;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceHeader;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;
import static com.stonewu.fusion.service.generation.review.GenerationContentReview.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationReviewAdmissionTests {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 13, 10, 0);
    private final CanvasActor actor = new CanvasActor(9, null);
    private final CanvasJson json = new CanvasJson(new ObjectMapper());
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final GenerationReviewModels models = mock(GenerationReviewModels.class);
    private final ResourceQueryService resources = mock(ResourceQueryService.class);
    private final GenerationReviewAdmission admission = new GenerationReviewAdmission(runs, mock(CanvasAccessPolicy.class),
            resources, models, json);

    @BeforeAll
    static void mapAgentRun() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentRun.class);
    }

    @Test
    void aToolCannotEnableReviewAndAPromptOnlyRequestCannotScheduleOne() {
        var request = new AiChatReqVO().setAgentType("storyboard_video_gen").setProjectId(4L)
                .setContext(Map.of("storyboardId", 7, "promptOnly", true, "selectedStoryboardItemIds", List.of(11)));
        assertThat(admission.pipeline(request, actor)).isNull();
        when(runs.selectOne(any())).thenReturn(AgentRun.builder().deadlineAt(DEADLINE).build());
        assertThat(admission.agent(agent(), GenerationKind.VIDEO, IntNode.valueOf(11))).isNull();
        request.setGenerationReviewModelId(80L);
        assertThatThrownBy(() -> admission.pipeline(request, actor)).hasMessageContaining("仅生成提示词");
        verifyNoInteractions(models, resources);
    }

    @Test
    void projectVideoReviewFreezesTheSelectedShotAndActualGenerationPrompt() {
        when(models.freeze(80, GenerationKind.VIDEO)).thenReturn(new Model(80, "视频审查模型", "fingerprint"));
        resource(STORYBOARD, 7, 4, Map.of("id", 7));
        var shot = resource(SHOT, 11, 4, Map.of("storyboardId", 7, "storyboardSceneId", 3,
                "content", "蓝车从左向右驶过", "cameraMovement", "横向跟拍", "dialogue", "开场"));
        resource(STORYBOARD_SCENE, 3, 4, Map.of("sceneHeading", "夜外·街角"));
        var request = new AiChatReqVO().setAgentType("storyboard_video_gen").setProjectId(4L)
                .setGenerationReviewModelId(80L)
                .setContext(Map.of("storyboardId", 7, "selectedStoryboardItemIds", List.of(11)));
        String plan = admission.pipeline(request, actor);
        when(runs.selectOne(any())).thenReturn(AgentRun.builder().projectId(4L)
                .agentType("storyboard_video_executor").generationReviewJson(plan).deadlineAt(DEADLINE).build());
        assertThatThrownBy(() -> admission.agent(agent(), GenerationKind.VIDEO, IntNode.valueOf(12)))
                .hasMessageContaining("用户本次选中");
        var selection = admission.agent(agent(), GenerationKind.VIDEO, IntNode.valueOf(11));
        var criteria = admission.freeze(selection, GenerationKind.VIDEO, "蓝色车，4 秒，跟拍", true, 4L, actor);
        shot.data().put("content", "后续编辑的内容");
        assertThat(criteria.target()).isEqualTo(Target.SHOT_VIDEO);
        assertThat(criteria.targetId()).isEqualTo(11);
        assertThat(criteria.prompt()).isEqualTo("蓝色车，4 秒，跟拍");
        assertThat(criteria.context().at("/镜头/content").asText()).isEqualTo("蓝车从左向右驶过");
        assertThat(criteria.context().at("/场次/sceneHeading").asText()).isEqualTo("夜外·街角");
        assertThat(criteria.hasReferences()).isTrue();
    }

    private ResourceSnapshot resource(CanvasValueType type, long id, long projectId, Map<String, Object> fields) {
        var value = new ResourceSnapshot(new ResourceHeader(type, id, projectId, 0, false), json.object(fields));
        when(resources.get(type, id, actor)).thenReturn(value);
        return value;
    }

    private ResourceCommandContext agent() {
        return new ResourceCommandContext(actor, "agent", null, "review-run", "tool-call", "runner", 1L);
    }
}
