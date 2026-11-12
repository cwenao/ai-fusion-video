package com.stonewu.fusion.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.repository.media.MediaResourceRepository;
import com.stonewu.fusion.repository.media.MediaResourceRepository.Row;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.ProjectGrant;
import com.stonewu.fusion.service.media.MediaAccessPolicy;
import com.stonewu.fusion.service.media.MediaIntakeService;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.MediaSourceFields;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.media.model.MediaResource;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
public class McpProjectMedia {
    public static final String ROOT = "/api/mcp/projects/{projectId}/media";
    private static final long MAX_FILE_BYTES = 95L * 1024 * 1024;
    private static final Set<MediaKind> KINDS = Set.of(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO);
    private static final Map<CanvasValueType, Map<String, String>> SLOTS = Map.of(
            CanvasValueType.ASSET, Map.of("cover", "coverUrl"),
            CanvasValueType.ASSET_ITEM, Map.of("image", "imageUrl", "thumbnail", "thumbnailUrl",
                    "video", "properties.videoUrl", "audio", "properties.audioUrl"),
            CanvasValueType.SHOT, Map.of("image", "imageUrl", "referenceImage", "referenceImageUrl",
                    "firstFrame", "firstFrameImageUrl", "lastFrame", "lastFrameImageUrl", "video", "videoUrl"));

    private final MediaResourceRepository media;
    private final MediaAccessPolicy access;
    private final MediaIntakeService intake;
    private final McpProjectCredentials credentials;
    private final CanvasJson json;

    public ObjectNode upload(Grant grant, long projectId, String requestId, List<MultipartFile> files) throws IOException {
        credentials.requireProject(grant, projectId, true);
        if (!requestId.matches(McpProjectToolSchemas.REQUEST_PATTERN)) {
            throw McpProjectFailure.invalid("requestId", "上传操作的 requestId 格式无效");
        }
        if (files.size() != 1 || files.getFirst().isEmpty()) {
            throw McpProjectFailure.invalid("file", "必须提供一个非空文件");
        }
        var file = files.getFirst();
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new MediaException(413, "MEDIA_TOO_LARGE", "MCP 原件超过单文件大小限制");
        }
        var key = McpProjectValues.requestKey(json, projectId, "media_upload", requestId);
        try (var stream = file.getInputStream()) {
            var resource = intake.uploadChecked(stream, file.getOriginalFilename(), file.getContentType(), key,
                    projectId, ResourceCommandContext.user(grant.actor(), null), KINDS);
            if (!"READY".equals(resource.state())) {
                throw notReady(resource);
            }
            log.info("项目 MCP 原件接收 credentialId={} userId={} projectId={} requestId={} mediaResourceId={}",
                    grant.credentialId(), grant.actor().userId(), projectId, requestId, resource.id());
            return describe(resource, projectId).put("requestId", requestId);
        }
    }

    public Row download(Grant grant, long projectId, String mediaId) {
        var target = credentials.authorizeProject(grant, projectId, false);
        return scoped(target, mediaId, true);
    }

    public void recheckDownload(Grant grant, long projectId, String mediaId, int version) {
        var target = credentials.recheckRead(grant, projectId);
        if (scoped(target, mediaId, true).resource().version() != version) {
            throw new MediaException(410, "MEDIA_VERSION_CHANGED", "原件状态在下载期间已变化");
        }
    }

    ObjectNode get(ProjectGrant grant, String mediaId) {
        return describe(scoped(grant, mediaId, false).resource(), grant.projectId());
    }

    ObjectNode uploadOptions(long projectId) {
        return json.object(Map.of("uploadUrl", path(projectId), "maxFileBytes", MAX_FILE_BYTES,
                "kinds", List.of("image", "video", "audio")));
    }

    Map<String, String> checkedBindings(ProjectGrant grant, CanvasValueType type, JsonNode bindings) {
        var mapping = SLOTS.get(type);
        Map<String, String> output = new LinkedHashMap<>();
        for (var binding : bindings) {
            String slot = binding.path("slot").asText();
            String field = mapping.get(slot);
            if (field == null || output.containsKey(field)) {
                throw McpProjectFailure.invalid("bindings.slot", "媒体槽位无效或重复");
            }
            String id = binding.path("mediaResourceId").asText();
            scoped(grant, id, true);
            output.put(field, id);
        }
        return output;
    }

    void readBindings(ProjectGrant grant, CanvasValueType type, ObjectNode source, ArrayNode bindings) {
        var slots = SLOTS.get(type);
        for (String field : MediaSourceFields.fields(type).keySet().stream().sorted().toList()) {
            var value = source.at("/" + field.replace('.', '/'));
            if (!value.isTextual() || value.textValue().isBlank()) {
                continue;
            }
            var target = bindings.addObject().put("field", field);
            slots.entrySet().stream().filter(entry -> entry.getValue().equals(field)).findFirst()
                    .ifPresent(entry -> target.put("slot", entry.getKey()));
            String reference = value.textValue();
            if (!MediaReference.managed(reference)) {
                target.put("state", "unmanaged");
                continue;
            }
            try {
                String id = MediaReference.id(reference);
                var resource = scoped(grant, id, false).resource();
                switch (resource.state()) {
                    case "READY" -> target.put("mediaResourceId", id).put("state", "ready");
                    case "STAGING" -> target.put("mediaResourceId", id).put("state", "pending");
                    default -> target.put("state", "unavailable");
                }
            } catch (McpProjectFailure failure) {
                if (failure.getCode() != 404) {
                    throw failure;
                }
                target.put("state", "unavailable");
            } catch (MediaException invalidReference) {
                if (invalidReference.getCode() != 400) {
                    throw invalidReference;
                }
                target.put("state", "unavailable");
            }
        }
    }

    boolean hasBindingFields(CanvasValueType type) {
        return SLOTS.containsKey(type);
    }

    private Row scoped(ProjectGrant grant, String id, boolean ready) {
        MediaReference.of(id);
        try {
            var row = media.get(id, false);
            var resource = row.resource();
            if (!Objects.equals(resource.projectId(), grant.projectId())) {
                throw McpProjectFailure.missing();
            }
            access.read(resource, grant.actor());
            if (ready && !"READY".equals(resource.state())) {
                throw notReady(resource);
            }
            return row;
        } catch (MediaException failure) {
            if (failure.getCode() == 404 || failure.getCode() == 410 || failure.getCode() == 403) {
                throw McpProjectFailure.missing();
            }
            throw failure;
        }
    }

    private ObjectNode describe(MediaResource resource, long projectId) {
        var content = resource.content();
        var output = json.object(Map.of("mediaResourceId", resource.id(), "name", resource.name(),
                "kind", content.kind().name().toLowerCase(Locale.ROOT), "mimeType", content.mimeType(),
                "byteSize", content.byteSize(), "sha256", content.contentHash(),
                "state", resource.state(), "version", resource.version()));
        if ("READY".equals(resource.state())) {
            output.put("downloadUrl", path(projectId) + "/" + resource.id() + "/content");
        }
        return output;
    }

    private static String path(long projectId) {
        return ROOT.replace("{projectId}", Long.toString(projectId));
    }

    private static McpProjectFailure notReady(MediaResource resource) {
        return new McpProjectFailure(409, "MEDIA_NOT_READY", "媒体尚未就绪", Map.of("state", resource.state()));
    }
}
