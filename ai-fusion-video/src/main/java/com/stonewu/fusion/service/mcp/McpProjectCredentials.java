package com.stonewu.fusion.service.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.fusion.entity.project.McpProjectCredential;
import com.stonewu.fusion.mapper.project.McpProjectCredentialMapper;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
public class McpProjectCredentials {
    public static final String READ = "read";
    public static final String READ_WRITE = "read_write";
    private static final Pattern TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpProjectCredentialMapper credentials;
    private final CanvasAccessPolicy access;

    public record Grant(String credentialId, long projectId, CanvasActor actor, String scope) {
        public boolean canWrite() {
            return READ_WRITE.equals(scope);
        }
    }

    public record Metadata(String credentialId, String projectId, String userId, String teamId, String label,
                           String scope, Instant createdAt, Instant expiresAt, Instant revokedAt) {
    }

    public record Issued(String credentialId, String projectId, String scope, Instant expiresAt, String token) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Issued issue(long projectId, CanvasActor actor, String label, String scope, int expiresInSeconds) {
        String effectiveScope = scope == null ? READ : scope;
        if (READ_WRITE.equals(effectiveScope)) {
            access.requireProjectEditor(projectId, actor);
        } else {
            access.requireProject(projectId, actor);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String id = UUID.randomUUID().toString();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds);
        credentials.insert(McpProjectCredential.builder().id(id).projectId(projectId).userId(actor.userId())
                .teamId(actor.teamId()).label(label.strip()).scope(effectiveScope).secretDigest(digest(secret))
                .createdAt(now).expiresAt(expiresAt).build());
        log.info("签发项目 MCP 凭证 credentialId={} userId={} projectId={} scope={}",
                id, actor.userId(), projectId, effectiveScope);
        return new Issued(id, Long.toString(projectId), effectiveScope, expiresAt, id + "." + secret);
    }

    public List<Metadata> list(long projectId, CanvasActor actor) {
        boolean manager = access.canManageProject(projectId, actor);
        return credentials.selectList(Wrappers.<McpProjectCredential>lambdaQuery()
                        .eq(McpProjectCredential::getProjectId, projectId)
                        .eq(!manager, McpProjectCredential::getUserId, actor.userId())
                        .orderByDesc(McpProjectCredential::getCreatedAt, McpProjectCredential::getId))
                .stream().map(row -> new Metadata(row.getId(), row.getProjectId().toString(),
                        row.getUserId().toString(), row.getTeamId() == null ? null : row.getTeamId().toString(),
                        row.getLabel(), row.getScope(), row.getCreatedAt(), row.getExpiresAt(), row.getRevokedAt()))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(long projectId, String id, CanvasActor actor) {
        McpProjectCredential row = credentials.selectById(id);
        if (row == null || row.getProjectId() != projectId) {
            throw McpProjectFailure.missing();
        }
        if (row.getUserId() != actor.userId()) {
            access.requireProjectManager(projectId, actor);
        }
        int changed = credentials.update(null, Wrappers.<McpProjectCredential>lambdaUpdate()
                .eq(McpProjectCredential::getId, id)
                .isNull(McpProjectCredential::getRevokedAt)
                .set(McpProjectCredential::getRevokedAt, Instant.now()));
        if (changed > 0) {
            log.info("撤销项目 MCP 凭证 credentialId={} userId={} projectId={}", id, actor.userId(), projectId);
        }
    }

    public Grant authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw McpProjectFailure.unauthorized();
        }
        String token = authorization.substring(7);
        if (!TOKEN.matcher(token).matches()) {
            throw McpProjectFailure.unauthorized();
        }
        String id = token.substring(0, 36);
        var row = active(id);
        if (!MessageDigest.isEqual(row.getSecretDigest().getBytes(StandardCharsets.US_ASCII),
                digest(token.substring(37)).getBytes(StandardCharsets.US_ASCII))) {
            throw McpProjectFailure.unauthorized();
        }
        return new Grant(id, row.getProjectId(), new CanvasActor(row.getUserId(), row.getTeamId()), row.getScope());
    }

    public void requireProject(Grant grant, long projectId, boolean write) {
        if (grant.projectId() != projectId || (write && !grant.canWrite())) {
            throw McpProjectFailure.denied();
        }
        if (write) {
            access.requireProjectEditor(projectId, grant.actor());
        } else {
            access.requireProject(projectId, grant.actor());
        }
    }

    public void recheckRead(Grant grant) {
        active(grant.credentialId());
        requireProject(grant, grant.projectId(), false);
    }

    boolean canWrite(Grant grant) {
        return grant.canWrite() && access.canEditProject(grant.projectId(), grant.actor());
    }

    private McpProjectCredential active(String id) {
        var row = credentials.selectById(id);
        if (row == null || row.getRevokedAt() != null || !row.getExpiresAt().isAfter(Instant.now())) {
            throw McpProjectFailure.unauthorized();
        }
        return row;
    }

    static String digest(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }
}
