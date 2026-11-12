package com.stonewu.fusion.service.mcp;

import com.stonewu.fusion.entity.project.McpProjectCredential;
import com.stonewu.fusion.mapper.project.McpProjectCredentialMapper;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.canvas.model.CanvasErrorCode;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpProjectCredentialsTests {
    private final CanvasActor issuer = new CanvasActor(2L, 3L);
    private final McpProjectCredentialMapper mapper = mock(McpProjectCredentialMapper.class);
    private final CanvasAccessPolicy access = mock(CanvasAccessPolicy.class);
    private final McpProjectCredentials credentials = new McpProjectCredentials(mapper, access);

    @Test
    void allProjectsReadFollowsCurrentProjectAccessAndRejectsWrites() {
        var grant = issueAndAuthenticate("all", McpProjectCredentials.READ);
        credentials.requireConnection(grant);

        assertEquals(11L, credentials.authorizeProject(grant, 11L, false).projectId());
        assertEquals(12L, credentials.authorizeProject(grant, 12L, false).projectId());
        assertThrows(McpProjectFailure.class, () -> credentials.authorizeProject(grant, 12L, true));
        assertThrows(McpProjectFailure.class, () -> credentials.authorizeProject(grant, 0L, false));

        when(access.requireProject(12L, issuer)).thenThrow(denied());
        assertThrows(CanvasException.class, () -> credentials.authorizeProject(grant, 12L, false));
        assertThrows(CanvasException.class, () -> credentials.recheckRead(grant, 12L));
        doThrow(denied()).when(access).requireActorScope(issuer);
        assertThrows(CanvasException.class, () -> credentials.requireConnection(grant));
    }

    @Test
    void allProjectsWriteStillRequiresEditingTheTargetProject() {
        var grant = issueAndAuthenticate("all", McpProjectCredentials.READ_WRITE);
        doThrow(denied()).when(access).requireProjectEditor(12L, issuer);

        assertEquals(11L, credentials.authorizeProject(grant, 11L, true).projectId());
        assertEquals(12L, credentials.authorizeProject(grant, 12L, false).projectId());
        assertThrows(CanvasException.class, () -> credentials.authorizeProject(grant, 12L, true));
    }

    @Test
    void existingProjectCredentialRemainsBoundAndAllAliasIsExplicit() {
        var grant = issueAndAuthenticate("11", McpProjectCredentials.READ_WRITE);

        assertEquals(11L, credentials.authorizeProject(grant, 11L, true).projectId());
        assertThrows(McpProjectFailure.class, () -> credentials.authorizeProject(grant, 12L, false));
        assertThrows(McpProjectFailure.class, () -> credentials.issue("0", issuer, "临时验收",
                McpProjectCredentials.READ, 3600));
    }

    @Test
    void allProjectsCredentialCannotBeRevokedByAnotherUserOrSpace() {
        var grant = issueAndAuthenticate("all", McpProjectCredentials.READ);

        assertThrows(McpProjectFailure.class, () -> credentials.revoke("11", grant.credentialId(), issuer));
        assertThrows(McpProjectFailure.class,
                () -> credentials.revoke("all", grant.credentialId(), new CanvasActor(4L, 3L)));
        assertThrows(McpProjectFailure.class,
                () -> credentials.revoke("all", grant.credentialId(), new CanvasActor(2L, 4L)));
    }

    @Test
    void permanentCredentialSurvivesElapsedTimeAndStopsAfterRevocation() {
        var issued = credentials.issue("11", issuer, "永久只读", McpProjectCredentials.READ, 0);
        assertNull(issued.expiresAt());
        var captured = ArgumentCaptor.forClass(McpProjectCredential.class);
        verify(mapper).insert(captured.capture());
        var persisted = captured.getValue();
        assertEquals(persisted.getCreatedAt(), persisted.getExpiresAt());
        var past = Instant.now().minusSeconds(86_400);
        persisted.setCreatedAt(past);
        persisted.setExpiresAt(past);
        when(mapper.selectById(issued.credentialId())).thenReturn(persisted);
        String bearer = "Bearer " + issued.token();

        assertEquals(issued.credentialId(), credentials.authenticate(bearer).credentialId());
        persisted.setRevokedAt(Instant.now());
        assertEquals(401, assertThrows(McpProjectFailure.class, () -> credentials.authenticate(bearer)).getCode());
    }

    private McpProjectCredentials.Grant issueAndAuthenticate(String target, String scope) {
        when(mapper.insert(any(McpProjectCredential.class))).thenAnswer(invocation -> {
            McpProjectCredential row = invocation.getArgument(0);
            when(mapper.selectById(row.getId())).thenReturn(row);
            return 1;
        });
        var issued = credentials.issue(target, issuer, "临时验收", scope, 3600);
        assertEquals(target, issued.projectId());
        return credentials.authenticate("Bearer " + issued.token());
    }

    private CanvasException denied() {
        return new CanvasException(403, CanvasErrorCode.ACCESS_DENIED, "无权访问", Map.of());
    }
}
