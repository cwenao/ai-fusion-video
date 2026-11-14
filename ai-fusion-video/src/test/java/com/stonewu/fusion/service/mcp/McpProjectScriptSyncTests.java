package com.stonewu.fusion.service.mcp;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.mapper.project.McpProjectCredentialMapper;
import com.stonewu.fusion.repository.resource.AssetReadRepository;
import com.stonewu.fusion.repository.resource.ResourceReadRepository;
import com.stonewu.fusion.repository.resource.StoryboardReadRepository;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.canvas.run.mcp.CanvasMcpSchemas;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import com.stonewu.fusion.service.resource.ResourceCommandService;
import com.stonewu.fusion.service.resource.ResourceQueryService;
import com.stonewu.fusion.service.resource.model.ResourceHeader;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.SCRIPT;
import static com.stonewu.fusion.service.canvas.model.CanvasValueType.SCRIPT_EPISODE;
import static com.stonewu.fusion.service.mcp.McpProjectTool.SCRIPT_GET;
import static com.stonewu.fusion.service.mcp.McpProjectTool.SCRIPT_LIST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpProjectScriptSyncTests {
    private static final String RAW_CONTENT = "  第一集\n人物：我会回来。\r\n\n";
    private static final String SYNOPSIS = "主角在雨夜收到一封信。";

    private final CanvasJson json = new CanvasJson(new ObjectMapper());
    private final CanvasActor actor = new CanvasActor(8L, null);
    private final Grant grant = new Grant("只读凭证", 11L, actor, McpProjectCredentials.READ);
    private final ResourceReadRepository repository = mock(ResourceReadRepository.class);
    private final ResourceQueryService queries = mock(ResourceQueryService.class);
    private final McpProjectToolSchemas schemas = new McpProjectToolSchemas(new CanvasMcpSchemas(), json);
    private final McpProjectMedia media = mock(McpProjectMedia.class);
    private final McpProjectResources resources = new McpProjectResources(repository, mock(StoryboardReadRepository.class),
            mock(AssetReadRepository.class), queries, schemas, media, json);
    private final McpProjectCredentials credentials = new McpProjectCredentials(mock(McpProjectCredentialMapper.class),
            mock(CanvasAccessPolicy.class));
    private final McpProjectTools tools = new McpProjectTools(credentials, schemas, resources, media,
            mock(ResourceCommandService.class), json);

    @Test
    void syncsEachEpisodesOriginalTextWithItsPageAndCanOmitItForDirectoryReads() {
        when(repository.pageScriptChildren(SCRIPT_EPISODE, 21L, 1, 1))
                .thenReturn(new Page<Long>(1, 1, 2).setRecords(List.of(31L)));
        when(repository.pageScriptChildren(SCRIPT_EPISODE, 21L, 2, 1))
                .thenReturn(new Page<Long>(2, 1, 2).setRecords(List.of(32L)));
        script();
        episode(31L, 1, SYNOPSIS, RAW_CONTENT);
        episode(32L, 2, "第二集概述", null);

        var first = call(SCRIPT_LIST, Map.of("kind", "episode", "parentId", "21", "pageSize", 1));
        assertThat(first.at("/data/hasMore").booleanValue()).isTrue();
        assertThat(first.at("/data/items/0/fields/episodeNumber").intValue()).isEqualTo(1);
        assertThat(first.at("/data/items/0/fields/rawContent").textValue()).isEqualTo(SYNOPSIS);
        assertThat(first.at("/data/items/0/fields/synopsis").textValue()).isEqualTo(SYNOPSIS);
        assertThat(first.at("/data/items/0/fields/originalText").textValue()).isEqualTo(RAW_CONTENT);

        var second = call(SCRIPT_LIST, Map.of("kind", "episode", "parentId", "21", "pageSize", 1, "page", 2));
        assertThat(second.at("/data/hasMore").booleanValue()).isFalse();
        assertThat(second.at("/data/items/0/fields/episodeNumber").intValue()).isEqualTo(2);
        assertThat(second.at("/data/items/0/fields/rawContent").textValue()).isEqualTo("第二集概述");
        assertThat(second.at("/data/items/0/fields/originalText").isNull()).isTrue();

        var directory = call(SCRIPT_LIST, Map.of("kind", "episode", "parentId", "21", "pageSize", 1,
                "includeRawContent", false));
        assertThat(directory.at("/data/items/0/fields/rawContent").textValue()).isEqualTo(SYNOPSIS);
        assertThat(directory.at("/data/items/0/fields/originalText").isMissingNode()).isTrue();
    }

    @Test
    void episodeDetailIncludesOriginalTextByDefaultAndScriptSourceRequiresOptIn() {
        script();
        episode(31L, 1, null, RAW_CONTENT);

        var episode = call(SCRIPT_GET, Map.of("kind", "episode", "id", "31"));
        assertThat(episode.at("/data/fields/rawContent").isNull()).isTrue();
        assertThat(episode.at("/data/fields/originalText").textValue()).isEqualTo(RAW_CONTENT);
        var withoutSource = call(SCRIPT_GET, Map.of("kind", "episode", "id", "31", "includeRawContent", false));
        assertThat(withoutSource.at("/data/fields/rawContent").isNull()).isTrue();
        assertThat(withoutSource.at("/data/fields/originalText").isMissingNode()).isTrue();

        var script = call(SCRIPT_GET, Map.of("kind", "script", "id", "21"));
        assertThat(script.at("/data/fields/rawContent").isMissingNode()).isTrue();
        var withSource = call(SCRIPT_GET, Map.of("kind", "script", "id", "21", "includeRawContent", true));
        assertThat(withSource.at("/data/fields/rawContent").textValue()).isEqualTo("剧本主档原文");
    }

    @Test
    void episodeSyncRejectsAnotherProjectsScriptAndSourceOptionOnSceneLists() {
        when(repository.find(SCRIPT, 99L, false, false))
                .thenReturn(Optional.of(new ResourceHeader(SCRIPT, 99L, 12L, 0, false)));

        var foreign = call(SCRIPT_LIST, Map.of("kind", "episode", "parentId", "99"));
        assertThat(foreign.at("/error/code").textValue()).isEqualTo("RESOURCE_NOT_FOUND");
        var scene = call(SCRIPT_LIST, Map.of("kind", "scene", "parentId", "31", "includeRawContent", true));
        assertThat(scene.at("/error/code").textValue()).isEqualTo("INVALID_ARGUMENT");
    }

    private ObjectNode call(McpProjectTool tool, Map<String, Object> arguments) {
        var args = new HashMap<>(arguments);
        args.put("projectId", "11");
        return json.object(tools.call(tool, grant, args).structuredContent());
    }

    private void script() {
        resource(SCRIPT, 21L, json.object(Map.of("id", 21L, "rawContent", "剧本主档原文", "version", 4)));
    }

    private void episode(long id, int number, String synopsis, String rawContent) {
        resource(SCRIPT_EPISODE, id, json.object(Map.of("id", id, "scriptId", 21L, "version", 4,
                "episodeNumber", number)).put("synopsis", synopsis).put("rawContent", rawContent));
    }

    private void resource(CanvasValueType type, long id, ObjectNode data) {
        var header = new ResourceHeader(type, id, 11L, 4, false);
        when(repository.find(type, id, false, false)).thenReturn(Optional.of(header));
        when(queries.get(type, id, actor)).thenReturn(new ResourceSnapshot(header, data));
    }
}
