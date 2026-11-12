package com.stonewu.fusion.controller.project;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.service.canvas.CanvasActorResolver;
import com.stonewu.fusion.service.mcp.McpProjectCredentials;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
@RequestMapping("/api/project/{projectId}/mcp-credentials")
public class McpProjectCredentialController {
    private final CanvasActorResolver actors;
    private final McpProjectCredentials credentials;

    public record IssueRequest(@NotBlank @Size(max = 80) String label,
                               @Pattern(regexp = "read|read_write") String scope,
                               @Min(0) @Max(604800) Integer expiresInSeconds) {
    }

    public record RenameRequest(@NotBlank @Size(max = 80) String label) {
    }

    @PostMapping
    public ResponseEntity<CommonResult<McpProjectCredentials.Issued>> issue(@PathVariable String projectId,
                                                                            @Valid @RequestBody IssueRequest request) {
        int ttl = request.expiresInSeconds() == null ? 86_400 : request.expiresInSeconds();
        var issued = credentials.issue(projectId, actors.current(), request.label(), request.scope(), ttl);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResult.success(issued));
    }

    @GetMapping
    public CommonResult<List<McpProjectCredentials.Metadata>> list(@PathVariable String projectId) {
        return CommonResult.success(credentials.list(projectId, actors.current()));
    }

    @PatchMapping("/{credentialId}")
    public CommonResult<Boolean> rename(@PathVariable String projectId, @PathVariable String credentialId,
                                        @Valid @RequestBody RenameRequest request) {
        credentials.rename(projectId, credentialId, actors.current(), request.label());
        return CommonResult.success(true);
    }

    @DeleteMapping("/{credentialId}")
    public CommonResult<Boolean> revoke(@PathVariable String projectId, @PathVariable String credentialId) {
        credentials.revoke(projectId, credentialId, actors.current());
        return CommonResult.success(true);
    }
}
