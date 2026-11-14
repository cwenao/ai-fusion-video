package com.stonewu.fusion.service.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.system.vo.PromptTemplateRequest;
import com.stonewu.fusion.entity.system.SystemConfig;
import com.stonewu.fusion.mapper.system.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    static final String KEY_PREFIX = "asset-prompt-template:";
    private static final String CACHE = "assetPromptTemplates";

    private final SystemConfigMapper mapper;
    private final ObjectMapper json;

    public record Template(String id, String name, String category, String markdown) {
    }

    @Cacheable(value = CACHE, key = "'catalog'")
    public List<Template> list() {
        var rows = mapper.selectList(Wrappers.<SystemConfig>lambdaQuery()
                .likeRight(SystemConfig::getConfigKey, KEY_PREFIX)
                .orderByAsc(SystemConfig::getId));
        var templates = new ArrayList<Template>(rows.size());
        for (SystemConfig row : rows) {
            templates.add(read(row));
        }
        return templates;
    }

    @CacheEvict(value = CACHE, key = "'catalog'")
    public Template create(PromptTemplateRequest request) {
        var template = new Template(UUID.randomUUID().toString(), request.name().strip(), request.category(), request.markdown());
        mapper.insert(SystemConfig.builder()
                .configKey(KEY_PREFIX + template.id())
                .configValue(write(template))
                .build());
        return template;
    }

    @CacheEvict(value = CACHE, key = "'catalog'")
    public Template update(String id, PromptTemplateRequest request) {
        var template = new Template(id, request.name().strip(), request.category(), request.markdown());
        int updated = mapper.update(Wrappers.<SystemConfig>lambdaUpdate()
                .eq(SystemConfig::getConfigKey, key(id))
                .set(SystemConfig::getConfigValue, write(template)));
        requireFound(updated);
        return template;
    }

    @CacheEvict(value = CACHE, key = "'catalog'")
    public void delete(String id) {
        int deleted = mapper.delete(Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, key(id)));
        requireFound(deleted);
    }

    static boolean ownsKey(String key) {
        return key.toLowerCase(Locale.ROOT).startsWith(KEY_PREFIX);
    }

    private static String key(String id) {
        try {
            if (UUID.fromString(id).toString().equals(id)) {
                return KEY_PREFIX + id;
            }
        } catch (IllegalArgumentException ignored) {
            // 非模板标识。
        }
        throw new BusinessException(404, "提示词模板不存在");
    }

    private static void requireFound(int affected) {
        if (affected == 0) {
            throw new BusinessException(404, "提示词模板不存在");
        }
    }

    private Template read(SystemConfig row) {
        try {
            return json.readValue(row.getConfigValue(), Template.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("提示词模板数据无法读取", e);
        }
    }

    private String write(Template template) {
        try {
            return json.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("提示词模板数据无法保存", e);
        }
    }
}
