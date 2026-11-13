package com.stonewu.fusion.service.generation.review;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.enums.ai.AiModelTypeEnum;
import com.stonewu.fusion.service.ai.AiModelMultimodalCapabilities;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.agentscope.kernel.OwnedChatModel;
import com.stonewu.fusion.service.ai.provider.AiProviderService;
import com.stonewu.fusion.service.generation.GenerationKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.stonewu.fusion.service.ai.AiModelMultimodalCapabilities.TRANSPORT_BASE64;

@Component
@RequiredArgsConstructor
public class GenerationReviewModels {
    private final AiModelService models;
    private final AiProviderService providers;

    public record Option(long id, String name) {
    }

    public List<Option> options(GenerationKind kind) {
        return models.getListByType(AiModelTypeEnum.CHAT.getType()).stream()
                .filter(model -> available(model, kind))
                .sorted(Comparator.comparing(AiModel::getName).thenComparing(AiModel::getId))
                .map(model -> new Option(model.getId(), model.getName())).toList();
    }

    public GenerationContentReview.Model freeze(long id, GenerationKind kind) {
        var model = require(id, kind);
        return new GenerationContentReview.Model(id, model.getName(), providers.agentScopeModelFingerprint(model));
    }

    public OwnedChatModel provision(GenerationContentReview.Model expected, GenerationKind kind) {
        var model = require(expected.id(), kind);
        if (!expected.fingerprint().equals(providers.agentScopeModelFingerprint(model))) {
            throw changed();
        }
        var provision = providers.provisionSingleRequestModel(model);
        var owned = OwnedChatModel.owned(provision.model());
        if (!expected.fingerprint().equals(provision.modelConfigFingerprint())) {
            owned.close();
            throw changed();
        }
        return owned;
    }

    private boolean available(AiModel model, GenerationKind kind) {
        try {
            validate(model, kind);
            return true;
        } catch (BusinessException unavailable) {
            return false;
        }
    }

    private AiModel require(long id, GenerationKind kind) {
        var model = models.getById(id);
        validate(model, kind);
        return model;
    }

    private void validate(AiModel model, GenerationKind kind) {
        String mediaType = kind.name().toLowerCase(Locale.ROOT);
        AiModelMultimodalCapabilities.validateUpload(model, mediaType + "/" + (kind == GenerationKind.IMAGE
                ? "png" : "mp4"), TRANSPORT_BASE64);
        if (kind == GenerationKind.VIDEO && !providers.supportsNativeVideoInput(model)) {
            throw new BusinessException(400, "所选审查模型的请求协议不能读取完整视频");
        }
    }

    private BusinessException changed() {
        return new BusinessException(409, "审查模型配置已变化，请重新选择模型后生成");
    }
}
