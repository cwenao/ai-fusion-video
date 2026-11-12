package com.stonewu.fusion.repository.resource;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.mapper.resource.ResourceReadMapper;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.resource.StoryboardFields;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.*;

@Repository
@RequiredArgsConstructor
public class StoryboardReadRepository {
    private final ResourceReadMapper records;

    public Optional<Long> forProject(long project) {
        return Optional.ofNullable(records.currentStoryboard(project));
    }

    public List<Long> children(CanvasValueType type, CanvasValueType parentType, long parent) {
        requireRelation(type, parentType);
        return records.storyboardChildren(type, parentType, parent, null);
    }

    public IPage<Long> pageChildren(CanvasValueType type, CanvasValueType parentType, long parent, int page, int size) {
        requireRelation(type, parentType);
        var query = new Page<Long>(page, size);
        return query.setRecords(records.storyboardChildren(type, parentType, parent, query));
    }

    private void requireRelation(CanvasValueType type, CanvasValueType parentType) {
        boolean valid = switch (parentType) {
            case STORYBOARD -> List.of(STORYBOARD_EPISODE, STORYBOARD_SCENE, SHOT).contains(type);
            case STORYBOARD_EPISODE -> type == STORYBOARD_SCENE || type == SHOT;
            case STORYBOARD_SCENE -> type == SHOT;
            default -> false;
        };
        if (!valid) {
            throw StoryboardFields.invalid("分镜父子查询类型不匹配");
        }
    }

    public long count(CanvasValueType type, long board) {
        ResourceTables.of(type);
        return records.countStoryboardChildren(type, board);
    }
}
