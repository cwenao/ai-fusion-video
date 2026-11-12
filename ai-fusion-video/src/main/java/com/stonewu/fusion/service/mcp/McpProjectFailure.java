package com.stonewu.fusion.service.mcp;

import com.stonewu.fusion.common.BusinessException;
import lombok.Getter;

import java.util.Map;

@Getter
public class McpProjectFailure extends BusinessException {
    private final String errorCode;
    private final Map<String, Object> details;

    public McpProjectFailure(int status, String errorCode, String message, Map<String, Object> details) {
        super(status, message);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public static McpProjectFailure invalid(String field, String message) {
        return new McpProjectFailure(400, "INVALID_ARGUMENT", message, Map.of("field", field));
    }

    public static McpProjectFailure missing() {
        return new McpProjectFailure(404, "RESOURCE_NOT_FOUND", "资源不存在或在授权项目范围之外", Map.of());
    }

    public static McpProjectFailure denied() {
        return new McpProjectFailure(403, "ACCESS_DENIED", "项目凭证没有此操作权限", Map.of());
    }

    public static McpProjectFailure unauthorized() {
        return new McpProjectFailure(401, "UNAUTHORIZED", "项目凭证无效、过期或已撤销", Map.of());
    }
}
