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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;
import static com.stonewu.fusion.service.resource.AssetFields.APPEARANCE_STATE;
import static com.stonewu.fusion.service.resource.AssetFields.MAX_CREATION_ITEMS;
import static com.stonewu.fusion.service.resource.AssetFields.MAX_VARIANT_LABEL_LENGTH;
import static com.stonewu.fusion.service.resource.AssetFields.TIMELINE;
import static com.stonewu.fusion.service.resource.model.ResourceBatchDeleteCommand.MAX_TARGETS;
import static com.stonewu.fusion.service.resource.model.ResourceBatchWriteCommand.MAX_ENTRIES;

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
    private static final Map<String, Object> ORDER = integer(0, 1_000_000);
    private static final Map<String, Object> STATUS = integer(0, 1);
    private static final Map<String, Object> IMAGE_PROMPT_TEXT = Map.of("type", "string", "maxLength", 5_000, "pattern", "\\S");
    private static final Map<String, Object> VARIANT_LABEL = Map.of("type", "string", "maxLength", MAX_VARIANT_LABEL_LENGTH,
            "pattern", "\\S");
    private static final Map<String, Object> SHOT_CUSTOM_DATA = Map.of("type", "object", "maxProperties", 200,
            "propertyNames", Map.of("not", choice("videoPosterUrl")));
    private static final Map<String, Object> ASSET_TYPE = choice("character", "scene", "prop", "vehicle",
            "building", "costume", "effect", "material", "image");
    private static final Map<String, Object> TARGET = choice("shot", "asset", "assetItem");
    private static final Map<String, Object> SCRIPT_DETAIL = choice("script", "episode", "scene");
    private static final Map<String, Object> SCRIPT_CHILD = choice("episode", "scene");
    private static final Map<String, Object> BOARD_DETAIL = choice("storyboard", "episode", "scene", "shot");
    private static final Map<String, Object> BOARD_CHILD = choice("episode", "scene", "shot");
    private static final Map<String, Object> ASSET_KIND = choice("asset", "assetItem");
    private static final String CHILD_BATCH_PARENT_VERSION = "目标为子资产时必填，使用批次开始前的主资产版本";
    private static final Map<String, Object> RAW_CONTENT = described(BOOLEAN,
            "是否返回剧本原文 rawContent。分集默认返回，主档默认省略");
    private static final Map<String, Object> PROMPT = choice("firstFramePrompt", "lastFramePrompt", "videoPrompt",
            "aiPrompt", "imagePrompt");
    private static final Map<String, Object> SLOTS = choice("cover", "image", "thumbnail", "video", "audio",
            "referenceImage", "firstFrame", "lastFrame", "generatedImage", "generatedVideo", "videoPoster");

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
                "sceneAssetId", nullable(ID), "propAssetIds", nullable(array(ID, 0, 10_000)), "status", STATUS));
        addOrderedChild(SCRIPT_SCENE, scene, "sceneHeading");

        var board = textFields("description");
        board.putAll(properties("customColumns", nullable(array(Map.of("type", "object"), 0, 200)), "status", STATUS));
        addFields(STORYBOARD, board, false);
        var boardEpisode = textFields("title", "synopsis");
        boardEpisode.putAll(properties("episodeNumber", integer(1, 1_000_000), "scriptEpisodeId", nullable(ID),
                "status", STATUS));
        addOrderedChild(STORYBOARD_EPISODE, boardEpisode, "episodeNumber");
        var boardScene = sceneFields();
        boardScene.put("status", STATUS);
        addOrderedChild(STORYBOARD_SCENE, boardScene, "sceneHeading");
        var shot = textFields("content", "shotType", "shotNumber", "sceneExpectation", "dialogue", "sound",
                "soundEffect", "music", "cameraMovement", "cameraAngle", "cameraEquipment", "focalLength",
                "transition", "remark", "firstFramePrompt", "lastFramePrompt", "videoPrompt");
        shot.putAll(properties("duration", nullable(Map.of("type", "number", "minimum", 0, "maximum", 86400)),
                "characterIds", array(ID, 0, 500), "sceneAssetItemId", nullable(ID), "propIds", array(ID, 0, 500),
                "aiGenerated", BOOLEAN, "status", STATUS));
        addOrderedChild(SHOT, shot, "content");

        addAssetFields(ASSET_ITEM, properties("name", nullable(TEXT), "itemType", pattern("^[a-z][a-z0-9_]{0,31}$")));
        var asset = properties("name", nonBlank(), "description", described(nullable(TEXT), "主资产设定"),
                "tags", nullable(array(Map.of("type", "string", "maxLength", 128), 0, 100)));
        addAssetFields(ASSET, asset, "type", "name");
    }

    private void addAssetFields(CanvasValueType type, Map<String, Object> asset, String... required) {
        var updates = new LinkedHashMap<>(asset);
        updates.put("propertyChanges", described(assetProperties(type, true), "仅修改提交的设定字段，null 删除单项；原件通过媒体工具绑定"));
        patch.put(type, compile(patchObject(updates)));
        asset.put("properties", described(assetProperties(type, false), "资产设定扩展字段"));
        if (type == ASSET) {
            asset.put("type", ASSET_TYPE);
        }
        var readable = new LinkedHashSet<>(asset.keySet());
        readable.remove("properties");
        readFields.put(type, Set.copyOf(readable));
        if (type == ASSET) {
            var children = array(create.get(ASSET_ITEM).definition(), 1, MAX_CREATION_ITEMS);
            asset.put("items", described(children, "按数组顺序创建子资产；第一项为基准表现。省略时创建一个默认子资产。"));
        }
        create.put(type, compile(object(asset, required)));
    }

    private void addOrderedChild(CanvasValueType type, Map<String, Object> fields, String required) {
        var input = new LinkedHashMap<>(fields);
        if (type == SHOT) {
            fields.put("customDataChanges", described(SHOT_CUSTOM_DATA, "只更新提交的自定义字段，null 删除单项；封面独立绑定"));
            input.put("customData", nullable(SHOT_CUSTOM_DATA));
        }
        addFields(type, fields, false);
        input.put("sortOrder", ORDER);
        if (!required.equals("episodeNumber")) {
            input.put(required, nonBlank());
        }
        create.put(type, compile(object(input, required)));
    }

    private static Map<String, Object> assetProperties(CanvasValueType type, boolean patch) {
        var fields = properties("appearanceDescription", nullable(TEXT),
                "imagePrompt", patch ? nullable(IMAGE_PROMPT_TEXT) : IMAGE_PROMPT_TEXT);
        if (type == ASSET_ITEM) {
            var label = patch ? nullable(VARIANT_LABEL) : VARIANT_LABEL;
            fields.put(TIMELINE, described(label, "时间线或叙事阶段，例如：十年前、主线第 8 集"));
            fields.put(APPEARANCE_STATE, described(label, "此时的外观状态，例如：受伤、换装、维修后"));
        }
        return Map.of("type", "object", "maxProperties", 200, "propertyNames", Map.of("not", choice(
                        "sourceMediaUrl", "sourceGenerationMode", "videoUrl", "audioUrl", "thumbnailUrl")),
                "properties", fields);
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
                list(params, required, SCRIPT_CHILD);
                required(params, required, "parentId", ID);
                params.put("includeRawContent", described(BOOLEAN, "是否返回分集剧本原文 rawContent，默认 true"));
            }
            case SCRIPT_GET -> {
                get(params, required, SCRIPT_DETAIL);
                params.put("includeRawContent", RAW_CONTENT);
            }
            case SCRIPT_BATCH_GET -> {
                batchGet(params, required, SCRIPT_DETAIL);
                params.put("includeRawContent", RAW_CONTENT);
            }
            case SCRIPT_CREATE -> create(params, required, SCRIPT_CHILD, true,
                    forms(create, SCRIPT_EPISODE, SCRIPT_SCENE));
            case SCRIPT_BATCH_CREATE -> batchCreate(params, required, SCRIPT_CHILD, true,
                    forms(create, SCRIPT_EPISODE, SCRIPT_SCENE));
            case SCRIPT_UPDATE -> update(params, required, SCRIPT_DETAIL,
                    forms(patch, SCRIPT, SCRIPT_EPISODE, SCRIPT_SCENE));
            case SCRIPT_BATCH_UPDATE -> batchUpdate(params, required, SCRIPT_DETAIL,
                    forms(patch, SCRIPT, SCRIPT_EPISODE, SCRIPT_SCENE));
            case SCRIPT_DELETE -> delete(params, required, choice("scene"));
            case SCRIPT_BATCH_DELETE -> batchDelete(params, required, choice("scene"));
            case SCRIPT_REORDER_SCENES -> reorder(params, required);
            case STORYBOARD_LIST -> {
                list(params, required, BOARD_CHILD);
                required(params, required, "parentKind", choice("storyboard", "episode", "scene"));
                required(params, required, "parentId", ID);
            }
            case STORYBOARD_GET -> get(params, required, BOARD_DETAIL);
            case STORYBOARD_BATCH_GET -> batchGet(params, required, BOARD_DETAIL);
            case STORYBOARD_CREATE -> create(params, required, BOARD_CHILD, true,
                    forms(create, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_BATCH_CREATE -> batchCreate(params, required, BOARD_CHILD, true,
                    forms(create, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_UPDATE -> update(params, required, BOARD_DETAIL,
                    forms(patch, STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_BATCH_UPDATE -> batchUpdate(params, required, BOARD_DETAIL,
                    forms(patch, STORYBOARD, STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT));
            case STORYBOARD_REORDER -> {
                required(params, required, "parentKind", choice("storyboard", "episode", "scene"));
                reorder(params, required);
            }
            case STORYBOARD_DELETE -> delete(params, required, choice("scene", "shot"));
            case STORYBOARD_BATCH_DELETE -> batchDelete(params, required, choice("scene", "shot"));
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
            case PROMPT_BATCH_GET -> {
                required(params, required, "targetKind", TARGET);
                required(params, required, "slot", PROMPT);
                required(params, required, "targetIds", batchIds(ID));
            }
            case PROMPT_BATCH_SET -> {
                required(params, required, "targetKind", TARGET);
                required(params, required, "slot", PROMPT);
                batchTargets(params, required, "targetId", "text", nullable(TEXT), CHILD_BATCH_PARENT_VERSION);
            }
            case ASSET_LIST -> {
                list(params, required, ASSET_KIND);
                params.putAll(properties("parentId", ID, "assetType", ASSET_TYPE, "keyword",
                        Map.of("type", "string", "maxLength", 200)));
            }
            case ASSET_GET -> get(params, required, ASSET_KIND);
            case ASSET_BATCH_GET -> batchGet(params, required, ASSET_KIND);
            case ASSET_CREATE -> create(params, required, ASSET_KIND, false,
                    forms(create, ASSET, ASSET_ITEM));
            case ASSET_BATCH_CREATE -> {
                batchCreate(params, required, ASSET_KIND, false, forms(create, ASSET, ASSET_ITEM));
                return assetParentSchema(params, required);
            }
            case ASSET_UPDATE -> {
                update(params, required, ASSET_KIND, forms(patch, ASSET, ASSET_ITEM));
                params.put("expectedParentVersion", VERSION);
            }
            case ASSET_BATCH_UPDATE -> {
                required(params, required, "kind", ASSET_KIND);
                params.put("expectedParentVersion", described(VERSION,
                        "kind 为 assetItem 且各目标的父版本相同时，可统一填写批次开始前的 parentVersion，各目标省略同名字段"));
                batchTargets(params, required, "id", "patch", forms(patch, ASSET, ASSET_ITEM),
                        "kind 为 assetItem 且未填写顶层 expectedParentVersion 时，每项必填其批次开始前的 parentVersion");
            }
            case ASSET_BATCH_DELETE -> {
                batchDelete(params, required, ASSET_KIND);
                params.putAll(properties("parentId", described(ID, "删除子资产时填写所属主资产 ID"),
                        "expectedParentVersion", described(VERSION, "删除子资产时填写主资产当前版本")));
                return assetParentSchema(params, required);
            }
            case MEDIA_GET -> required(params, required, "mediaResourceId", MEDIA_ID);
            case MEDIA_BATCH_GET -> required(params, required, "mediaResourceIds", batchIds(MEDIA_ID));
            case MEDIA_BIND -> {
                target(params, required);
                required(params, required, "bindings", bindings());
            }
            case MEDIA_BATCH_BIND -> {
                required(params, required, "targetKind", TARGET);
                batchTargets(params, required, "targetId", "bindings", bindings(), CHILD_BATCH_PARENT_VERSION);
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

    private static void batchGet(Map<String, Object> params, List<String> required, Map<String, Object> kind) {
        required(params, required, "kind", kind);
        required(params, required, "ids", batchIds(ID));
    }

    private static Map<String, Object> batchIds(Map<String, Object> id) {
        var schema = new LinkedHashMap<>(array(id, 1, MAX_ENTRIES));
        schema.put("uniqueItems", true);
        return schema;
    }

    private static Map<String, Object> bindings() {
        return array(object(properties("slot", SLOTS, "mediaResourceId",
                described(nullable(MEDIA_ID), "托管媒体 ID，传 null 清空槽位")), "slot", "mediaResourceId"), 1, 8);
    }

    private static void create(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                               boolean parentRequired, Map<String, Object> fields) {
        required(params, required, "kind", kind);
        required(params, required, "fields", fields);
        creationParent(params, required, parentRequired);
    }

    private static void batchCreate(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                                    boolean parentRequired, Map<String, Object> fields) {
        required(params, required, "kind", kind);
        required(params, required, "items", described(array(fields, 1, MAX_ENTRIES),
                "每项使用单条新增的 fields 结构，按数组顺序执行，任一项失败则全部回滚"));
        creationParent(params, required, parentRequired);
    }

    private static void creationParent(Map<String, Object> params, List<String> required, boolean parentRequired) {
        params.putAll(properties("parentId", ID, "expectedParentVersion", VERSION));
        if (parentRequired) {
            required.addAll(List.of("parentId", "expectedParentVersion"));
        }
    }

    private static Map<String, Object> assetParentSchema(Map<String, Object> params, List<String> required) {
        var schema = new LinkedHashMap<>(object(params, required.toArray(String[]::new)));
        schema.put("if", Map.of("properties", Map.of("kind", choice("assetItem"))));
        schema.put("then", Map.of("required", List.of("parentId", "expectedParentVersion")));
        schema.put("else", Map.of("properties", Map.of("parentId", false, "expectedParentVersion", false)));
        return schema;
    }

    private static void update(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                               Map<String, Object> fields) {
        get(params, required, kind);
        required(params, required, "expectedVersion", VERSION);
        required(params, required, "patch", fields);
    }

    private static void batchUpdate(Map<String, Object> params, List<String> required, Map<String, Object> kind,
                                    Map<String, Object> fields) {
        required(params, required, "kind", kind);
        batchTargets(params, required, "id", "patch", fields, null);
    }

    private static void batchTargets(Map<String, Object> params, List<String> required, String idField,
                                     String dataField, Map<String, Object> data, String parentVersionDescription) {
        var fields = properties(idField, ID, "expectedVersion", VERSION, dataField, data);
        if (parentVersionDescription != null) {
            fields.put("expectedParentVersion", described(VERSION, parentVersionDescription));
        }
        var target = object(fields, idField, "expectedVersion", dataField);
        required(params, required, "targets", described(array(target, 1, MAX_ENTRIES),
                "同类目标的 ID、当前版本和写入内容，ID 不可重复，任一项失败则全部回滚"));
    }

    private static void delete(Map<String, Object> params, List<String> required, Map<String, Object> kind) {
        get(params, required, kind);
        required(params, required, "expectedVersion", VERSION);
    }

    private static void batchDelete(Map<String, Object> params, List<String> required, Map<String, Object> kind) {
        required(params, required, "kind", kind);
        var target = object(properties("id", ID, "expectedVersion", VERSION), "id", "expectedVersion");
        required(params, required, "targets", described(array(target, 1, MAX_TARGETS),
                "同一类型的明确 ID 与版本，ID 不可重复，任一目标失败则全部回滚"));
    }

    private static void reorder(Map<String, Object> params, List<String> required) {
        required(params, required, "parentId", ID);
        required(params, required, "expectedParentVersion", VERSION);
        required(params, required, "childIds", array(ID, 0, 5_000));
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
