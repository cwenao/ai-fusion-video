package com.stonewu.fusion.entity.project;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("afv_mcp_project_credential")
public class McpProjectCredential {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long projectId;
    private Long userId;
    private Long teamId;
    private String label;
    private String scope;
    private String secretDigest;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
}
