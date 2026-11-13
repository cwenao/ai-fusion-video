package com.stonewu.fusion.service.generation;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import lombok.RequiredArgsConstructor;
import com.stonewu.fusion.mapper.generation.GenerationSubmissionMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 只投影执行状态，既不修改缓存实体，也不暴露执行身份和供应商配置。
 */
@Component
@RequiredArgsConstructor
public class GenerationTaskViews {
    private final GenerationSubmissionMapper submissions;

    public View<ImageTask> image(ImageTask task, CanvasActor actor) {
        return new View<>(task, summaries(GenerationKind.IMAGE, List.of(task.getId()), actor).get(task.getId()));
    }

    public View<VideoTask> video(VideoTask task, CanvasActor actor) {
        return new View<>(task, summaries(GenerationKind.VIDEO, List.of(task.getId()), actor).get(task.getId()));
    }

    public PageResult<View<ImageTask>> images(PageResult<ImageTask> page, CanvasActor actor) {
        var states = summaries(GenerationKind.IMAGE, page.getList().stream().map(ImageTask::getId).toList(), actor);
        return page.map(task -> new View<>(task, states.get(task.getId())));
    }

    public PageResult<View<VideoTask>> videos(PageResult<VideoTask> page, CanvasActor actor) {
        var states = summaries(GenerationKind.VIDEO, page.getList().stream().map(VideoTask::getId).toList(), actor);
        return page.map(task -> new View<>(task, states.get(task.getId())));
    }

    private Map<Long, Execution> summaries(GenerationKind kind, List<Long> ids, CanvasActor actor) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Long, Execution>();
        submissions.taskSummaries(kind.name(), ids)
                .forEach(row -> result.put(row.taskId(), new Execution(row.id(), row.state(),
                        row.cancelRequested(), actor.userId() == row.actorUserId(), row.reviewRequested())));
        return result;
    }

    public record View<T>(@JsonUnwrapped T task, Execution execution) {
    }

    public record Execution(String id, String state, boolean cancelRequested, boolean canControl,
                            boolean reviewRequested) {
    }
}
