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
        var stored = McpProjectValues.internalFields(args);
        assertEquals(9_007_199_254_740_993L, stored.at("/dialogues/0/character_asset_id").longValue());
        assertEquals(args.get("dialogues"), McpProjectValues.externalField("dialogues", stored.get("dialogues")));
        assertThrows(McpProjectFailure.class, () -> McpProjectValues.id(input("""
                {"id":"9223372036854775808"}
                """), "id"));
    }

    @Test
    void episodeWritesAcceptOnlyRawContentForSourceAndKeepSynopsisSeparate() {
        var episode = CanvasValueType.SCRIPT_EPISODE;
        String markdown = "  ## 第一集\n\n> **林：**信到了。\n";
        var fields = input("{\"episodeNumber\":1,\"synopsis\":\"雨夜来信\"}").put("rawContent", markdown);
        schemas.validateFields(episode, true, fields);
        var stored = McpProjectValues.internalFields(fields);
        assertEquals(markdown, stored.path("rawContent").textValue());
        assertEquals("雨夜来信", stored.path("synopsis").textValue());

        var clear = input("{\"rawContent\":null}");
        schemas.validateFields(episode, false, clear);
        assertEquals(clear, McpProjectValues.internalFields(clear));
        var synopsisOnly = input("{\"synopsis\":\"新的概述\"}");
        schemas.validateFields(episode, false, synopsisOnly);
        assertEquals(synopsisOnly, McpProjectValues.internalFields(synopsisOnly));

        var removedField = input("{\"originalText\":\"已移除的字段\"}");
        assertThrows(McpProjectFailure.class,
                () -> schemas.validateFields(episode, true, removedField.deepCopy().put("episodeNumber", 1)));
        assertThrows(McpProjectFailure.class,
                () -> schemas.validateFields(episode, false, removedField));
    }

    @Test
    void storyboardAcceptsPromptsAndCustomFieldsWithoutGivingMediaUrlsAWritePath() {
        var shot = CanvasValueType.SHOT;
        var fields = input("""
                {"content":"推镜","videoPrompt":"雨中前行","sortOrder":0,"status":1,
                 "customData":{"assetId":"内部工作标签"}}
                """);
        schemas.validateFields(shot, true, fields);
        assertEquals(fields.get("customData"), McpProjectValues.internalFields(fields).get("customData"));
        assertTrue(McpProjectValues.references(List.of(McpProjectValues.internalFields(fields))).isEmpty());
        schemas.validateFields(shot, false, input("""
                {"customDataChanges":{"灯光":"侧逆光","空字段":null},"videoPrompt":null}
                """));
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(shot, false,
                input("{\"customDataChanges\":{\"videoPosterUrl\":null}}")));
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(shot, false, input("{\"sortOrder\":1}")));
    }

    @Test
    void assetItemEditsCanonicalAppearanceAndPromptWithoutOverwritingMediaProperties() {
        var type = CanvasValueType.ASSET_ITEM;
        schemas.validateFields(type, true, input("""
                {"itemType":"variant","properties":{"appearanceDescription":"深蓝雨衣","imagePrompt":"写实质感"}}
                """));
        schemas.validateFields(type, false, input("""
                {"propertyChanges":{"appearanceDescription":"灰色雨衣","imagePrompt":null}}
                """));
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(type, false,
                input("{\"propertyChanges\":{\"audioUrl\":null}}")));
        assertThrows(McpProjectFailure.class, () -> schemas.validateFields(type, false,
                input("{\"propertyChanges\":{\"imagePrompt\":\"  \"}}")));
    }

    @Test
    void assetCreateSchemaAdvertisesSeveralItemsWithoutAllowingNestedMediaOrClientIds() {
        var args = input("""
                {"projectId":"4","kind":"asset","requestId":"sync-character","fields":{
                    "type":"character","name":"阿林","items":[
                        {"name":"少年","properties":{"timeline":"十年前","appearanceState":"校服"}},
                        {"name":"归来","properties":{"timeline":"主线第 8 集","appearanceState":"伤愈"}}]}}
                """);
        var contract = schemas.contract(McpProjectTool.ASSET_CREATE);
        McpProjectToolSchemas.validate(contract.validator(), args, "arguments");
        var item = (ObjectNode) args.at("/fields/items/1");
        item.put("imageUrl", "https://example.test/image.png");
        assertThrows(McpProjectFailure.class, () -> McpProjectToolSchemas.validate(contract.validator(), args, "arguments"));
        item.remove("imageUrl");
        item.put("id", "5");
        assertThrows(McpProjectFailure.class, () -> McpProjectToolSchemas.validate(contract.validator(), args, "arguments"));
    }

    @Test
    void assetItemLabelsCanBeClearedOnlyThroughPropertyChanges() {
        var type = CanvasValueType.ASSET_ITEM;
        schemas.validateFields(type, false, input("""
                {"propertyChanges":{"timeline":null,"appearanceState":"入夜"}}
                """));
        for (String invalid : List.of("null", "5", "\"\"", "\"" + "夜".repeat(81) + "\"")) {
            assertThrows(McpProjectFailure.class, () -> schemas.validateFields(type, true,
                    input("{\"properties\":{\"timeline\":" + invalid + "}}")));
        }
    }

    private ObjectNode input(String value) {
        return json.read(value, ObjectNode.class);
    }
}
