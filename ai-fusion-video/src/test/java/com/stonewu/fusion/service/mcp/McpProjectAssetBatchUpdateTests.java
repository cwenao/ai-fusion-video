package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.security.McpProjectSecurity;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.run.mcp.CanvasMcpSchemas;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.ProjectGrant;
import com.stonewu.fusion.service.resource.ResourceCommandService;
import com.stonewu.fusion.service.resource.model.ResourceBatchWriteCommand;
import com.stonewu.fusion.service.resource.model.ResourceCommandReceipt;
import com.stonewu.fusion.service.resource.model.ResourcePatchCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpProjectAssetBatchUpdateTests {
    private static final String TOOL = "fusion_asset_batch_update";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanvasMcpSchemas compiler = new CanvasMcpSchemas();
    private final CanvasActor actor = new CanvasActor(8L, null);
    private final Grant grant = new Grant("验收凭证", 11L, actor, McpProjectCredentials.READ_WRITE);
    private final ResourceCommandService commands = mock(ResourceCommandService.class);
    private final McpProjectEndpoint endpoint;

    McpProjectAssetBatchUpdateTests() {
        var json = new CanvasJson(mapper);
        var schemas = new McpProjectToolSchemas(compiler, json);
        var credentials = mock(McpProjectCredentials.class);
        when(credentials.authorizeProject(grant, 11L, true))
                .thenReturn(new ProjectGrant(grant.credentialId(), 11L, actor, grant.scope()));
        var tools = new McpProjectTools(credentials, schemas, mock(McpProjectResources.class),
                mock(McpProjectMedia.class), commands, json);
        endpoint = new McpProjectEndpoint(tools, schemas);
    }

    @AfterEach
    void closeEndpoint() {
        endpoint.close();
    }

    @Test
    void advertisesTheSharedParentVersionAndAppliesItToEveryChild() throws Exception {
        var listed = rpc("tools/list", Map.of()).path("tools");
        var schema = StreamSupport.stream(listed.spliterator(), false)
                .filter(tool -> TOOL.equals(tool.path("name").asText()))
                .findFirst().orElseThrow().path("inputSchema");
        assertThat(schema.at("/properties/expectedParentVersion/type").asText()).isEqualTo("integer");

        var arguments = childUpdates().put("expectedParentVersion", 4);
        compiler.validate(schema, arguments, TOOL);
        assertBatchAccepted(arguments, 4, 4);
    }

    @Test
    void acceptsDifferentParentVersionsOnIndividualTargets() throws Exception {
        var arguments = childUpdates();
        ((ObjectNode) arguments.at("/targets/0")).put("expectedParentVersion", 4);
        ((ObjectNode) arguments.at("/targets/1")).put("expectedParentVersion", 7);
        assertBatchAccepted(arguments, 4, 7);
    }

    @Test
    void parentVersionIsRequiredOnlyForChildUpdatesAndMustUseOneForm() throws Exception {
        var arguments = childUpdates();
        var first = (ObjectNode) arguments.at("/targets/0");
        var second = (ObjectNode) arguments.at("/targets/1");
        first.put("expectedParentVersion", 4);
        assertThat(call(arguments).at("/error/code").asText()).isEqualTo("INVALID_ARGUMENT");

        first.remove("expectedParentVersion");
        arguments.put("expectedParentVersion", 4);
        second.put("expectedParentVersion", 7);
        assertThat(call(arguments).at("/error/code").asText()).isEqualTo("INVALID_ARGUMENT");

        second.remove("expectedParentVersion");
        arguments.put("kind", "asset");
        assertThat(call(arguments).at("/error/code").asText()).isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(commands);

        arguments.remove("expectedParentVersion");
        assertBatchAccepted(arguments, null, null);
    }

    private void assertBatchAccepted(ObjectNode arguments, Integer firstParentVersion, Integer secondParentVersion)
            throws Exception {
        var result = mapper.createObjectNode();
        result.putArray("items");
        when(commands.execute(any(ResourceBatchWriteCommand.class), any()))
                .thenReturn(new ResourceCommandReceipt("batch-command", "update-assets", result, List.of()));

        var response = call(arguments);
        assertThat(response.path("ok").asBoolean()).withFailMessage(response.toString()).isTrue();
        var submitted = ArgumentCaptor.forClass(ResourceBatchWriteCommand.class);
        verify(commands).execute(submitted.capture(), any());
        assertThat(submitted.getValue().entries()).map(ResourcePatchCommand.class::cast)
                .extracting(ResourcePatchCommand::resourceId, ResourcePatchCommand::expectedVersion,
                        ResourcePatchCommand::expectedParentVersion)
                .containsExactly(tuple(41L, 2, firstParentVersion), tuple(42L, 0, secondParentVersion));
    }

    private ObjectNode childUpdates() throws Exception {
        return mapper.readValue("""
                {"projectId":"11","kind":"assetItem","requestId":"update-assets","targets":[
                  {"id":"41","expectedVersion":2,"patch":{"name":"雨夜"}},
                  {"id":"42","expectedVersion":0,"patch":{"name":"清晨"}}
                ]}
                """, ObjectNode.class);
    }

    private JsonNode call(ObjectNode arguments) throws Exception {
        return rpc("tools/call", Map.of("name", TOOL, "arguments", arguments)).path("structuredContent");
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        var request = new MockHttpServletRequest("POST", McpProjectSecurity.ENDPOINT);
        request.setAttribute(McpProjectSecurity.class.getName() + ".grant", grant);
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContentType("application/json; charset=UTF-8");
        request.setContent(mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)));
        var response = new MockHttpServletResponse();
        endpoint.protocol(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        var body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.has("result")).withFailMessage(body.toString()).isTrue();
        return body.path("result");
    }
}
