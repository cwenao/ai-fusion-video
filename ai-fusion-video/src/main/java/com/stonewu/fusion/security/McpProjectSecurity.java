package com.stonewu.fusion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.config.McpProjectProperties;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.mcp.McpProjectCredentials;
import com.stonewu.fusion.service.mcp.McpProjectCredentials.Grant;
import com.stonewu.fusion.service.mcp.McpProjectFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProjectProperties.class)
public class McpProjectSecurity {
    public static final String ENDPOINT = "/api/mcp";
    private static final String GRANT_ATTRIBUTE = McpProjectSecurity.class.getName() + ".grant";
    private static final String MEDIA_PREFIX = ENDPOINT + "/projects/";
    private static final Pattern MEDIA_PATH = Pattern.compile(MEDIA_PREFIX + "([1-9][0-9]{0,18})/media(?:/.*)?");

    public static boolean matches(HttpServletRequest request) {
        String path = path(request);
        return path.equals(ENDPOINT) || path.startsWith(ENDPOINT + "/");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static long mediaProjectId(String path) {
        var match = MEDIA_PATH.matcher(path);
        if (!match.matches()) {
            throw McpProjectFailure.denied();
        }
        try {
            return Long.parseLong(match.group(1));
        } catch (NumberFormatException invalidId) {
            throw McpProjectFailure.denied();
        }
    }

    public static Grant grant(HttpServletRequest request) {
        return (Grant) request.getAttribute(GRANT_ATTRIBUTE);
    }

    @Bean
    @Order(1)
    SecurityFilterChain projectMcpSecurity(HttpSecurity http, McpProjectProperties properties,
                                           ObjectProvider<McpProjectCredentials> credentials,
                                           ObjectProvider<StringRedisTemplate> redis, ObjectMapper json) throws Exception {
        return http.securityMatcher(McpProjectSecurity::matches)
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new ProjectFilter(properties, credentials, redis, json),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @RequiredArgsConstructor
    private static final class ProjectFilter extends OncePerRequestFilter {
        private static final DefaultRedisScript<Long> COUNT_REQUEST = new DefaultRedisScript<>("""
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then redis.call('EXPIRE', KEYS[1], 70) end
                return count
                """, Long.class);

        private final McpProjectProperties properties;
        private final ObjectProvider<McpProjectCredentials> credentials;
        private final ObjectProvider<StringRedisTemplate> redis;
        private final ObjectMapper json;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!properties.isEnabled()) {
                error(response, 404, "RESOURCE_NOT_FOUND", "项目 MCP 服务未启用");
                return;
            }
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (properties.getAllowedHosts().stream().noneMatch(request.getServerName()::equalsIgnoreCase)
                    || (origin != null && !properties.getAllowedOrigins().contains(origin))) {
                error(response, 403, "ACCESS_DENIED", "MCP 请求主机或来源未获授权");
                return;
            }
            Grant grant;
            try {
                var service = credentials.getObject();
                grant = service.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
                String path = path(request);
                if (path.startsWith(MEDIA_PREFIX)) {
                    service.requireProject(grant, mediaProjectId(path), "POST".equals(request.getMethod()));
                } else {
                    service.requireConnection(grant);
                }
            } catch (McpProjectFailure failure) {
                if (failure.getCode() == 401) {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"fusion-project-mcp\"");
                }
                error(response, failure.getCode(), failure.getErrorCode(), failure.getMessage());
                return;
            } catch (CanvasException denied) {
                error(response, 403, "ACCESS_DENIED", "项目成员权限已变化");
                return;
            }
            long seconds = Instant.now().getEpochSecond();
            String key = "fusion:project-mcp:limit:" + grant.credentialId() + ":" + seconds / 60;
            Long count = redis.getObject().execute(COUNT_REQUEST, List.of(key));
            if (count == null) {
                throw new IllegalStateException("Redis 未返回 MCP 限流计数");
            }
            if (count > properties.getRequestsPerMinute()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(60 - seconds % 60));
                error(response, 429, "RATE_LIMITED", "MCP 请求频率超出项目凭证限制");
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(grant, null, List.of()));
            SecurityContextHolder.setContext(context);
            request.setAttribute(GRANT_ATTRIBUTE, grant);
            chain.doFilter(request, response);
        }

        private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            json.writeValue(response.getWriter(), Map.of("ok", false,
                    "error", Map.of("code", code, "message", message, "details", Map.of())));
        }
    }
}
