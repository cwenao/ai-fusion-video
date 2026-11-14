package com.stonewu.fusion.service.generation.image.strategy.minimax;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.image.strategy.ImageGenerationStrategy;
import com.stonewu.fusion.service.generation.job.GenerationPreparedRequest;
import com.stonewu.fusion.service.generation.job.GenerationProviderReceipt;
import com.stonewu.fusion.service.generation.job.PreparedGenerationCall;
import com.stonewu.fusion.service.generation.job.RemoteGenerationResult;
import com.stonewu.fusion.service.generation.transport.GenerationHttpClients;
import com.stonewu.fusion.service.generation.transport.OfficialVideoHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public final class MiniMaxImageStrategy implements ImageGenerationStrategy {
    private static final Set<String> AREAS = Set.of("1K", "2K", "4K");
    private static final Set<String> FORMATS = Set.of("jpeg", "jpg", "png", "webp");
    private static final Pattern SIZE = Pattern.compile("([1-9]\\d{0,4})[xX]([1-9]\\d{0,4})");
    private static final Pattern RATIO = Pattern.compile("([1-9]\\d{0,2}):([1-9]\\d{0,2})");
    private static final int MAX_COUNT = 10;

    private final GenerationModelCapabilityService capabilities;

    @Override
    public String getName() {
        return "minimax_h3_image";
    }

    @Override
    public int batchSize(ImageTask task, AiModel model, ApiConfig apiConfig) {
        return Math.min(task.getCount(), MAX_COUNT);
    }

    @Override
    public PreparedGenerationCall prepare(ImageTask task, AiModel model, ApiConfig config) {
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new BusinessException("请配置 MiniMax API Key");
        }
        var body = buildRequest(task, model.getCode(), capabilities.getMergedModelConfig(model));
        String action = body.containsKey("images") ? "edits" : "generations";
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > 64 * 1024 * 1024) {
            throw new BusinessException("MiniMax 图片请求超过 64 MiB，请减少参考图或使用公网地址");
        }
        String url = OfficialVideoHttpClient.baseUrl(config, "/v1") + "/images/" + action;
        var client = GenerationHttpClients.restClientBuilder(config).build();
        return new PreparedGenerationCall(GenerationPreparedRequest.ordinary(), () -> {
            GenerationHttpClients.requireNoTransaction();
            String response = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(config.getApiKey())).body(payload)
                    .retrieve().body(String.class);
            return GenerationProviderReceipt.completed(result(JSONUtil.parseObj(response)));
        });
    }

    @Override
    public RemoteGenerationResult query(String taskId, ImageTask task, AiModel model, ApiConfig config) {
        throw new BusinessException("MiniMax 同步图片结果必须从已保存回执读取");
    }

    static JSONObject buildRequest(ImageTask task, String modelCode, JSONObject options) {
        String prompt = StrUtil.trimToEmpty(task.getPrompt());
        if (prompt.isEmpty() || prompt.codePointCount(0, prompt.length()) > 32_000) {
            throw new BusinessException("MiniMax 图片提示词须包含 1 至 32000 个字符");
        }
        var body = new JSONObject().set("model", modelCode).set("prompt", prompt)
                .set("n", task.getCount()).set("response_format", "url");
        String references = task.getRefImageUrls();
        var images = StrUtil.isBlank(references) ? new JSONArray() : JSONUtil.parseArray(references);
        if (images.size() > 16) {
            throw new BusinessException("MiniMax 图片编辑最多支持 16 张参考图");
        }
        if (!images.isEmpty()) {
            body.set("images", images.toList(String.class).stream()
                    .map(url -> new JSONObject().set("image_url", url)).toList());
        }
        applySize(body, task);
        if (options.containsKey("outputFormat")) {
            String format = options.getStr("outputFormat");
            if (format == null || !FORMATS.contains(format)) {
                throw new BusinessException("MiniMax 图片输出格式须为 jpeg、jpg、png 或 webp");
            }
            body.set("output_format", format);
        }
        if (images.isEmpty() && Boolean.TRUE.equals(options.getBool("disablePromptEnhanced"))) {
            if ("auto".equals(body.getStr("size")) && "auto".equals(body.getStr("aspect_ratio"))) {
                throw new BusinessException("关闭提示词增强时，请指定图片宽高或固定比例");
            }
            body.set("disable_prompt_enhanced", true);
        }
        return body;
    }

    private static void applySize(JSONObject body, ImageTask task) {
        String resolution = StrUtil.trimToEmpty(task.getResolution());
        String ratio = StrUtil.blankToDefault(task.getRatio(), task.getAspectRatio());
        Integer width = task.getWidth();
        Integer height = task.getHeight();
        var size = SIZE.matcher(resolution);
        if (size.matches()) {
            int w = Integer.parseInt(size.group(1));
            int h = Integer.parseInt(size.group(2));
            if (width != null && width != w || height != null && height != h) {
                throw new BusinessException("图片宽高与所选分辨率不一致");
            }
            width = w;
            height = h;
        }
        if (width != null || height != null) {
            if (width == null || height == null || width <= 0 || height <= 0
                    || !validAspect(width, height) || (long) width * height < 655_360
                    || (long) width * height > 20_000_000) {
                throw new BusinessException("MiniMax 图片面积须为 655360 至 20000000 像素，宽高比须在 1:3 至 3:1 之间");
            }
            body.set("size", width + "x" + height);
            return;
        }

        String area = resolution.isEmpty() || resolution.equals("auto") ? "4K" : resolution;
        if (!AREAS.contains(area)) {
            throw new BusinessException("MiniMax 图片面积档位须为 1K、2K 或 4K");
        }
        ratio = StrUtil.blankToDefault(ratio, "auto");
        if (!ratio.equals("auto")) {
            var parts = RATIO.matcher(ratio);
            if (!parts.matches() || !validRatio(parts.group(1), parts.group(2))) {
                throw new BusinessException("MiniMax 图片比例须为 auto 或 1:3 至 3:1 的整数比，比例两项须在 1 至 300 之间");
            }
        }
        body.set("size", "auto").set("area", area).set("aspect_ratio", ratio);
    }

    private static boolean validRatio(String numerator, String denominator) {
        int w = Integer.parseInt(numerator);
        int h = Integer.parseInt(denominator);
        return w <= 300 && h <= 300 && validAspect(w, h);
    }

    private static boolean validAspect(int width, int height) {
        return Math.max(width, height) <= 3L * Math.min(width, height);
    }

    private static RemoteGenerationResult result(JSONObject response) {
        var data = response.getJSONArray("data");
        if (data == null) {
            throw new BusinessException("MiniMax 图片响应缺少 data");
        }
        return RemoteGenerationResult.success(data.toList(JSONObject.class).stream()
                .filter(item -> !item.containsKey("error"))
                .map(item -> RemoteGenerationResult.Output.image(item.getStr("url"))).toList());
    }
}
