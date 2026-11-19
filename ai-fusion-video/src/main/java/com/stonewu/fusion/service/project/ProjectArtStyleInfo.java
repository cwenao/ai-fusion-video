package com.stonewu.fusion.service.project;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.config.ArtStylePresets;
import com.stonewu.fusion.entity.project.Project;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectArtStyleInfo {
    private final SystemConfigService systemConfigService;

    /**
     * 构建画风信息：从数据库字段读取，预设则补充静态信息，并通过 resolvePublicUrl 生成完整 URL
     */
    public JSONObject describe(Project project) {
        String artStyleKey = project.getArtStyle();
        JSONObject info = JSONUtil.createObj();

        if (StrUtil.isBlank(artStyleKey)) {
            info.set("hasArtStyle", false);
            info.set("description", "高质量精细画面");
            info.set("imagePrompt", "High quality, detailed illustration, professional artwork");
            return info;
        }

        info.set("hasArtStyle", true);
        info.set("styleKey", artStyleKey);

        String refImagePath;

        if (!"custom".equals(artStyleKey)) {
            // 预设画风
            ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
            if (preset != null) {
                info.set("name", preset.getName());
                info.set("description", preset.getDescription());
                info.set("imagePrompt", preset.getImagePrompt());
                info.set("isPreset", true);
                // 优先从系统配置获取预设的公网 URL，否则回退到本地静态路径
                String presetPublicUrl = systemConfigService.getValue("art_preset_url:" + artStyleKey);
                refImagePath = StrUtil.isNotBlank(presetPublicUrl)
                        ? presetPublicUrl
                        : preset.getReferenceImagePath();
            } else {
                info.set("name", artStyleKey);
                info.set("description", "高质量精细画面");
                info.set("imagePrompt", "高质量精细画面，专业级插画，细节丰富");
                info.set("isPreset", false);
                refImagePath = null;
            }
        } else {
            // 自定义画风
            info.set("name", "自定义画风");
            info.set("description", StrUtil.blankToDefault(project.getArtStyleDescription(), "高质量精细画面"));
            info.set("imagePrompt", StrUtil.blankToDefault(project.getArtStyleImagePrompt(),
                    "High quality, detailed illustration, professional artwork"));
            info.set("isPreset", false);
            refImagePath = project.getArtStyleImageUrl();
        }

        // 解析参考图为完整公网 URL
        String publicUrl = (MediaReference.managed(refImagePath) ? refImagePath : systemConfigService.resolvePublicUrl(
                refImagePath));
        info.set("referenceImageUrl", publicUrl);
        info.set("referenceImageAvailable", publicUrl != null);

        if (publicUrl == null && StrUtil.isNotBlank(refImagePath)) {
            info.set("referenceImageWarning",
                    "参考图无法通过网络访问，请在系统设置中配置后端资源公网地址或启用对象存储");
        }

        return info;
    }
}
