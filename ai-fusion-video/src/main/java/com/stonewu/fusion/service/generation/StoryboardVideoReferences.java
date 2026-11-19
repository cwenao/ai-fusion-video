package com.stonewu.fusion.service.generation;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.mapper.project.ProjectMapper;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService.VideoModelCapability;
import com.stonewu.fusion.service.project.ProjectArtStyleInfo;
import com.stonewu.fusion.service.resource.model.ResourceSnapshot;
import com.stonewu.fusion.service.storyboard.StoryboardAssetReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StoryboardVideoReferences {
    private final StoryboardAssetReferences assets;
    private final ProjectMapper projects;
    private final ProjectArtStyleInfo artStyles;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Images resolve(ResourceSnapshot shot, CanvasActor actor, VideoModelCapability model,
                          boolean exclusiveInputModes) {
        var data = shot.data();
        String first = model.supportsFirstFrame() ? assets.mediaSource(data.path("firstFrameImageUrl").textValue()) : null;
        String last = first != null && model.supportsLastFrame()
                ? assets.mediaSource(data.path("lastFrameImageUrl").textValue()) : null;
        if (!model.supportsReferenceImages() || (first != null && exclusiveInputModes)) {
            return new Images(first, last, List.of());
        }

        long projectId = shot.header().projectId();
        var bindings = assets.resolve(data, projectId, actor, new HashMap<>());
        var project = projects.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "镜头所属项目已删除");
        }
        String styleImage = artStyles.describe(project).getStr("referenceImageUrl");
        List<String> ordered = new ArrayList<>();
        if (styleImage != null) {
            ordered.add(styleImage);
        }
        for (var item : bindings.ordered()) {
            long itemId = item.path("assetItemId").asLong();
            if (item.path("unavailable").asBoolean()) {
                throw new BusinessException(400, "镜头关联子资产 #" + itemId + " 已失效，请检查镜头绑定");
            }
            String source = assets.mediaSource(item.path("imageUrl").textValue());
            if (source == null) {
                throw new BusinessException(400, "镜头关联子资产 #" + itemId + " 没有可用参考图");
            }
            ordered.add(source);
        }
        return new Images(first, last, List.copyOf(ordered));
    }

    public record Images(String first, String last, List<String> references) { }
}
