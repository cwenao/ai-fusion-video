CREATE TABLE afv_mcp_project_credential (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '凭证编号',
    project_id BIGINT NOT NULL COMMENT '授权项目',
    user_id BIGINT NOT NULL COMMENT '签发用户',
    team_id BIGINT NULL COMMENT '签发时团队，个人范围为空',
    label VARCHAR(80) NOT NULL COMMENT '凭证用途',
    scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '读写范围',
    secret_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密钥摘要',
    created_at TIMESTAMP(6) NOT NULL COMMENT '签发时间',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '到期时间',
    revoked_at TIMESTAMP(6) NULL COMMENT '撤销时间',
    PRIMARY KEY (id),
    KEY idx_mcp_credential_project_user (project_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 MCP 项目凭证';
