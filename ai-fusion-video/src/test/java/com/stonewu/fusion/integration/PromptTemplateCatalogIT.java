package com.stonewu.fusion.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.system.vo.PromptTemplateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PromptTemplateCatalogIT {

    private static final String API = "/api/system/prompt-templates";
    private static final String CONFIG = "/api/system/config";
    private static final Path FILES = Path.of("target", "prompt-template-" + UUID.randomUUID()).toAbsolutePath();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("ai_fusion_video").withUsername("afv").withPassword("afv-test")
            .withCommand("--default-time-zone=+08:00");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("app.storage.local-base-path", FILES::toString);
        registry.add("app.agent-workspace.local-base-path", () -> FILES.resolve("workspace").toString());
    }

    @Autowired
    MockMvc http;

    @Autowired
    ObjectMapper json;

    @Test
    void administratorsManageTemplatesAndSignedInUsersSeeEveryChange() throws Exception {
        var admin = user("admin").roles("ADMIN");
        var member = user("member").roles("USER");
        var template = new PromptTemplateRequest("夜景模板", "scene", "## 🌕 月光\n\n- 色温\n\n- 反光  \n");
        String input = json.writeValueAsString(template);

        http.perform(get(API)).andExpect(status().isUnauthorized());
        http.perform(get(API).with(member)).andExpect(jsonPath("$.data.length()").value(0));
        http.perform(get(API).with(member)).andExpect(jsonPath("$.data.length()").value(0));
        http.perform(post(API).with(member).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isForbidden());

        var response = http.perform(post(API).with(admin).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isOk()).andReturn().getResponse();
        var id = json.readTree(response.getContentAsByteArray()).path("data").path("id").asText();
        http.perform(get(API).with(member))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].markdown").value(template.markdown()));
        http.perform(get(API).with(member))
                .andExpect(jsonPath("$.data[0].id").value(id));

        String path = API + "/" + id;
        var changed = new PromptTemplateRequest("通用夜景", "general", "## 月光与反射\n\n- 柔和侧光");
        String changedInput = json.writeValueAsString(changed);
        http.perform(put(path).with(member).contentType(MediaType.APPLICATION_JSON).content(changedInput))
                .andExpect(status().isForbidden());
        http.perform(delete(path).with(member)).andExpect(status().isForbidden());

        http.perform(put(CONFIG).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ASSET-PROMPT-TEMPLATE:" + id, "invalid"))))
                .andExpect(status().isBadRequest());
        http.perform(get(CONFIG).with(admin))
                .andExpect(jsonPath("$.data[?(@.configKey == 'asset-prompt-template:" + id + "')]").isEmpty());

        var invalid = new PromptTemplateRequest("无效模板", "unknown", "正文");
        http.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
        var overLimit = new PromptTemplateRequest("超长模板", "prop", "字".repeat(5001));
        http.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(overLimit)))
                .andExpect(status().isBadRequest());

        http.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(changedInput))
                .andExpect(status().isOk());
        http.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(changedInput))
                .andExpect(status().isOk());
        http.perform(get(API).with(member))
                .andExpect(jsonPath("$.data[0].name").value(changed.name()))
                .andExpect(jsonPath("$.data[0].category").value(changed.category()))
                .andExpect(jsonPath("$.data[0].markdown").value(changed.markdown()));

        http.perform(delete(path).with(admin)).andExpect(status().isOk());
        http.perform(get(API).with(member)).andExpect(jsonPath("$.data.length()").value(0));
        http.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isNotFound());
    }
}
