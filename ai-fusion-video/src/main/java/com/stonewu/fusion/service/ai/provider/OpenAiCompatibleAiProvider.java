package com.stonewu.fusion.service.ai.provider;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.controller.ai.vo.RemoteModelVO;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容提供商。
 */
@Component
@Slf4j
public class OpenAiCompatibleAiProvider extends AbstractAiProvider {

    private static final String MINIMAX_CHAT = "minimax_chat";
    private static final String MINIMAX_FLASH = "MiniMax-M3.1-Flash";
    private static final Set<String> MINIMAX_EFFORT_LEVELS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of(
            "openai_compatible", "openai", "deepseek", "zhipu", "moonshot", "volcengine", "siliconflow",
            "newapi", MINIMAX_CHAT);

    @Override
    public boolean supports(String platform) {
        return platform != null && SUPPORTED_PLATFORMS.contains(platform.toLowerCase());
    }

    @Override
    public ChatModel createChatModel(AiProviderContext context) {
        String platform = context.getPlatform();
        String apiKey = context.getApiKey();
        String baseUrl = resolveRootBaseUrl(platform, context.getBaseUrl());
        String completionsPath = resolveCompletionsPath(context);
        String embeddingsPath = resolveEmbeddingsPath(context);
        Map<String, Object> config = context.getConfig();
        String modelName = context.getModelName();

        requireApiKey(apiKey, "OpenAI Compatible (" + platform + ")");

        if (shouldUseResponsesApi(context)) {
            log.warn(
                    "[OpenAiCompatibleAiProvider] Responses API 目前仅接入 AgentScope 主链路，Spring AI ChatModel 仍回退到 chat/completions: model={}",
                    context.getModelName());
        }

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        apiBuilder.restClientBuilder(AiProxySupport.restClientBuilder(
                context.getApiConfig(), 60 * 1000, 25 * 60 * 1000));
        apiBuilder.webClientBuilder(AiProxySupport.webClientBuilder(
                context.getApiConfig(), "openai-compatible-provider", Duration.ofMinutes(25)));
        if (StrUtil.isNotBlank(baseUrl)) {
            apiBuilder.baseUrl(baseUrl);
        }
        apiBuilder.completionsPath(completionsPath);
        apiBuilder.embeddingsPath(embeddingsPath);

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(modelName);
        if (MINIMAX_CHAT.equals(platform)) {
            optionsBuilder.temperature(1.0).topP(0.95)
                    .maxCompletionTokens(miniMaxMaxTokens(context)).reasoningEffort(miniMaxEffort(context));
        } else {
            applyDouble(config, "temperature", optionsBuilder::temperature);
            applyDouble(config, "topP", optionsBuilder::topP);
            applyInt(config, "maxTokens", optionsBuilder::maxTokens);
        }

        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    @Override
    public ChatModelBase createAgentScopeModel(AiProviderContext context) {
        String platform = context.getPlatform();
        String apiKey = context.getApiKey();
        String baseUrl = resolveRootBaseUrl(platform, context.getBaseUrl());
        String endpointPath = resolveCompletionsPath(context);

        requireApiKey(apiKey, "OpenAI Compatible (" + platform + ")");

        boolean miniMax = MINIMAX_CHAT.equals(platform);
        GenerateOptions generateOptions = miniMax ? miniMaxOptions(context) : buildGenerateOptions(context);
        if (shouldUseResponsesApi(context)) {
            return new OpenAiResponsesAgentScopeModel(
                    context.getApiConfig(),
                    apiKey,
                    baseUrl,
                    context.getModelName(),
                    generateOptions,
                    context.isSingleRequest());
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(context.getModelName())
                .stream(true);
        if (miniMax) {
            builder.contextWindowSize(1_000_000).nativeStructuredOutput(false);
        }
        if (context.isSingleRequest()) {
            builder.formatter(new StreamingOpenAiFormatter(miniMax ? new MiniMaxFormatter() : null));
            generateOptions = SingleRequestModelSupport.options(generateOptions);
        } else if (miniMax) {
            builder.formatter(new MiniMaxFormatter());
        }
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }
        if (StrUtil.isNotBlank(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        builder.endpointPath(endpointPath);
        HttpTransport proxyTransport = SingleRequestModelSupport.transport(context);
        if (proxyTransport != null) {
            builder.httpTransport(proxyTransport);
        }
        var model = builder.build();
        return context.isSingleRequest() ? SingleRequestModelSupport.own(model, proxyTransport) : model;
    }

    @Override
    public List<RemoteModelVO> listRemoteModels(AiProviderContext context) {
        String rootBaseUrl = resolveRootBaseUrl(context.getPlatform(), context.getBaseUrl());
        String url = joinUrl(rootBaseUrl, resolveModelsPath(context));

        log.info("[OpenAiCompatibleAiProvider] 获取远程模型列表: {}", url);
        String response = executeGet(url, context.getApiKey() == null
                ? Map.of()
                : Map.of("Authorization", "Bearer " + context.getApiKey()), context.getApiConfig());
        return parseDataArrayModels(response, context.getPlatform());
    }

    private GenerateOptions buildGenerateOptions(AiProviderContext context) {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        boolean hasOptions = false;

        Double temperature = getConfigDoubleValue(context.getConfig(), "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
            hasOptions = true;
        }

        Double topP = getConfigDoubleValue(context.getConfig(), "topP", "top_p");
        if (topP != null) {
            builder.topP(topP);
            hasOptions = true;
        }

        Integer maxTokens = getConfigInteger(context.getConfig(), "maxTokens", "max_tokens");
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
            builder.maxCompletionTokens(maxTokens);
            hasOptions = true;
        }

        String reasoningEffort = getConfigString(context.getConfig(), "reasoningEffort", "reasoning_effort");
        if (StrUtil.isNotBlank(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
            hasOptions = true;
        }

        Integer thinkingBudget = getConfigInteger(context.getConfig(), "thinkingBudget", "thinking_budget");
        if (thinkingBudget != null) {
            builder.thinkingBudget(thinkingBudget);
            hasOptions = true;
        }

        Boolean includeReasoning = getConfigBoolean(context.getConfig(), "includeReasoning", "include_reasoning");
        if (includeReasoning == null && isReasoningEnabled(context)) {
            includeReasoning = true;
        }
        if (includeReasoning != null) {
            builder.additionalBodyParam("include_reasoning", includeReasoning);
            hasOptions = true;
        }

        return hasOptions ? builder.build() : null;
    }

    private GenerateOptions miniMaxOptions(AiProviderContext context) {
        var builder = GenerateOptions.builder().maxCompletionTokens(miniMaxMaxTokens(context));
        String effort = miniMaxEffort(context);
        if (effort != null) {
            // SDK 的 MiniMax 格式化器仍按旧模型移除类型化字段，官网 Flash 支持该参数。
            builder.additionalBodyParam("reasoning_effort", effort);
        }
        return builder.build();
    }

    private int miniMaxMaxTokens(AiProviderContext context) {
        Object value = getConfigValue(context.getConfig(), "maxTokens", "max_completion_tokens");
        if (value == null) {
            return 131_072;
        }
        try {
            int tokens = new BigDecimal(value.toString()).intValueExact();
            if (tokens >= 1 && tokens <= 524_288) {
                return tokens;
            }
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new BusinessException("MiniMax 最大输出 token 数须为 1 至 524288 的整数");
        }
        throw new BusinessException("MiniMax 最大输出 token 数须为 1 至 524288 的整数");
    }

    private String miniMaxEffort(AiProviderContext context) {
        if (!MINIMAX_FLASH.equals(context.getModelName())) {
            return null;
        }
        String value = StrUtil.blankToDefault(
                getConfigString(context.getConfig(), "reasoningEffort", "reasoning_effort"), "max");
        if (!MINIMAX_EFFORT_LEVELS.contains(value)) {
            throw new BusinessException("MiniMax 思考深度须为 low、medium、high、xhigh 或 max");
        }
        return value;
    }

    boolean shouldUseResponsesApi(AiProviderContext context) {
        if (MINIMAX_CHAT.equals(context.getPlatform())) {
            return false;
        }
        Boolean useResponsesApi = getConfigBoolean(context.getConfig(),
                "useResponsesApi", "useResponses", "responseApi", "responsesApi");
        if (useResponsesApi != null) {
            return useResponsesApi;
        }

        String apiMode = getConfigString(context.getConfig(),
                "apiMode", "api_mode", "openaiApiMode", "openai_api_mode");
        if (StrUtil.isBlank(apiMode)) {
            return false;
        }

        String normalized = apiMode.trim().toLowerCase();
        return "responses".equals(normalized) || "response".equals(normalized);
    }

    private Double getConfigDoubleValue(Map<String, Object> config, String... keys) {
        Object value = getConfigValue(config, keys);
        if (value == null) {
            return null;
        }
        try {
            return toDouble(value);
        } catch (Exception e) {
            log.warn("[OpenAiCompatibleAiProvider] 参数解析失败: keys={}, value={}", String.join(",", keys), value);
            return null;
        }
    }

    private String resolveCompletionsPath(AiProviderContext context) {
        return switch (context.getPlatform().toLowerCase()) {
            case "zhipu" -> "/api/paas/v4/chat/completions";
            case "volcengine" -> "/api/v3/chat/completions";
            default -> shouldAutoAppendV1Path(context) ? "/v1/chat/completions" : "/chat/completions";
        };
    }

    private String resolveEmbeddingsPath(AiProviderContext context) {
        return switch (context.getPlatform().toLowerCase()) {
            case "zhipu" -> "/api/paas/v4/embeddings";
            case "volcengine" -> "/api/v3/embeddings";
            default -> shouldAutoAppendV1Path(context) ? "/v1/embeddings" : "/embeddings";
        };
    }

    private String resolveModelsPath(AiProviderContext context) {
        return switch (context.getPlatform().toLowerCase()) {
            case "zhipu" -> "/api/paas/v4/models";
            case "volcengine" -> "/api/v3/models";
            default -> shouldAutoAppendV1Path(context) ? "/v1/models" : "/models";
        };
    }

    private String resolveRootBaseUrl(String platform, String baseUrl) {
        if (MINIMAX_CHAT.equals(platform)) {
            if (StrUtil.isBlank(baseUrl)) {
                throw new BusinessException("请填写 MiniMax API 地址");
            }
            String root = normalizeBaseUrl(baseUrl.trim());
            return root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
        }
        return StrUtil.isBlank(baseUrl) ? inferRootBaseUrl(platform) : normalizeBaseUrl(baseUrl);
    }

    private boolean shouldAutoAppendV1Path(AiProviderContext context) {
        if (!"openai_compatible".equalsIgnoreCase(context.getPlatform())) {
            return true;
        }
        ApiConfig apiConfig = context.getApiConfig();
        return apiConfig == null || !Boolean.FALSE.equals(apiConfig.getAutoAppendV1Path());
    }

    private String inferRootBaseUrl(String platform) {
        return switch (platform.toLowerCase()) {
            case "deepseek" -> "https://api.deepseek.com";
            case "zhipu" -> "https://open.bigmodel.cn";
            case "volcengine" -> "https://ark.cn-beijing.volces.com";
            case "moonshot" -> "https://api.moonshot.cn";
            case "siliconflow" -> "https://api.siliconflow.cn";
            case "newapi" -> "https://docs.newapi.ai";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.openai.com";
        };
    }
}
