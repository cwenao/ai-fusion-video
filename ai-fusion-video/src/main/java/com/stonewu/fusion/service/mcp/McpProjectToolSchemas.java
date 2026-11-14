package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.canvas.run.mcp.CanvasMcpSchemas;
import com.networknt.schema.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;

@Component
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
class McpProjectToolSchemas {
    static final String REQUEST_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";
    static final String MEDIA_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final Map<String, Object> ID = pattern("^[1-9][0-9]{0,18}$");
    private static final Map<String, Object> MEDIA_ID = pattern(MEDIA_PATTERN);
    private static final Map<String, Object> VERSION = integer(0, Integer.MAX_VALUE);
    private static final Map<String, Object> TEXT = Map.of("type", "string");
    private static final Map<String, Object> BOOLEAN = Map.of("type", "boolean");
    private static final Map<String, Object> ASSET_TYPE = choice("character", "scene", "prop", "vehicle",
            "building", "costume", "effect", "material", "image");
    private static final Map<String, Object> TARGET = choice("shot", "asset", "assetItem");
    private static final Map<String, Object> PROMPT = choice("firstFramePrompt", "lastFramePrompt", "videoPrompt",
            "aiPrompt");
    private static final Map<String, Object> SLOTS = choice("cover", "image", "thumbnail", "video", "audio",
            "referenceImage", "firstFrame", "lastFrame");

    private final Map<McpProjectTool, Contract> tools = new EnumMap<>(McpProjectTool.class);
    private final Map<CanvasValueType, Contract> create = new EnumMap<>(CanvasValueType.class);
    private final Map<CanvasValueType, Contract> patch = new EnumMap<>(CanvasValueType.class);
    private final Map<CanvasValueType, Set<String>> readFields = new EnumMap<>(CanvasValueType.class);
    private final CanvasMcpSchemas compiler;
    private final CanvasJson json;

    record Contract(Map<String, Object> definition, String wire, Schema validator) {
    }

    McpProjectToolSchemas(CanvasMcpSchemas compiler, CanvasJson json) {
        this.compiler = compiler;
        this.json = json;
        fields();
        for (var tool : McpProjectTool.values()) {
            tools.put(tool, compile(tool(tool)));
        }
    }

    Contract contract(McpProjectTool tool) {
        return tools.get(tool);
    }

    Set<String> readFields(CanvasValueType type) {
        return readFields.get(type);
    }

    void validateFields(CanvasValueType type, boolean creating, JsonNode value) {
        validate((creating ? create : patch).get(type).validator(), value, creating ? "fields" : "patch");
    }

    static void validate(Schema schema, JsonNode value, String field) {
        var errors = schema.validate(value);
        if (!errors.isEmpty()) {
            var first = errors.getFirst();
            throw McpProjectFailure.invalid(field, first.getMessage());
        }
    }

    private Contract compile(Map<String, Object> definition) {
        return new Contract(definition, json.write(definition), compiler.compile(json.object(definition)));
    }

    private void fields() {
        var dialogue = object(properties(
                "type", integer(1, 5), "content", TEXT, "character_name", nullable(TEXT),
                "parenthetical", nullable(TEXT), "character_asset_id", nullable(ID),
                "sortOrder", integer(0, 1_000_000)), "type", "content");
        var character = object(properties("name", nonBlank(), "description", nullable(TEXT),
                "importance", nullable(TEXT), "assetId", nullable(ID)), "name");
        var script = textFields("content", "storySynopsis", "summary", "genre", "targetAudience");
        script.put("durationEstimate", nullable(integer(0, 1_000_000)));
        script.put("charactersJson", nullable(array(character, 0, 10_000)));
        addFields(SCRIPT, script, false);

        var episode = textFields("title");
        episode.put("synopsis", described(nullable(TEXT), "本集剧情概述"));
        episode.put("rawContent", described(nullable(TEXT), "本集剧本原文，支持纯文本和 Markdown，保留空白和换行"));
        episode.put("episodeNumber", integer(1, 1_000_000));
        episode.put("durationEstimate", nullable(integer(0, 1_000_000)));
        addFields(SCRIPT_EPISODE, episode, true, "episodeNumber");
        var scene = sceneFields();
        scene.putAll(properties("sceneDescription", nullable(TEXT), "characters", nullable(array(TEXT, 0, 10_000)),
                "dialogues", nullable(array(dialogue, 0, 10_000)), "characterAssetIds", nullable(array(ID, 0, 10_000)),
                "sceneAssetId", nullable(ID), "propAssetIds", nullable(array(ID, 0, 10_000))));
        addFields(SCRIPT_SCENE, scene, true, "sceneHeading");

        addFields(STORYBOARD, textFields("description"), false);
        var boardEpisode = textFields("title", "synopsis");
        boardEpisode.putAll(properties("episodeNumber", integer(1, 1_000_000), "scriptEpisodeId", nullable(ID)));
        addFields(STORYBOARD_EPISODE, boardEpisode, true, "episodeNumber");
        addFields(STORYBOARD_SCENE, sceneFields(), true, "sceneHeading");
        var shot = textFields("content", "shotType", "shotNumber", "sceneExpectation", "dialogue", "sound",
                "soundEffect", "music", "cameraMovement", "cameraAngle", "cameraEquipment", "focalLength",
                "transition", "remark");
        shot.putAll(properties("duration", nullable(Map.of("type", "number", "minimum", 0, "maximum", 86400)),
                "characterIds", array(ID, 0, 500), "sceneAssetItemId", nullable(ID), "propIds", array(ID, 0, 500)));
        addFields(SHOT, shot, true, "content");

        var asset = properties("name", nonBlank(), "description", nullable(TEXT),
                "tags", nullable(array(Map.of("type", "string", "maxLength", 128), 0, 100)));
        patch.put(ASSET, compile(patchObject(asset)));
        asset.put("type", ASSET_TYPE);
        create.put(ASSET, compile(object(asset, "type", "name")));
        readFields.put(ASSET, Set.copyOf(asset.keySet()));
        addFields(ASSET_ITEM, properties("name", nullable(TEXT), "itemType", pattern("^[a-z][a-z0-9_]{0,31}$")),
                true);
    }

    private Map<String, Object> sceneFields() {
        return textFields("sceneHeading", "sceneNumber", "location", "timeOfDay", "intExt");
    }

    private void addFields(CanvasValueType type, Map<String, Object> fields, boolean creatable, String... required) {
        readFields.put(type, Set.copyOf(fields.keySet()));
        patch.put(type, compile(patchObject(fields)));
        if (creatable) {
            var input = new LinkedHashMap<>(fields);
            for (String key : required) {
                if (key.equals("sceneHeading") || key.equals("content")) {
                    input.put(key, nonBlank());
                }
            }
            create.put(type, compile(object(input, required)));
        }
    }

    private Map<String, Object> tool(McpProjectTool tool) {
        Map<String, Object> params = properties("projectId", ID);
        var required = new ArrayList<>(List.of("projectId"));
        if (tool.write) {
            params.put("requestId", pattern(REQUEST_PATTERN));
            required.add("requestId");
        }
        switch (tool) {
            case WORKSPACE_GET -> {
            }
            case WORKSPACE_INITIALIZE -> required(params, required, "expectedVersion", VERSION);
            case SCRIPT_LIST -> {
                list(params, required, choice("episode", "scene"));
                required(params, required, "parentId", ID);
                params.put("includeRawContent", described(BOOLEAN, "是否返回分集剧本原文 rawContent，默认 true"));
            }
            case SCRIPT_GET -> {
                get(params, required, choice("script", "episode", "scene"));
                params.put("includeRawContent", described(BOOLEAN,
                        "是否返回剧本原文 rawContent。分集默认返回，主档默认省略"));
            }
            case SCRIPT_CREATE -> create(params, required, choice("episode", "scene"), true,
                    forms(create, SCRIPT_EPISODE, SCRIPT_SCENE));
            case SCRIPT_UPDATE -> update(params, required, choice("script", "episode", "scene"),
                    forms(patch, SCRIPT, SCRIPT_EPISODE, SCRIPT_SCENE));
            case STORYBOARD_LIST -> {
                list(params, required, choice("episode", "scene", "shot"));
                required(params, required, "parentKind", choice("storyboard", "episode", "scene"));
                required(params, required, "parentId", ID);
            }
            case STORYBOARD_GET -> get(params, required, choice("storyboard", "episode", "scene", "shot"));
            case STORYBOARD_CREATE -> create(params, required, choice("episode", "scene", "shot"), true,
                    forms(create, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_UPDATE -> update(params, required, choice("storyboard", "episode", "scene", "shot"),
                    forms(patch, STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_APPEND_SHOTS -> {
                required(params, required, "sceneId", ID);
                required(params, required, "expectedParentVersion", VERSION);
                required(params, required, "shots", array(create.get(SHOT).definition(), 1, 20));
            }
            case PROMPT_GET, PROMPT_SET -> {
                target(params, required);
                required(params, required, "slot", PROMPT);
                if (tool.write) {
                    required(params, required, "text", nullable(TEXT));
                }
            }
            case ASSET_LIST -> {
                list(params, required, choice("asset", "assetItem"));
                params.putAll(properties("parentId", ID, "assetType", ASSET_TYPE, "keyword",
                        Map.of("type", "string", "maxLength", 200)));
            }
            case ASSET_GET -> get(params, required, choice("asset", "assetItem"));
            case ASSET_CREATE -> create(params, required, choice("asset", "assetItem"), false,
                    forms(create, ASSET, ASSET_ITEM));
            case ASSET_UPDATE -> {
                update(params, required, choice("asset", "assetItem"), forms(patch, ASSET, ASSET_ITEM));
                params.put("expectedParentVersion", VERSION);
            }
            case MEDIA_GET -> required(params, required, "mediaResourceId", MEDIA_ID);
            case MEDIA_BIND -> {
                target(params, required);
                var binding = object(properties("slot", SLOTS, "mediaResourceId", MEDIA_ID), "slot",
                        "mediaResourceId");
                required(params, required, "bindings", array(binding, 1, 5));
            }
        }
        if (tool == McpProjectTool.PROMPT_SET || tool == McpProjectTool.MEDIA_BIND) {
            required(params, required, "expectedVersion", VERSION);
            params.put("expectedParentVersion", VERSION);
        }
        return object(params, required.toArray(String[]::new));
    }

    private static void list(Map<String, Object> params, List<String> required, Map<String, Object> kind) {
        required(params, required, "kind", kind);
        params.putAll(properties("page", integer(1, Integer.MAX_VALUE), "pageSize", integer(1, 50)));
    }

    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        var result = new LinkedHashMap<>(schema);
        result.put("description", description);
        return result;
    }

    private static void get(Map<String, Object> params, List<String> required, Map<String, Object> kind) {
        required(params, required, "kind", kind);
        required(params, required, "id", ID);
    }

    private static void create(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                               boolean parentRequired, Map<String, Object> fields) {
        required(params, required, "kind", kind);
        required(params, required, "fields", fields);
        params.putAll(properties("parentId", ID, "expectedParentVersion", VERSION));
        if (parentRequired) {
            required.addAll(List.of("parentId", "expectedParentVersion"));
        }
    }

    private static void update(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                               Map<String, Object> fields) {
        get(params, required, kind);
        required(params, required, "expectedVersion", VERSION);
        required(params, required, "patch", fields);
    }

    private static void target(Map<String, Object> params, List<String> required) {
        required(params, required, "targetKind", TARGET);
        required(params, required, "targetId", ID);
    }

    private static void required(Map<String, Object> params, List<String> required, String name, Object schema) {
        params.put(name, schema);
        required.add(name);
    }

    private static Map<String, Object> forms(Map<CanvasValueType, Contract> source, CanvasValueType... types) {
        return Map.of("type", "object", "anyOf", Arrays.stream(types).map(type -> source.get(type).definition()).toList());
    }

    private static Map<String, Object> patchObject(Map<String, Object> properties) {
        var schema = new LinkedHashMap<>(object(properties));
        schema.put("minProperties", 1);
        return schema;
    }

    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", Map.copyOf(properties), "required", List.of(required),
                "additionalProperties", false);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max);
    }

    private static Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> pattern(String value) {
        return Map.of("type", "string", "pattern", value);
    }

    private static Map<String, Object> nonBlank() {
        return pattern("\\S");
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> textFields(String... names) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : names) {
            fields.put(name, nullable(TEXT));
        }
        return fields;
    }

    private static Map<String, Object> properties(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put((String) pairs[index], pairs[index + 1]);
        }
        return fields;
    }
}
