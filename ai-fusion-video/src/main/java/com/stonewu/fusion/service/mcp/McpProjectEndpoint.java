package com.stonewu.fusion.service.mcp;

import com.stonewu.fusion.security.McpProjectSecurity;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
class McpProjectEndpoint {
    private static final String GRANT_KEY = "projectGrant";
    private final HttpServletStatelessServerTransport transport;
    private final McpStatelessSyncServer server;

    McpProjectEndpoint(McpProjectTools tools, McpProjectToolSchemas schemas) {
        var mapper = McpJsonMapper.getDefault();
        transport = HttpServletStatelessServerTransport.builder().jsonMapper(mapper)
                .messageEndpoint(McpProjectSecurity.ENDPOINT)
                .contextExtractor(request -> McpTransportContext.create(Map.of(GRANT_KEY, McpProjectSecurity.grant(request))))
                .build();
        var specification = McpServer.sync(transport).jsonMapper(mapper).serverInfo("fusion-project-mcp", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build()).requestTimeout(Duration.ofSeconds(30));
        for (var operation : McpProjectTool.values()) {
            var contract = Tool.builder().name(operation.toolName()).description(operation.description)
                    .inputSchema(mapper, schemas.contract(operation).wire())
                    .annotations(new ToolAnnotations(null, !operation.write, operation.canReplaceContent(), true, false, false))
                    .build();
            specification.toolCall(contract,
                    (context, request) -> tools.call(operation, (Grant) context.get(GRANT_KEY), request.arguments()));
        }
        server = specification.build();
    }

    @RequestMapping(McpProjectSecurity.ENDPOINT)
    public void protocol(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        transport.service((ServletRequest) request, response);
    }

    @PreDestroy
    void close() {
        server.close();
    }
}
