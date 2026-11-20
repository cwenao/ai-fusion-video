package com.stonewu.fusion.service.resource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.mapper.resource.ResourceRevisionMapper;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasErrorCode;
import com.stonewu.fusion.service.canvas.model.CanvasException;
import com.stonewu.fusion.service.resource.model.ResourceCommandReceipt;
import com.stonewu.fusion.service.storyboard.dto.StoryboardPromptVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.entity.resource.ResourceCommandEntity;
import com.stonewu.fusion.mapper.resource.ResourceCommandMapper;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Map;

/**
 * 只缓存不可变内容的 JSON 字符串；权限仍在每次历史读取和恢复前重新判断。
 */
@Component
@RequiredArgsConstructor
public class ResourceHistoryCache {
    private final ResourceCommandMapper commands;
    private final ResourceRevisionMapper revisions;
    private final CanvasJson json;

    @Cacheable(value = "resourceDocument", key = "'history:v1:' + #commandId")
    public String source(String commandId) {
        var row = commands.selectOne(new LambdaQueryWrapper<ResourceCommandEntity>()
                .eq(ResourceCommandEntity::getId, commandId).isNotNull(ResourceCommandEntity::getCommittedAt));
        if (row == null) {
            throw new CanvasException(404, CanvasErrorCode.RESOURCE_DELETED, "业务操作记录不存在", Map.of());
        }
        ObjectNode request = json.read(row.getRequestJson(), ObjectNode.class);
        return json.write(new ResourceHistoryService.Source(row.getId(), row.getActorUserId(), row.getOrigin(),
                row.getCanvasId(), row.getAgentRunId(), row.getToolCallId(), request.path("operation").asText(),
                json.read(row.getResultJson(), ResourceCommandReceipt.class), request,
                json.read(row.getUndoJson(), ObjectNode.class), row.getCommittedAt()));
    }

    @Cacheable(value = "resourceDocument", key = "'shot-prompt:v1:' + #id + ':' + #field + ':' + #version")
    public String shotPromptHistory(long id, String field, int version) {
        return json.write(revisions.shotPromptHistory(id, field, version).stream()
                .map(row -> new StoryboardPromptVersion(row.version(), row.prompt(), row.replacedAt().toInstant(ZoneOffset.UTC)))
                .toList());
    }
}
