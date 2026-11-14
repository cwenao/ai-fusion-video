package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.canvas.run.mcp.CanvasMcpSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpProjectToolSchemasTests {
    private final CanvasJson json = new CanvasJson(new ObjectMapper());
    private final McpProjectToolSchemas schemas = new McpProjectToolSchemas(new CanvasMcpSchemas(), json);

    @Test
    void rejectsUnversionedWritesAndClientSuppliedAuthority() {
        var schema = schemas.contract(McpProjectTool.SCRIPT_UPDATE).validator();
        var args = input("""
                {"projectId":"1","kind":"scene","id":"10","requestId":"draft-1","patch":{"sceneDescription":"河岸"}}
                """);
        assertThrows(McpProjectFailure.class, () -> McpProjectToolSchemas.validate(schema, args, "arguments"));
        args.put("expectedVersion", 0);
        assertDoesNotThrow(() -> McpProjectToolSchemas.validate(schema, args, "arguments"));
        args.put("userId", "2");
        assertThrows(McpProjectFailure.class, () -> McpProjectToolSchemas.validate(schema, args, "arguments"));
    }

    @Test
    void selectedResourceSchemaRejectsCrossKindFieldsAndDirectMediaLinks() {
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(CanvasValueType.STORYBOARD_SCENE, true,
                input("""
                        {"content":"不应当作场景创建"}
                        """)));
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(CanvasValueType.SHOT, false,
                input("""
                        {"videoUrl":"https://example.test/raw.mp4"}
                        """)));
        assertDoesNotThrow(() -> schemas.validateFields(CanvasValueType.STORYBOARD_SCENE, true,
                input("""
                        {"sceneHeading":"旧书店","location":"街角"}
                        """)));
    }

    @Test
    void convertsNestedReferenceIdsWithoutJavascriptPrecisionLoss() {
        var args = input("""
                {"dialogues":[{"type":1,"content":"到岸了","character_asset_id":"9007199254740993"}]}
                """);
        schemas.validateFields(CanvasValueType.SCRIPT_SCENE, false, args);
        var stored = McpProjectValues.internalFields(CanvasValueType.SCRIPT_SCENE, args);
        assertEquals(9_007_199_254_740_993L, stored.at("/dialogues/0/character_asset_id").longValue());
        assertEquals(args.get("dialogues"), McpProjectValues.externalField("dialogues", stored.get("dialogues")));
        assertThrows(McpProjectFailure.class, () -> McpProjectValues.id(input("""
                {"id":"9223372036854775808"}
                """), "id"));
    }

    @Test
    void episodeWritesAcceptEitherOriginalTextNameAndKeepSynopsisSeparate() {
        var episode = CanvasValueType.SCRIPT_EPISODE;
        String markdown = "  ## 第一集\n\n> **林：**信到了。\n";
        var fields = input("{\"episodeNumber\":1,\"synopsis\":\"雨夜来信\"}");
        var expected = fields.deepCopy().put("rawContent", markdown);

        var canonical = fields.deepCopy().put("rawContent", markdown);
        var alias = fields.deepCopy().put("originalText", markdown);
        var both = canonical.deepCopy().put("originalText", markdown);
        for (var args : List.of(canonical, alias, both)) {
            schemas.validateFields(episode, true, args);
            assertEquals(expected, McpProjectValues.internalFields(episode, args));
        }

        var clear = input("{\"originalText\":null}");
        schemas.validateFields(episode, false, clear);
        assertEquals(input("{\"rawContent\":null}"), McpProjectValues.internalFields(episode, clear));
        var synopsisOnly = input("{\"synopsis\":\"新的概述\"}");
        schemas.validateFields(episode, false, synopsisOnly);
        assertEquals(synopsisOnly, McpProjectValues.internalFields(episode, synopsisOnly));
        assertThrows(McpProjectFailure.class,
                () -> McpProjectValues.internalFields(episode, both.deepCopy().put("originalText", markdown.strip())));
        assertThrows(McpProjectFailure.class,
                () -> McpProjectValues.internalFields(episode, both.deepCopy().putNull("originalText")));
    }

    private ObjectNode input(String value) {
        return json.read(value, ObjectNode.class);
    }
}
