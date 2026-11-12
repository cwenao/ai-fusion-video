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
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
public class McpProjectCredentials {
    public static final String READ = "read";
    public static final String READ_WRITE = "read_write";
    private static final String ALL_PROJECTS = "all";
    // 项目主键为正整数，凭证表用 0 表示签发人当前范围内的全部项目。
    private static final long ALL_PROJECTS_ID = 0;
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

    record ProjectGrant(String credentialId, long projectId, CanvasActor actor, String scope) {
    }

    public record Metadata(String credentialId, String projectId, String userId, String teamId, String label,
                           String scope, Instant createdAt, Instant expiresAt, Instant revokedAt) {
    }

    public record Issued(String credentialId, String projectId, String scope, Instant expiresAt, String token) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Issued issue(String target, CanvasActor actor, String label, String scope, int expiresInSeconds) {
        long projectId = credentialProjectId(target);
        String effectiveScope = scope == null ? READ : scope;
        if (projectId == ALL_PROJECTS_ID) {
            access.requireActorScope(actor);
        } else if (READ_WRITE.equals(effectiveScope)) {
            access.requireProjectEditor(projectId, actor);
        } else {
            access.requireProject(projectId, actor);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String id = UUID.randomUUID().toString();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = expiresInSeconds == 0 ? null : now.plusSeconds(expiresInSeconds);
        credentials.insert(McpProjectCredential.builder().id(id).projectId(projectId).userId(actor.userId())
                .teamId(actor.teamId()).label(label.strip()).scope(effectiveScope).secretDigest(digest(secret))
                .createdAt(now).expiresAt(expiresAt == null ? now : expiresAt).build());
        log.info("签发项目 MCP 凭证 credentialId={} userId={} projectId={} scope={}",
                id, actor.userId(), projectId, effectiveScope);
        return new Issued(id, targetName(projectId), effectiveScope, expiresAt, id + "." + secret);
    }

    public List<Metadata> list(String target, CanvasActor actor) {
        long projectId = credentialProjectId(target);
        var query = Wrappers.<McpProjectCredential>lambdaQuery().eq(McpProjectCredential::getProjectId, projectId);
        if (projectId == ALL_PROJECTS_ID) {
            access.requireActorScope(actor);
            query.eq(McpProjectCredential::getUserId, actor.userId());
            if (actor.teamId() == null) {
                query.isNull(McpProjectCredential::getTeamId);
            } else {
                query.eq(McpProjectCredential::getTeamId, actor.teamId());
            }
        } else {
            query.eq(!access.canManageProject(projectId, actor), McpProjectCredential::getUserId, actor.userId());
        }
        return credentials.selectList(query.orderByDesc(McpProjectCredential::getCreatedAt, McpProjectCredential::getId))
                .stream().map(row -> new Metadata(row.getId(), targetName(row.getProjectId()),
                        row.getUserId().toString(), row.getTeamId() == null ? null : row.getTeamId().toString(),
                        row.getLabel(), row.getScope(), row.getCreatedAt(), expiry(row), row.getRevokedAt()))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void rename(String target, String id, CanvasActor actor, String label) {
        long projectId = credentialProjectId(target);
        var row = requireManageable(projectId, id, actor);
        if (row.getRevokedAt() != null) {
            throw McpProjectFailure.missing();
        }
        String name = label.strip();
        if (name.equals(row.getLabel())) {
            return;
        }
        int changed = credentials.update(null, Wrappers.<McpProjectCredential>lambdaUpdate()
                .eq(McpProjectCredential::getId, id)
                .isNull(McpProjectCredential::getRevokedAt)
                .set(McpProjectCredential::getLabel, name));
        if (changed > 0) {
            log.info("修改项目 MCP 凭证名称 credentialId={} userId={} projectId={}", id, actor.userId(), projectId);
        } else if (credentials.selectById(id).getRevokedAt() != null) {
            throw McpProjectFailure.missing();
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(String target, String id, CanvasActor actor) {
        long projectId = credentialProjectId(target);
        requireManageable(projectId, id, actor);
        int changed = credentials.update(null, Wrappers.<McpProjectCredential>lambdaUpdate()
                .eq(McpProjectCredential::getId, id)
                .isNull(McpProjectCredential::getRevokedAt)
                .set(McpProjectCredential::getRevokedAt, Instant.now()));
        if (changed > 0) {
            log.info("撤销项目 MCP 凭证 credentialId={} userId={} projectId={}", id, actor.userId(), projectId);
        }
    }

    private McpProjectCredential requireManageable(long projectId, String id, CanvasActor actor) {
        McpProjectCredential row = credentials.selectById(id);
        if (row == null || row.getProjectId() != projectId) {
            throw McpProjectFailure.missing();
        }
        if (projectId == ALL_PROJECTS_ID) {
            if (row.getUserId() != actor.userId() || !Objects.equals(row.getTeamId(), actor.teamId())) {
                throw McpProjectFailure.missing();
            }
        } else if (row.getUserId() != actor.userId()) {
            access.requireProjectManager(projectId, actor);
        }
        return row;
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

    public void requireConnection(Grant grant) {
        if (grant.projectId() == ALL_PROJECTS_ID) {
            access.requireActorScope(grant.actor());
        } else {
            access.requireProject(grant.projectId(), grant.actor());
        }
    }

    public void requireProject(Grant grant, long projectId, boolean write) {
        if (projectId <= 0 || (grant.projectId() != ALL_PROJECTS_ID && grant.projectId() != projectId)
                || (write && !grant.canWrite())) {
            throw McpProjectFailure.denied();
        }
        if (write) {
            access.requireProjectEditor(projectId, grant.actor());
        } else {
            access.requireProject(projectId, grant.actor());
        }
    }

    ProjectGrant authorizeProject(Grant grant, long projectId, boolean write) {
        requireProject(grant, projectId, write);
        return new ProjectGrant(grant.credentialId(), projectId, grant.actor(), grant.scope());
    }

    ProjectGrant recheckRead(Grant grant, long projectId) {
        active(grant.credentialId());
        return authorizeProject(grant, projectId, false);
    }

    boolean canWrite(ProjectGrant grant) {
        return READ_WRITE.equals(grant.scope()) && access.canEditProject(grant.projectId(), grant.actor());
    }

    private static long credentialProjectId(String target) {
        if (ALL_PROJECTS.equals(target)) {
            return ALL_PROJECTS_ID;
        }
        try {
            long projectId = Long.parseLong(target);
            if (projectId > 0) {
                return projectId;
            }
        } catch (NumberFormatException invalidId) {
            throw McpProjectFailure.invalid("projectId", "项目编号无效");
        }
        throw McpProjectFailure.invalid("projectId", "项目编号无效");
    }

    private static String targetName(long projectId) {
        return projectId == ALL_PROJECTS_ID ? ALL_PROJECTS : Long.toString(projectId);
    }

    private McpProjectCredential active(String id) {
        var row = credentials.selectById(id);
        if (row == null || row.getRevokedAt() != null) {
            throw McpProjectFailure.unauthorized();
        }
        Instant expiresAt = expiry(row);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw McpProjectFailure.unauthorized();
        }
        return row;
    }

    // 限时凭证的到期时间严格晚于签发时间。两者相等表示永久有效。
    private static Instant expiry(McpProjectCredential row) {
        return row.getCreatedAt().equals(row.getExpiresAt()) ? null : row.getExpiresAt();
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
