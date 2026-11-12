package com.stonewu.fusion.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@Getter
@Setter
@Validated
@ConfigurationProperties("app.project-mcp")
public class McpProjectProperties {
    private boolean enabled;
    @NotEmpty
    private Set<String> allowedHosts = Set.of("localhost", "127.0.0.1");
    private Set<String> allowedOrigins = Set.of();
    @Positive
    private int requestsPerMinute = 120;
}
