package com.stonewu.fusion.service.ai.tool.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceHeader;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import org.junit.jupiter.api.Test;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.SCRIPT_EPISODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ScriptEpisodeSourceTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResourceQueryService resources = mock(ResourceQueryService.class);
    private final CanvasActor actor = new CanvasActor(42L, null);
    private final ToolExecutionContext context = ToolExecutionContext.forInvocation(42L, 1, 42L,
            ResourceCommandContext.user(actor, null));
    private final GetScriptEpisodeSourceToolExecutor tool = new GetScriptEpisodeSourceToolExecutor(
            new ScriptToolQueries(resources, new CanvasJson(mapper)));

    @Test
    void returnsSavedTextAndVersionWithoutLoadingScenes() throws Exception {
        String source = "外景 站台 夜\n\n  ▲ 雨落在站牌上。\n林：车什么时候来？\n";
        givenSavedSource(source);

        var result = mapper.readTree(tool.execute("""
                {"scriptEpisodeId":26,"expectedVersion":4}
                """, context));

        assertThat(result.path("status").textValue()).isEqualTo("success");
        assertThat(result.path("rawContent").textValue()).isEqualTo(source);
        assertThat(result.path("episode_version").intValue()).isEqualTo(4);
        assertThat(result.has("scenes")).isFalse();
        verify(resources).get(SCRIPT_EPISODE, 26L, actor);
        verifyNoMoreInteractions(resources);
    }

    @Test
    void rejectsSourceChangedAfterConfirmation() throws Exception {
        givenSavedSource("最新原文");

        var result = mapper.readTree(tool.execute("""
                {"scriptEpisodeId":26,"expectedVersion":3}
                """, context));

        assertThat(result.path("status").textValue()).isEqualTo("error");
        assertThat(result.path("errorCode").textValue()).isEqualTo("VERSION_CONFLICT");
        assertThat(result.has("rawContent")).isFalse();
    }

    @Test
    void rejectsBlankSavedSource() throws Exception {
        givenSavedSource(" \n　");

        var result = mapper.readTree(tool.execute("""
                {"scriptEpisodeId":26}
                """, context));

        assertThat(result.path("status").textValue()).isEqualTo("error");
        assertThat(result.path("message").textValue()).contains("保存分集剧本原文");
    }

    private void givenSavedSource(String source) {
        var data = mapper.createObjectNode().put("id", 26L).put("scriptId", 15L).put("version", 4)
                .put("rawContent", source);
        when(resources.get(SCRIPT_EPISODE, 26L, actor)).thenReturn(new ResourceSnapshot(
                new ResourceHeader(SCRIPT_EPISODE, 26L, 5L, 4, false), data));
    }
}
