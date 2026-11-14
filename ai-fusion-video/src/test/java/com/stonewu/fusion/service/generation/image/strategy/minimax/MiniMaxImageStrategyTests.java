package com.stonewu.fusion.service.generation.image.strategy.minimax;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.job.RemoteGenerationResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiniMaxImageStrategyTests {
    /** 按参考图切换正式端点，同时保留部分成功的输出。 */
    @Test
    void sendsImageGenerationAndEditWithDocumentedJson() throws Exception {
        var capture = new AtomicReference<Request>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/", exchange -> {
            try (exchange) {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                capture.set(new Request(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), JSONUtil.parseObj(raw)));
                byte[] response = """
                        {"data":[{"error":{"code":"1027","message":"filtered"}},{"url":"https://example.com/out.jpg"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var model = AiModel.builder().code("MiniMax-H3-Image-Highspeed").build();
            var config = ApiConfig.builder().apiKey("fixture-key")
                    .apiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
            var capabilities = mock(GenerationModelCapabilityService.class);
            when(capabilities.getMergedModelConfig(model)).thenReturn(new JSONObject());
            var strategy = new MiniMaxImageStrategy(capabilities);
            var task = ImageTask.builder().prompt("海边灯塔").count(2).ratio("16:9").resolution("2K").build();

            try (var prepared = strategy.prepare(task, model, config)) {
                var result = prepared.submit().result();
                assertThat(result.outputs()).extracting(RemoteGenerationResult.Output::media)
                        .containsExactly("https://example.com/out.jpg");
            }
            var generation = capture.get();
            assertThat(generation.path()).isEqualTo("/v1/images/generations");
            assertThat(generation.authorization()).isEqualTo("Bearer fixture-key");
            assertThat(generation.body().getRaw()).containsAllEntriesOf(new JSONObject()
                    .set("model", "MiniMax-H3-Image-Highspeed").set("prompt", "海边灯塔").set("n", 2)
                    .set("size", "auto").set("aspect_ratio", "16:9").set("area", "2K").set("response_format", "url"));
            assertThat(generation.body().getRaw()).doesNotContainKeys("images", "stream");

            task.setResolution("2720x1536");
            task.setRefImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")));
            try (var prepared = strategy.prepare(task, model, config)) {
                prepared.submit();
            }
            var edit = capture.get();
            assertThat(edit.path()).isEqualTo("/v1/images/edits");
            assertThat(edit.body().getJSONArray("images").getJSONObject(0).getStr("image_url"))
                    .isEqualTo("https://example.com/ref.png");
            assertThat(edit.body().getRaw()).containsEntry("size", "2720x1536").doesNotContainKeys("area", "aspect_ratio");
        } finally {
            server.stop(0);
        }
    }

    /** 禁止无效比例和超出官网像素范围的自定义尺寸。 */
    @Test
    void enforcesDocumentedSizeLimitsAndDefaults() {
        String code = "MiniMax-H3-Image";
        var task = ImageTask.builder().prompt("橘猫").build();
        assertThat(MiniMaxImageStrategy.buildRequest(task, code, new JSONObject()).getRaw())
                .containsEntry("area", "4K").containsEntry("aspect_ratio", "auto").containsEntry("n", 1);
        assertThatThrownBy(() -> MiniMaxImageStrategy.buildRequest(task, code,
                new JSONObject().set("disablePromptEnhanced", true))).isInstanceOf(BusinessException.class);
        task.setRatio("1:4");
        assertThatThrownBy(() -> MiniMaxImageStrategy.buildRequest(task, code, new JSONObject()))
                .hasMessageContaining("1:3");
        task.setResolution("6000x4000");
        assertThatThrownBy(() -> MiniMaxImageStrategy.buildRequest(task, code, new JSONObject()))
                .hasMessageContaining("20000000");
    }

    private record Request(String path, String authorization, JSONObject body) {
    }
}
