package com.stonewu.fusion.service.ai.catalog;

import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.ModelPresetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 展示目录里经核对的模型，避免将供应商的文本模型列表当成多模态模型清单。 */
@Component
@RequiredArgsConstructor
public class PresetModelDiscovery implements ProviderModelDiscovery {
    private final ModelPresetService presets;

    @Override
    public String type() {
        return "preset";
    }

    @Override
    public ProviderDiscoveryResult discover(ApiConfig config, ProviderDefinition provider) {
        var models = provider.modelPresetCodes().stream().map(code -> {
            var preset = presets.getPreset(code);
            if (preset == null) {
                throw new IllegalStateException("提供商引用的模型预设不存在: " + code);
            }
            return new ProviderModelCandidate(preset.getStr("modelCode", code), preset.getStr("name"),
                    preset.getInt("modelType"), preset.getStr("modelProtocol"), code, "{}", null);
        }).toList();
        return new ProviderDiscoveryResult(provider.id(), false, "以下为官方模型预设，调用权限以当前 API Key 为准。", models);
    }
}
