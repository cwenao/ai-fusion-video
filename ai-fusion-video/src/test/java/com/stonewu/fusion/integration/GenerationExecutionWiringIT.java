package com.stonewu.fusion.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.security.TokenService;
import com.stonewu.fusion.service.system.SystemConfigService;
import com.stonewu.fusion.service.generation.job.GenerationAttemptRepository;
import com.stonewu.fusion.service.generation.job.GenerationProviderReceipt;
import com.stonewu.fusion.service.generation.job.RemoteGenerationResult;
import com.stonewu.fusion.service.generation.job.GenerationSubmissionService;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.resource.ResourceToolRequests;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import org.flywaydb.core.Flyway;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 真实 HTTP、调度器、MySQL/Redis 和私有图片登记；供应商为本地受控 HTTP 服务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenerationExecutionWiringIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("ai_fusion_video").withUsername("afv").withPassword("afv-test");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);
    static final Path FILES = Path.of("target", "generation-execution-wiring-" + UUID.randomUUID()).toAbsolutePath();
    static final AtomicLong IDS = new AtomicLong(28000);
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Remote> REMOTES = new ConcurrentHashMap<>();
    static final Map<String, ReviewRemote> REVIEWERS = new ConcurrentHashMap<>();
    static final HttpServer PROVIDER = provider();
    static final byte[] PNG = png();
    static final AtomicReference<byte[]> VIDEO = new AtomicReference<>();
    static final AtomicBoolean STORYBOARD_VIDEO_READY = new AtomicBoolean(false);
    static final AtomicReference<List<JsonNode>> STORYBOARD_SHOTS = new AtomicReference<>(List.of());
    static final AtomicInteger STORYBOARD_SUBMISSIONS = new AtomicInteger();
    static final Map<String, String> STORYBOARD_VIDEO_REQUESTS = new ConcurrentHashMap<>();
    static final String SAVED_STORYBOARD_PROMPT = "分镜验收，蓝色灯光\n镜头向左平移。";
    static final String DRAFT_STORYBOARD_PROMPT = "分镜验收，红色背景";
    static final Map<String, ImagePipeline> IMAGE_PIPELINES = new ConcurrentHashMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("spring.data.redis.password", () -> "");
        p.add("app.storage.local-base-path", FILES::toString);
        p.add("app.agent-workspace.local-base-path", () -> FILES.resolve("workspace").toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired SystemConfigService system;
    @Autowired GenerationAttemptRepository attempts;
    @Autowired GenerationSubmissionService submissions;
    @Autowired CanvasJson json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Flyway flyway;
    @AfterAll static void stopProvider() { PROVIDER.stop(0); }

    @Test
    void optedInOutputsAreReviewedSeparatelyWithoutBlockingGeneration() throws Exception {
        flyway.validate();
        var f = fixture("sync");
        var outsider = fixture("sync");
        var reviewer = reviewer(f, "图片审查桩", "image");
        var remote = reviewer.remote();
        assertThat(ok(get("/api/generation/reviews/models?kind=IMAGE", f.headers)).findValuesAsText("name"))
                .contains("图片审查桩");
        assertThat(ok(get("/api/generation/reviews/models?kind=VIDEO", f.headers)).findValuesAsText("name"))
                .doesNotContain("图片审查桩");

        String unchecked = submit(f, reviewImageRequest(f, 1));
        String uncheckedReview = reviewPath(imageResults(f, unchecked).get(0));
        assertThat(ok(get(uncheckedReview, f.headers)).isNull()).isTrue();
        assertThat(remote.calls.get()).isZero();

        var reviewedRequest = reviewImageRequest(f, 2);
        reviewedRequest.put("reviewModelId", reviewer.id());
        String task = submit(f, reviewedRequest);
        assertThat(submit(f, reviewedRequest)).isEqualTo(task);
        var changed = new HashMap<>(reviewedRequest);
        changed.remove("reviewModelId");
        assertThat(post(f, changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var items = imageResults(f, task);
        assertThat(items).hasSize(2);
        String first = reviewPath(items.get(0));
        String second = reviewPath(items.get(1));
        try {
            assertThat(remote.started.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(ok(get(first, f.headers)).path("state").asText()).isEqualTo("RUNNING");
            assertThat(ok(get(second, f.headers)).path("state").asText()).isEqualTo("PENDING");
            assertThat(get(first, outsider.headers).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ok(get("/api/generation/image/" + task, f.headers)).at("/execution/reviewRequested").asBoolean())
                    .isTrue();
        } finally {
            remote.release.countDown();
        }
        await().pollInterval(Duration.ofMillis(150)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(ok(get(first, f.headers)).at("/result/verdict").asText()).isEqualTo("NEEDS_CHANGES");
            assertThat(ok(get(second, f.headers)).path("state").asText()).isEqualTo("FAILED");
        });
        assertThat(remote.calls.get()).isEqualTo(2);
        assertThat(remote.input.get()).contains(f.prompt, "image_url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
        assertThat(imageResults(f, task).findValues("status")).allMatch(status -> status.asInt() == 1);
        assertThat(ok(get(first + "?target=ASSET&targetId=7", f.headers)).isNull()).isTrue();
    }

    @Test
    void optedInVideoReviewReceivesTheCompleteVideoAndNotItsThumbnail() throws Exception {
        var f = fixture("sync");
        byte[] clip = sampleVideo();
        VIDEO.set(clip);
        long videoModel = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                    max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                VALUES (?, '视频生成桩', 'sora-2', 3, 'openai', ?, '{"asyncTaskPollIntervalSeconds":1}', 1,
                    JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY())
                """, videoModel, f.id);
        var reviewer = reviewer(f, "视频审查桩", "video");
        reviewer.remote().release.countDown();
        assertThat(ok(get("/api/generation/reviews/models?kind=VIDEO", f.headers)).findValuesAsText("name"))
                .contains("视频审查桩");
        var request = Map.of("requestId", UUID.randomUUID().toString(), "scope", Map.of("userId", f.id),
                "modelId", videoModel, "reviewModelId", reviewer.id(), "prompt", "蓝色背景，保留声音",
                "generateMode", "text2video", "ratio", "16:9", "resolution", "1280x720", "duration", 4, "count", 1);
        String task = ok(http.postForEntity("/api/generation/video/submit", new HttpEntity<>(request, f.headers),
                JsonNode.class)).asText();
        String statusPath = "/api/generation/video/" + task;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var execution = ok(get(statusPath + "/execution", f.headers));
            assertThat(execution.path("state").asText()).as(execution.toString()).isEqualTo("SUCCEEDED");
        });
        long id = ok(get(statusPath, f.headers)).path("id").asLong();
        var item = ok(get("/api/generation/video/" + id + "/items", f.headers)).get(0);
        String source = item.path("videoUrl").asText();
        assertThat(source).startsWith("media://").isNotEqualTo(item.path("coverUrl").asText());
        String review = "/api/generation/reviews/media/" + source.substring("media://".length());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var report = ok(get(review, f.headers));
            assertThat(report.path("state").asText()).as(report.toString()).isEqualTo("COMPLETED");
        });
        assertThat(reviewer.remote().calls.get()).isEqualTo(1);
        assertThat(reviewer.remote().input.get()).contains("蓝色背景", "video_url",
                "data:video/mp4;base64," + Base64.getEncoder().encodeToString(clip));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void storyboardVideoPersistsThroughWaitingAndDisconnect(boolean editWhileWaiting, boolean disconnectBatch)
            throws Exception {
        STORYBOARD_VIDEO_READY.set(false);
        STORYBOARD_SUBMISSIONS.set(0);
        STORYBOARD_VIDEO_REQUESTS.clear();
        jdbc.update("UPDATE afv_ai_model SET default_model=FALSE WHERE model_type=3");
        var f = fixture("sync");
        VIDEO.set(sampleVideo());
        long model = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                    max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels,
                    default_model)
                VALUES (?, '分镜视频生成桩', 'sora-2', 3, 'openai', ?, '{"pollIntervalMillis":1000}', 1,
                    JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY(), TRUE)
                """, model, f.id);
        var reviewer = reviewer(f, "分镜审查桩", "video");
        reviewer.remote().release.countDown();
        long chat = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                    max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                VALUES (?, '分镜调度桩', 'storyboard-acceptance', 1, 'openai', ?, '', 2,
                    JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY())
                """, chat, f.id);
        var project = ok(http.postForEntity("/api/project", new HttpEntity<>(Map.of(
                "requestId", UUID.randomUUID().toString(), "fields", Map.of("name", "分镜回填验收")), f.headers),
                JsonNode.class));
        long projectId = project.path("id").asLong();
        ok(http.postForEntity("/api/project/" + projectId + "/workspace/initialize",
                new HttpEntity<>(Map.of("requestId", UUID.randomUUID().toString(), "version", project.path("version").asInt()),
                        f.headers), JsonNode.class));
        var board = ok(get("/api/storyboard/project/" + projectId, f.headers));
        var episode = storyboardChild(f, "episode", board, Map.of("episodeNumber", 1));
        var scene = storyboardChild(f, "scene", episode, Map.of("sceneNumber", "1"));
        var shot = storyboardChild(f, "item", scene, Map.of("content", "红色背景，固定镜头",
                "videoPrompt", SAVED_STORYBOARD_PROMPT));
        var shots = List.of(shot);
        if (disconnectBatch) {
            var updatedScene = ok(get("/api/storyboard/scene/" + scene.path("id").asLong(), f.headers));
            shots = List.of(shot, storyboardChild(f, "item", updatedScene, Map.of("content", "第二个镜头")));
        }
        STORYBOARD_SHOTS.set(shots);
        var shotIds = shots.stream().map(item -> item.path("id").asLong()).toList();
        long shotId = shot.path("id").asLong();
        String shotPath = "/api/storyboard/item/" + shotId;
        var body = Map.<String, Object>of("projectId", projectId, "modelId", chat, "agentType", "storyboard_video_gen",
                "toolExecutionMode", "FULL_ACCESS", "generationReviewModelId", reviewer.id(), "context",
                Map.of("selectedStoryboardItemIds", shotIds, "storyboardId", board.path("id").asLong()));
        var headers = new HttpHeaders();
        headers.putAll(f.headers);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        if (disconnectBatch) {
            verifyDisconnectedStoryboardBatch(body, f.headers, projectId, shotIds);
            return;
        }
        var pipeline = CompletableFuture.supplyAsync(() ->
                http.postForEntity("/api/ai/pipeline/run", new HttpEntity<>(body, headers), String.class));
        try {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var states = jdbc.queryForList("SELECT status FROM afv_agent_run WHERE project_id=? ORDER BY id",
                        String.class, projectId);
                assertThat(states).containsExactly("WAITING_EXTERNAL", "WAITING_EXTERNAL");
            });
            assertThat(ok(get(shotPath, f.headers)).path("generatedVideoUrl").isNull()).isTrue();
            assertThat(pipeline.isDone()).isFalse();
            if (editWhileWaiting) {
                var edit = Map.of("requestId", UUID.randomUUID().toString(), "id", shotId,
                        "version", shot.path("version").asInt(), "patch", Map.of("content", "并发编辑的画面内容"));
                ok(http.exchange("/api/storyboard/item", HttpMethod.PUT, new HttpEntity<>(edit, f.headers),
                        JsonNode.class));
            }
        } finally {
            STORYBOARD_VIDEO_READY.set(true);
        }
        assertThat(pipeline.get(45, TimeUnit.SECONDS).getBody()).contains("\"outputType\":\"DONE\"");
        var written = ok(get(shotPath, f.headers));
        String reference = jdbc.queryForObject("""
                SELECT i.video_url FROM afv_video_item i JOIN afv_generation_submission s ON s.task_id=i.task_id
                WHERE s.project_id=? AND s.kind='VIDEO' AND s.state='SUCCEEDED'
                """, String.class, projectId);
        if (editWhileWaiting) {
            assertThat(written.path("generatedVideoUrl").isNull()).isTrue();
            assertThat(written.path("content").asText()).isEqualTo("并发编辑的画面内容");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM afv_agent_event e JOIN afv_agent_run a ON a.run_id=e.run_id
                    WHERE a.project_id=? AND e.raw_event_type='PLATFORM_TOOL_RESULT'
                    AND JSON_UNQUOTE(JSON_EXTRACT(e.payload_json,'$.toolStatus'))='error'
                    """, Integer.class, projectId)).isEqualTo(1);
        } else {
            assertThat(written.path("generatedVideoUrl").asText()).isEqualTo(reference);
        }
        assertThat(written.path("videoPrompt").asText()).isEqualTo(SAVED_STORYBOARD_PROMPT);
        assertThat(STORYBOARD_VIDEO_REQUESTS.values()).singleElement()
                .asString().contains(SAVED_STORYBOARD_PROMPT).doesNotContain(DRAFT_STORYBOARD_PROMPT);
        assertThat(written.path("version").asInt()).isEqualTo(shot.path("version").asInt() + 1);
        String review = "/api/generation/reviews/media/" + reference.substring("media://".length())
                + "?target=SHOT_VIDEO&targetId=" + shotId;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(ok(get(review, f.headers)).path("state").asText()).isEqualTo("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_submission WHERE project_id=?",
                Integer.class, projectId)).isEqualTo(1);
    }

    private void verifyDisconnectedStoryboardBatch(Map<String, Object> body, HttpHeaders headers, long projectId,
                                                   List<Long> shotIds) throws Exception {
        String cursor = disconnectAfterFirstPipelineEvent(body, headers);
        String[] position = cursor.split(":", 2);
        String runId = position[0];
        try {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var jobs = jdbc.queryForList("""
                        SELECT j.phase FROM afv_remote_generation_job j JOIN afv_generation_submission s
                        ON j.submission_id = s.id WHERE s.project_id = ?
                        """, String.class, projectId);
                assertThat(jobs).containsExactlyInAnyOrder("POLLING", "NEW");
            });
            assertThat(STORYBOARD_SUBMISSIONS.get()).isEqualTo(1);
            assertThat(jdbc.update("""
                    UPDATE afv_remote_generation_job j JOIN afv_generation_submission s ON j.submission_id = s.id
                    SET j.deadline_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
                    WHERE s.project_id = ? AND j.phase = 'NEW' AND j.permit_held = FALSE
                    """, projectId)).isEqualTo(1);
        } finally {
            STORYBOARD_VIDEO_READY.set(true);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(ok(get("/api/ai/pipeline/status?runId=" + runId, headers)).path("status").asText())
                    .isEqualTo("COMPLETED");
        });
        var streamHeaders = new HttpHeaders();
        streamHeaders.putAll(headers);
        streamHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        var replay = http.exchange("/api/ai/pipeline/reconnect?runId=" + runId + "&afterSequence=" + position[1],
                HttpMethod.GET, new HttpEntity<>(streamHeaders), String.class);
        assertThat(replay.getBody()).contains("\"outputType\":\"DONE\"");
        for (int index = 0; index < shotIds.size(); index++) {
            var written = ok(get("/api/storyboard/item/" + shotIds.get(index), headers));
            assertThat(written.path("generatedVideoUrl").asText()).startsWith("media://");
            assertThat(written.path("videoPrompt").asText())
                    .isEqualTo(index == 0 ? SAVED_STORYBOARD_PROMPT : DRAFT_STORYBOARD_PROMPT);
        }
        assertThat(STORYBOARD_SUBMISSIONS.get()).isEqualTo(shotIds.size());
    }

    private String disconnectAfterFirstPipelineEvent(Map<String, Object> body, HttpHeaders headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(http.getRootUri() + "/api/ai/pipeline/run"))
                .header(HttpHeaders.AUTHORIZATION, headers.getFirst(HttpHeaders.AUTHORIZATION))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(response.statusCode()).isEqualTo(200);
                String cursor = null;
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.startsWith("id:")) {
                        cursor = line.substring(3).trim();
                    }
                    if (line.isEmpty() && cursor != null) {
                        return cursor;
                    }
                }
                throw new AssertionError("流水线没有返回可恢复的事件位置");
            }
        }
    }

    private JsonNode storyboardChild(Fixture f, String type, JsonNode parent, Map<String, Object> fields) {
        return ok(http.postForEntity("/api/storyboard/" + type, new HttpEntity<>(Map.of(
                "requestId", UUID.randomUUID().toString(), "parentId", parent.path("id").asLong(),
                "parentVersion", parent.path("version").asInt(), "fields", fields), f.headers), JsonNode.class));
    }

    @ParameterizedTest
    @CsvSource({"first,firstFrameImageUrl,SHOT_FIRST_FRAME", "last,lastFrameImageUrl,SHOT_LAST_FRAME",
            "asset,imageUrl,ASSET", "both,firstFrameImageUrl,SHOT_FIRST_FRAME", "variants,imageUrl,ASSET"})
    void projectImagePipelineWaitsAndWritesItsOwnReviewedResult(String destination, String field, String reviewType)
            throws Exception {
        boolean both = destination.equals("both");
        boolean variants = destination.equals("variants");
        boolean assetDestination = destination.equals("asset") || variants;
        String firstDestination = both ? "first" : destination;
        var f = fixture("async");
        jdbc.update("UPDATE afv_ai_model SET default_model=(id=?) WHERE model_type=2", f.id);
        var reviewer = reviewer(f, "项目生图审查桩", "image");
        reviewer.remote().allValid.set(true);
        reviewer.remote().release.countDown();
        long chat = IDS.incrementAndGet();
        String chatCode = "project-image-" + chat;
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                    max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                VALUES (?, '项目生图调度桩', ?, 1, 'openai', ?, '', 2, JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY())
                """, chat, chatCode, f.id);
        var project = ok(http.postForEntity("/api/project", new HttpEntity<>(Map.of(
                "requestId", UUID.randomUUID().toString(), "fields", Map.of("name", "项目生图回填验收")), f.headers),
                JsonNode.class));
        long projectId = project.path("id").asLong();
        String agentType;
        String resourcePath;
        ImagePipeline fixture;
        Long secondItem = null;
        Map<String, Object> context;
        if (assetDestination) {
            var asset = ok(http.postForEntity("/api/asset", new HttpEntity<>(Map.of("projectId", projectId,
                    "requestId", UUID.randomUUID().toString(), "fields", Map.of("name", "红色道具", "type", "prop")),
                    f.headers), JsonNode.class));
            var item = asset.path("items").get(0);
            int parentVersion = asset.path("version").asInt();
            if (variants) {
                var variant = ok(http.postForEntity("/api/asset/item", new HttpEntity<>(Map.of(
                        "requestId", UUID.randomUUID().toString(), "assetId", asset.path("id").asLong(),
                        "parentVersion", parentVersion, "fields", Map.of("name", "道具变体", "itemType", "variant")),
                        f.headers), JsonNode.class));
                secondItem = variant.path("id").asLong();
                parentVersion = variant.path("assetVersion").asInt();
            }
            fixture = new ImagePipeline("generate_asset_image", "asset", item.path("id").asLong(),
                    item.path("version").asInt(), asset.path("id").asLong(), parentVersion, f.prompt);
            agentType = "asset_image_gen";
            resourcePath = "/api/asset/item/" + fixture.target();
            context = Map.of("selectedAssetIds", List.of(fixture.parent()), "selectedAssetItemIds",
                    variants ? List.of(fixture.target(), secondItem) : List.of(fixture.target()));
        } else {
            ok(http.postForEntity("/api/project/" + projectId + "/workspace/initialize",
                    new HttpEntity<>(Map.of("requestId", UUID.randomUUID().toString(),
                            "version", project.path("version").asInt()), f.headers), JsonNode.class));
            var board = ok(get("/api/storyboard/project/" + projectId, f.headers));
            var episode = storyboardChild(f, "episode", board, Map.of("episodeNumber", 1));
            var scene = storyboardChild(f, "scene", episode, Map.of("sceneNumber", "1"));
            var shot = storyboardChild(f, "item", scene, Map.of("content", "红色背景"));
            fixture = new ImagePipeline("generate_storyboard_frame", firstDestination, shot.path("id").asLong(),
                    shot.path("version").asInt(), null, null, f.prompt);
            agentType = "storyboard_frame_gen";
            resourcePath = "/api/storyboard/item/" + fixture.target();
            context = Map.of("selectedStoryboardItemIds", List.of(fixture.target()), "storyboardId",
                    board.path("id").asLong(), "frameType", firstDestination, "framePrompt", f.prompt);
        }
        if (variants) {
            fixture = new ImagePipeline(fixture.childTool(), "asset", fixture.target(), fixture.version(),
                    fixture.parent(), fixture.parentVersion(), fixture.prompt(), secondItem);
        }
        IMAGE_PIPELINES.put(chatCode, fixture);
        var body = Map.of("projectId", projectId, "modelId", chat, "agentType", agentType,
                "toolExecutionMode", "FULL_ACCESS", "generationReviewModelId", reviewer.id(), "context", context);
        var headers = new HttpHeaders();
        headers.putAll(f.headers);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        var pipeline = CompletableFuture.supplyAsync(() ->
                http.postForEntity("/api/ai/pipeline/run", new HttpEntity<>(body, headers), String.class));
        CompletableFuture<ResponseEntity<String>> tailPipeline = null;
        if (both) {
            String tailCode = chatCode + "-tail";
            long tailChat = IDS.incrementAndGet();
            jdbc.update("""
                    INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                        max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                    VALUES (?, '尾帧调度桩', ?, 1, 'openai', ?, '', 2, JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY())
                    """, tailChat, tailCode, f.id);
            IMAGE_PIPELINES.put(tailCode, new ImagePipeline(fixture.childTool(), "last", fixture.target(),
                    fixture.version(), null, null, f.prompt));
            var tailContext = new HashMap<>(context);
            tailContext.put("frameType", "last");
            var tailBody = new HashMap<>(body);
            tailBody.put("context", tailContext);
            tailBody.put("modelId", tailChat);
            tailPipeline = CompletableFuture.supplyAsync(() ->
                    http.postForEntity("/api/ai/pipeline/run", new HttpEntity<>(tailBody, headers), String.class));
        }
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var states = jdbc.queryForList("SELECT status FROM afv_agent_run WHERE project_id=? ORDER BY id",
                        String.class, projectId);
                assertThat(states).hasSize(both ? 4 : variants ? 3 : 2).containsOnly("WAITING_EXTERNAL");
            });
            assertThat(ok(get(resourcePath, f.headers)).path(field).isNull()).isTrue();
            assertThat(pipeline.isDone()).isFalse();
        } finally {
            f.remote.complete.set(true);
        }
        assertThat(pipeline.get(40, TimeUnit.SECONDS).getBody()).contains("\"outputType\":\"DONE\"");
        if (tailPipeline != null) {
            assertThat(tailPipeline.get(40, TimeUnit.SECONDS).getBody()).contains("\"outputType\":\"DONE\"");
        }
        var result = ok(get(resourcePath, f.headers));
        assertThat(result.path("version").asInt()).isEqualTo(fixture.version() + (both ? 2 : 1));
        String reference = result.path(field).asText();
        assertThat(reference).startsWith("media://");
        assertThat(result.path(assetDestination ? "aiPrompt" : firstDestination + "FramePrompt").asText())
                .isEqualTo(f.prompt);
        String report = "/api/generation/reviews/media/" + reference.substring("media://".length())
                + "?target=" + reviewType + "&targetId=" + fixture.target();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(ok(get(report, f.headers)).path("state").asText()).isEqualTo("COMPLETED"));
        if (both) {
            String tail = result.path("lastFrameImageUrl").asText();
            assertThat(tail).startsWith("media://").isNotEqualTo(reference);
            String tailReport = "/api/generation/reviews/media/" + tail.substring("media://".length())
                    + "?target=SHOT_LAST_FRAME&targetId=" + fixture.target();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(ok(get(tailReport, f.headers)).path("state").asText()).isEqualTo("COMPLETED"));
        }
        if (variants) {
            var sibling = ok(get("/api/asset/item/" + secondItem, f.headers));
            String siblingMedia = sibling.path("imageUrl").asText();
            assertThat(siblingMedia).startsWith("media://").isNotEqualTo(reference);
            assertThat(sibling.path("sourceType").asInt()).isEqualTo(2);
        }
        assertThat(f.remote.calls.get()).isEqualTo(both || variants ? 2 : 1);
    }

    private record ImagePipeline(String childTool, String destination, long target, int version, Long parent,
                                 Integer parentVersion, String prompt, Long secondTarget) {
        ImagePipeline(String childTool, String destination, long target, int version, Long parent,
                      Integer parentVersion, String prompt) {
            this(childTool, destination, target, version, parent, parentVersion, prompt, null);
        }
    }

    private Reviewer reviewer(Fixture f, String name, String kind) {
        long modelId = IDS.incrementAndGet();
        String modelCode = "review-" + kind + "-" + modelId;
        var remote = new ReviewRemote();
        REVIEWERS.put(modelCode, remote);
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config,
                    max_concurrency, multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                VALUES (?, ?, ?, 1, 'openai', ?, '{}', 1, JSON_ARRAY(?),
                    JSON_OBJECT(?, JSON_ARRAY('base64')), JSON_ARRAY())
                """, modelId, name, modelCode, f.id, kind, kind);
        return new Reviewer(modelId, remote);
    }

    private byte[] sampleVideo() throws IOException, InterruptedException {
        Path sample = Files.createTempFile("afv-review-fixture-", ".mp4");
        try {
            var encode = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i",
                    "color=c=red:s=160x90:d=4:r=8", "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", sample.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            if (!encode.waitFor(20, TimeUnit.SECONDS)) {
                encode.destroyForcibly();
                throw new IOException("未能在时限内生成视频测试素材");
            }
            assertThat(encode.exitValue()).isZero();
            return Files.readAllBytes(sample);
        } finally {
            Files.deleteIfExists(sample);
        }
    }

    private JsonNode imageResults(Fixture f, String task) {
        var done = awaitState(f, task, "SUCCEEDED");
        return ok(get("/api/generation/image/" + done.path("recordId").asLong() + "/items", f.headers));
    }

    private Map<String, Object> reviewImageRequest(Fixture fixture, int count) {
        var request = new HashMap<>(fixture.request(count));
        request.put("width", 1024);
        request.put("height", 1024);
        return request;
    }

    private String reviewPath(JsonNode image) {
        return "/api/generation/reviews/media/" + image.path("imageUrl").asText().substring("media://".length());
    }

    @Test
    void revokedProjectMemberCannotDeleteACompletedTaskTheyPreviouslySubmitted() {
        var creator = fixture("sync");
        var owner = fixture("sync");
        long project = ok(http.postForEntity("/api/project", new HttpEntity<>(Map.of(
                "requestId", UUID.randomUUID().toString(), "fields", Map.of("name", "撤权后的生成结果")),
                owner.headers), JsonNode.class)).path("id").asLong();
        String membershipPath = "/api/project/" + project + "/members";
        ok(http.postForEntity(membershipPath, new HttpEntity<>(Map.of("requestId", UUID.randomUUID().toString(),
                "expectedRevision", 0, "userId", creator.id, "role", 3), owner.headers), JsonNode.class));
        var request = new HashMap<>(creator.request(1));
        request.put("projectId", project);
        String publicId = submit(creator, request);
        awaitState(creator, publicId, "SUCCEEDED");
        long id = ok(get("/api/generation/image/" + publicId, creator.headers)).path("id").asLong();
        assertThat(ok(get("/api/generation/image/" + id + "/items", owner.headers))).hasSize(1);

        var removal = new HashMap<String, Object>();
        removal.put("requestId", UUID.randomUUID().toString());
        removal.put("expectedRevision", 1);
        removal.put("userId", creator.id);
        removal.put("role", null);
        ok(http.postForEntity(membershipPath, new HttpEntity<>(removal, owner.headers), JsonNode.class));
        assertThat(get("/api/generation/image/" + publicId, creator.headers).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var deletion = http.exchange("/api/generation/image/" + id, HttpMethod.DELETE,
                new HttpEntity<>(creator.headers), JsonNode.class);
        var ownerRead = get("/api/generation/image/" + publicId, owner.headers);
        assertThat(deletion.getStatusCode()).as("Revoked creator DELETE=%s; owner GET after deletion=%s",
                deletion.getStatusCode(), ownerRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ok(ownerRead);
        assertThat(ok(get("/api/generation/image/" + id + "/items", owner.headers))).hasSize(1);
        ok(http.postForEntity(membershipPath, new HttpEntity<>(Map.of("requestId", UUID.randomUUID().toString(),
                "expectedRevision", 2, "userId", creator.id, "role", 3), owner.headers), JsonNode.class));
        ok(http.exchange("/api/generation/image/" + id, HttpMethod.DELETE,
                new HttpEntity<>(creator.headers), JsonNode.class));
        assertThat(get("/api/generation/image/" + publicId, owner.headers).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void scopeMismatchCannotCreateOrWithdrawAnotherScopeRequest() {
        var f = fixture("sync");
        var scope = ok(get("/api/generation/executions/scope", f.headers));
        assertThat(scope.path("userId").asLong()).isEqualTo(f.id);
        var body = new HashMap<>(f.request(1));
        body.put("scope", Map.of("userId", f.id, "teamId", 99999L));
        assertThat(post(f, body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var wrong = http.postForEntity("/api/generation/executions/requests/" + body.get("requestId") + "/withdraw",
                new HttpEntity<>(Map.of("kind", "IMAGE", "scope", Map.of("userId", f.id + 1)), f.headers), JsonNode.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_submission WHERE actor_user_id = ?", Integer.class, f.id)).isZero();
    }

    @Test void withdrawingUnknownSubmissionPermanentlyRejectsItsLateArrival() {
        var f = fixture("sync");
        var body = f.request(1);
        var withdrawal = new HttpEntity<>(Map.of("kind", "IMAGE", "scope", Map.of("userId", f.id)), f.headers);
        String path = "/api/generation/executions/requests/" + body.get("requestId") + "/withdraw";
        assertThat(ok(http.postForEntity(path, withdrawal, JsonNode.class)).path("state").asText()).isEqualTo("CANCELLED_BEFORE_ACCEPTANCE");
        assertThat(ok(http.postForEntity(path, withdrawal, JsonNode.class)).path("state").asText()).isEqualTo("CANCELLED_BEFORE_ACCEPTANCE");
        assertThat(post(f, body).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_submission WHERE actor_user_id = ?", Integer.class, f.id)).isZero();
        assertThat(f.remote.calls.get()).isZero();
    }

    @Test void withdrawingAcceptedSubmissionCancelsOriginalAndRetryKeepsItsIdentity() {
        var f = fixture("async");
        var body = f.request(1);
        String taskId = submit(f, body);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(f.remote.calls.get()).isEqualTo(1));
        var withdrawal = new HttpEntity<>(Map.of("kind", "IMAGE", "scope", Map.of("userId", f.id)), f.headers);
        var result = ok(http.postForEntity("/api/generation/executions/requests/" + body.get("requestId") + "/withdraw", withdrawal, JsonNode.class));
        assertThat(result.path("taskId").asText()).isEqualTo(taskId);
        assertThat(result.path("state").asText()).isEqualTo("CANCEL_REQUESTED");
        assertThat(submit(f, body)).isEqualTo(taskId);
        f.remote.complete.set(true);
        awaitState(f, taskId, "CANCELLED");
        assertThat(f.remote.calls.get()).isEqualTo(1);
    }

    @Test void nativeBatchAdmissionReplaysOnceAndPublishesOnlyManagedOutputs() {
        var f = fixture("sync");
        var body = f.request(3);
        String taskId = submit(f, body);
        assertThat(submit(f, body)).isEqualTo(taskId);
        var done = awaitState(f, taskId, "SUCCEEDED");
        var taskView = ok(get("/api/generation/image/" + taskId, f.headers));
        assertThat(taskView.path("taskId").asText()).isEqualTo(taskId);
        assertThat(taskView.path("execution").path("id").asText()).isEqualTo(done.path("id").asText());
        assertThat(taskView.path("execution").path("canControl").asBoolean()).isTrue();
        assertThat(taskView.has("task")).isFalse();
        assertThat(taskView.has("executionContextJson")).isFalse();
        assertThat(f.remote.calls.get()).isEqualTo(1);
        assertThat(f.remote.count.get()).isEqualTo(3);
        assertThat(done.path("items")).hasSize(1);
        assertThat(done.path("items").get(0).path("itemIds")).hasSize(3);
        var items = ok(get("/api/generation/image/" + done.path("recordId").asLong() + "/items", f.headers));
        assertThat(items).hasSize(3);
        for (var item : items) {
            assertThat(item.path("imageUrl").asText()).startsWith("media://");
            assertThat(item.path("status").asInt()).isEqualTo(1);
        }
        String media = items.get(0).path("imageUrl").asText().substring("media://".length());
        String url = ok(http.postForEntity("/api/media-resources/" + media + "/access", new HttpEntity<>(f.headers), JsonNode.class)).path("url").asText();
        assertThat(http.getForEntity(url, byte[].class).getBody()).isEqualTo(PNG);
        var changed = new HashMap<>(body);
        changed.put("prompt", "不同的内容");
        assertThat(post(f, changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_submission WHERE actor_user_id = ?", Integer.class, f.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_remote_generation_job WHERE actor_user_id = ? AND permit_held = TRUE", Integer.class, f.id)).isZero();
    }

    @Test void missingBatchOutputsArePartialFailureWithEveryActualResultPreserved() {
        var f = fixture("partial");
        String task = submit(f, f.request(3));
        var done = awaitState(f, task, "PARTIAL_FAILED");
        var items = ok(get("/api/generation/image/" + done.path("recordId").asLong() + "/items", f.headers));
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("status").asInt()).isEqualTo(1);
        assertThat(items.get(1).path("status").asInt()).isEqualTo(1);
        assertThat(items.get(2).path("status").asInt()).isEqualTo(2);
        assertThat(items.get(2).path("imageUrl").isNull()).isTrue();
    }

    @Test void runningCancellationKeepsCapacityUntilRemoteTerminalAndQueuedCancellationNeverSubmits() {
        var f = fixture("async");
        String first = submit(f, f.request(1));
        await().atMost(Duration.ofSeconds(20)).until(() -> f.remote.calls.get() == 1);
        var secondBody = f.request(1);
        String second = submit(f, secondBody);
        ok(http.postForEntity("/api/generation/image/" + second + "/cancel", new HttpEntity<>(f.headers), JsonNode.class));
        awaitState(f, second, "CANCELLED");
        assertThat(f.remote.calls.get()).isEqualTo(1);
        ok(http.postForEntity("/api/generation/image/" + first + "/cancel", new HttpEntity<>(f.headers), JsonNode.class));
        var stopping = awaitState(f, first, "CANCEL_REQUESTED");
        assertThat(stopping.path("items").get(0).path("remoteCapacityHeld").asBoolean()).isTrue();
        f.remote.complete.set(true);
        var cancelled = awaitState(f, first, "CANCELLED");
        assertThat(cancelled.path("items").get(0).path("remoteCapacityHeld").asBoolean()).isFalse();
        assertThat(f.remote.calls.get()).isEqualTo(1);
        var items = ok(get("/api/generation/image/" + cancelled.path("recordId").asLong() + "/items", f.headers));
        assertThat(items.get(0).path("imageUrl").isNull()).isTrue();
    }

    @Test void explicitProviderErrorEndsExecutionAndReleasesCapacityWithoutReview() {
        var f = fixture("rejected");
        String task = submit(f, f.request(1));
        var failed = awaitState(f, task, "FAILED");
        var item = failed.path("items").get(0);
        assertThat(item.path("remoteCapacityHeld").asBoolean()).isFalse();
        assertThat(item.path("availableActions")).isEmpty();
        assertThat(failed.toString()).contains("HTTP 503", "video queue is full");
        assertThat(f.remote.calls.get()).isEqualTo(1);
        assertThat(item.path("attempts").get(0).path("acknowledged").asBoolean()).isTrue();
        f.remote.complete.set(true);
        String retry = submit(f, f.request(1));
        awaitState(f, retry, "SUCCEEDED");
        assertThat(f.remote.calls.get()).isEqualTo(2);
        assertThat(ok(get("/api/generation/executions/" + failed.path("id").asText(), f.headers)).path("state").asText()).isEqualTo("FAILED");
    }

    @Test void ambiguousSubmissionReleasesCapacityAndRetainsUnknownRecordWithoutReposting() {
        var f = fixture("unknown");
        String task = submit(f, f.request(1));
        var done = awaitState(f, task, "FAILED");
        var item = done.path("items").get(0);
        assertThat(item.path("state").asText()).isEqualTo("UNKNOWN_SUBMISSION");
        assertThat(item.path("remoteCapacityHeld").asBoolean()).isFalse();
        assertThat(item.path("availableActions")).isEmpty();
        var record = ok(get("/api/execution-records/GENERATION/" + item.path("id").asText(), f.headers));
        assertThat(record.at("/task/remoteUnknown").asBoolean()).isTrue();
        assertThat(record.at("/task/localFinished").asBoolean()).isTrue();
        assertThat(record.path("capacityHeld").asBoolean()).isFalse();
        assertThat(ok(get("/api/execution-records", f.headers)).path("items").toString()).contains(item.path("id").asText());
        assertThat(f.remote.calls.get()).isEqualTo(1);
        f.remote.complete.set(true);
        awaitState(f, submit(f, f.request(1)), "SUCCEEDED");
        assertThat(f.remote.calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_attempt WHERE job_id = ?", Integer.class, item.path("id").asText())).isEqualTo(1);
    }

    @Test void anotherUserCannotReadTheUnknownExecutionOrItsExceptionRecord() {
        var owner = fixture("unknown");
        var other = fixture("sync");
        String task = submit(owner, owner.request(1));
        var done = awaitState(owner, task, "FAILED");
        assertThat(get("/api/generation/executions/" + done.path("id").asText(), other.headers).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String job = done.path("items").get(0).path("id").asText();
        assertThat(get("/api/execution-records/GENERATION/" + job, other.headers).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ok(get("/api/execution-records", other.headers)).path("items").toString()).doesNotContain(job);
        assertThat(owner.remote.calls.get()).isEqualTo(1);
    }

    @Test void providerInputTicketSurvivesPollOwnershipChangesAndIsRevokedOnCancellation() throws Exception {
        var f = fixture("async");
        system.setValue(SystemConfigService.RESOURCE_BASE_URL_KEY, "https://media.example.test");
        jdbc.update("UPDATE afv_ai_model SET config = ? WHERE id = ?", JSON.writeValueAsString(Map.of(
                "defaultWidth", 64, "defaultHeight", 64, "asyncMode", true, "asyncTaskPollIntervalSeconds", 1,
                "supportReferenceImages", true, "referenceImageInputFormats", List.of("url"))), f.id);
        var multipart = new LinkedMultiValueMap<String, Object>();
        multipart.add("file", new ByteArrayResource(PNG) { @Override public String getFilename() { return "reference.png"; } });
        multipart.add("requestId", UUID.randomUUID().toString());
        var uploadHeaders = new HttpHeaders();
        uploadHeaders.addAll(f.headers);
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        String resource = ok(http.postForEntity("/api/media-resources/upload", new HttpEntity<>(multipart, uploadHeaders), JsonNode.class)).path("id").asText();
        var body = new HashMap<>(f.request(1));
        body.put("refImageUrls", JSON.writeValueAsString(List.of("media://" + resource)));
        String task = submit(f, body);
        await().atMost(Duration.ofSeconds(20)).until(() -> f.remote.inputUrl.get() != null);
        var published = URI.create(f.remote.inputUrl.get());
        assertThat(published.getHost()).isEqualTo("media.example.test");
        // 在受控 HTTP 测试中将公网网关映射到本地应用，不放宽生产的公网地址校验。
        String inputUrl = http.getRootUri() + published.getRawPath() + "?" + published.getRawQuery();
        assertThat(http.getForEntity(inputUrl, byte[].class).getBody()).isEqualTo(PNG);
        var execution = ok(get("/api/generation/image/" + task + "/execution", f.headers));
        String job = execution.path("items").get(0).path("id").asText();
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT owner_epoch FROM afv_remote_generation_job WHERE id = ?", Long.class, job) >= 3);
        assertThat(http.getForEntity(inputUrl, byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_media_access_ticket WHERE resource_id = ? AND purpose = 'GENERATION' AND generation_job_id = ? AND generation_attempt_id IS NOT NULL",
                Integer.class, resource, job)).isEqualTo(1);
        ok(http.postForEntity("/api/generation/image/" + task + "/cancel", new HttpEntity<>(f.headers), JsonNode.class));
        assertThat(http.getForEntity(inputUrl, byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        f.remote.complete.set(true);
        awaitState(f, task, "CANCELLED");
    }

    private Fixture fixture(String mode) {
        long id = IDS.incrementAndGet();
        String prompt = "generation-test-" + id;
        var remote = new Remote(mode, prompt);
        REMOTES.put(prompt, remote);
        jdbc.update("INSERT INTO sys_user(id, username, password) VALUES (?, ?, 'test-only')", id, prompt);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM afv_storage_config", Integer.class) == 0) {
            jdbc.update("INSERT INTO afv_storage_config(name, type, base_path, is_default) VALUES ('执行验收存储', 'local', ?, TRUE)", FILES.toString());
        }
        String protocol = mode.equals("async") ? "newapi" : "openai";
        jdbc.update("INSERT INTO afv_api_config(id, name, platform, image_protocol, api_url, api_key) VALUES (?, ?, ?, ?, ?, 'test-key')",
                id, prompt, mode.equals("async") ? "newapi" : "openai_compatible", protocol, "http://localhost:" + PROVIDER.getAddress().getPort());
        String config = mode.equals("async") ? "{\"defaultWidth\":64,\"defaultHeight\":64,\"asyncMode\":true,\"asyncTaskPollIntervalSeconds\":1}" : "{\"defaultWidth\":64,\"defaultHeight\":64}";
        jdbc.update("""
                INSERT INTO afv_ai_model(id, name, code, model_type, model_protocol, api_config_id, config, max_concurrency,
                    multimodal_input_types, multimodal_input_transports, reasoning_effort_levels)
                VALUES (?, ?, ?, 2, ?, ?, ?, 1, JSON_ARRAY(), JSON_OBJECT(), JSON_ARRAY())
                """, id, prompt, "gpt-image-2", protocol, id, config);
        var headers = new HttpHeaders();
        headers.setBearerAuth(tokens.createToken(id, prompt, null).getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new Fixture(id, prompt, headers, remote);
    }
    @Test void lateSuccessfulReceiptIsRetainedWithoutRestartingTheEndedJob() {
        var f = fixture("unknown");
        String taskId = submit(f, f.request(1));
        var done = awaitState(f, taskId, "FAILED");
        String jobId = done.path("items").get(0).path("id").asText();
        String original = jdbc.queryForObject("SELECT active_attempt_id FROM afv_remote_generation_job WHERE id = ?", String.class, jobId);
        attempts.acknowledge(original, GenerationProviderReceipt.completed(RemoteGenerationResult.success(List.of(
                RemoteGenerationResult.Output.image("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG))))));
        var record = ok(get("/api/execution-records/GENERATION/" + jobId, f.headers));
        assertThat(record.path("remote").get(0).path("responseRecorded").asBoolean()).isTrue();
        assertThat(record.path("capacityHeld").asBoolean()).isFalse();
        assertThat(record.at("/task/localFinished").asBoolean()).isTrue();
        assertThat(ok(get("/api/generation/executions/" + done.path("id").asText(), f.headers)).path("state").asText()).isEqualTo("FAILED");
        assertThat(f.remote.calls.get()).isEqualTo(1);
    }

    @Test void referenceDownloadFailureEndsBeforePaidIntentAndReleasesCapacity() throws Exception {
        var f = fixture("preparation-error");
        String task = submit(f, referenceRequest(f));
        var execution = awaitState(f, task, "FAILED");
        assertThat(f.remote.inputReads.get()).isEqualTo(1);
        assertThat(f.remote.calls.get()).isZero();
        assertUnsubmitted(execution.path("items").get(0).path("id").asText());
    }

    @Test void cancellationDuringReferenceDownloadDoesNotSubmitWhenPreparationReturns() throws Exception {
        var f = fixture("preparation-wait");
        String task = submit(f, referenceRequest(f));
        try {
            assertThat(f.remote.inputStarted.await(20, TimeUnit.SECONDS)).isTrue();
            var execution = ok(get("/api/generation/image/" + task + "/execution", f.headers));
            String jobId = execution.path("items").get(0).path("id").asText();
            assertThat(jdbc.queryForObject("SELECT phase FROM afv_remote_generation_job WHERE id = ?", String.class, jobId))
                    .isEqualTo("PREPARING");
            ok(http.postForEntity("/api/generation/image/" + task + "/cancel", new HttpEntity<>(f.headers), JsonNode.class));
        } finally { f.remote.inputReleased.countDown(); }
        var cancelled = awaitState(f, task, "CANCELLED");
        assertThat(f.remote.calls.get()).isZero();
        assertUnsubmitted(cancelled.path("items").get(0).path("id").asText());
    }

    @Test void preparedMultipartUsesFetchedImageOnceAndPublishesOneManagedResult() throws Exception {
        var f = fixture("preparation-success");
        String task = submit(f, referenceRequest(f));
        var succeeded = awaitState(f, task, "SUCCEEDED");
        assertThat(f.remote.inputReads.get()).isEqualTo(1);
        assertThat(f.remote.calls.get()).isEqualTo(1);
        assertThat(f.remote.contentType.get()).startsWith("multipart/form-data");
        String jobId = succeeded.path("items").get(0).path("id").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_attempt WHERE job_id = ? AND submitted_at IS NOT NULL",
                Integer.class, jobId)).isEqualTo(1);
    }

    @Test void adapterCannotMarkInvalidMultipartInputAsAnUncertainPaidSubmission() throws Exception {
        var f = fixture("preparation-invalid-data");
        var request = referenceRequest(f);
        request.put("refImageUrls", "[\"data:image/png;base64\"]");
        String task = submit(f, request);
        var execution = awaitState(f, task, "FAILED");
        assertUnsubmitted(execution.path("items").get(0).path("id").asText());
        assertThat(f.remote.inputReads.get()).isZero();
        assertThat(f.remote.calls.get()).isZero();
    }

    @Test void longAgentToolIdentityUsesBoundedRequestIdAndReplaysOnePaidSubmission() {
        var f = fixture("sync");
        var actor = agent(f, "call:" + "long-id/".repeat(14));
        String requestId = ResourceToolRequests.requestId(json, actor);
        assertThat(requestId).hasSize(70).matches("agent:[a-f0-9]{64}");
        var first = submissions.image(agentImage(f), requestId, actor, null);
        assertThat(submissions.image(agentImage(f), requestId, actor, null).id()).isEqualTo(first.id());
        awaitState(f, first.publicTaskId(), "SUCCEEDED");
        assertThat(f.remote.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT tool_call_id FROM afv_media_resource WHERE agent_run_id = ?", String.class, actor.agentRunId()))
                .containsExactly(actor.toolCallId());
        var changed = agentImage(f);
        changed.setPrompt("changed contents");
        assertThatThrownBy(() -> submissions.image(changed, "another-valid-request", actor, null)).hasMessageContaining("不同的生成内容");
        var oversized = new ResourceCommandContext(actor.actor(), "agent", null, actor.agentRunId(), "x".repeat(129),
                actor.ownerInstanceId(), actor.ownerEpoch());
        assertThatThrownBy(() -> submissions.image(agentImage(f), ResourceToolRequests.requestId(json, oversized), oversized, null))
                .hasMessageContaining("最多 128 字符");
        var nonAscii = new ResourceCommandContext(actor.actor(), "agent", null, actor.agentRunId(), "非 ASCII 调用",
                actor.ownerInstanceId(), actor.ownerEpoch());
        assertThatThrownBy(() -> submissions.image(agentImage(f), ResourceToolRequests.requestId(json, nonAscii), nonAscii, null))
                .hasMessageContaining("ASCII");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_submission WHERE actor_user_id = ?", Integer.class, f.id)).isEqualTo(1);
    }

    @Test void oldAgentRequestKeepsItsReceiptWhenToolRequestEncodingChanges() {
        var f = fixture("sync");
        var actor = agent(f, "legacy-call");
        String original = "tool:" + actor.agentRunId() + ":" + actor.toolCallId();
        var first = submissions.image(agentImage(f), original, actor, null);
        var replay = submissions.image(agentImage(f), ResourceToolRequests.requestId(json, actor), actor, null);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT request_id FROM afv_generation_submission WHERE id = ?", String.class, first.id())).isEqualTo(original);
        awaitState(f, first.publicTaskId(), "SUCCEEDED");
        assertThat(f.remote.calls.get()).isEqualTo(1);
    }

    private ResourceCommandContext agent(Fixture f, String call) {
        String run = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO afv_agent_run(run_id, conversation_id, user_id, kernel_fingerprint,
                    agent_definition_snapshot_json, agent_state_session_id, status, owner_instance_id, owner_epoch,
                    lease_until, deadline_at, started_at)
                VALUES (?, ?, ?, 'generation-identity-test', '{}', ?, 'RUNNING', 'identity-test-owner', 1,
                    TIMESTAMPADD(MINUTE, 10, UTC_TIMESTAMP(6)), TIMESTAMPADD(HOUR, 1, UTC_TIMESTAMP(6)), UTC_TIMESTAMP(6))
                """, run, run, f.id, run);
        return new ResourceCommandContext(new CanvasActor(f.id, null), "agent", null, run, call, "identity-test-owner", 1L);
    }

    private ImageTask agentImage(Fixture f) {
        return ImageTask.builder().userId(f.id).modelId(f.id).prompt(f.prompt).count(1).width(64).height(64).build();
    }

    private Map<String, Object> referenceRequest(Fixture f) throws Exception {
        jdbc.update("UPDATE afv_ai_model SET config = ? WHERE id = ?", JSON.writeValueAsString(Map.of(
                "defaultWidth", 64, "defaultHeight", 64, "supportReferenceImages", true,
                "referenceImageInputFormats", List.of("data_uri"))), f.id);
        var request = new HashMap<>(f.request(1));
        request.put("refImageUrls", JSON.writeValueAsString(List.of("http://localhost:" + PROVIDER.getAddress().getPort()
                + "/preparation-input/" + f.prompt)));
        return request;
    }

    private void assertUnsubmitted(String jobId) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM afv_generation_attempt WHERE job_id = ? AND submitted_at IS NOT NULL",
                Integer.class, jobId)).isZero();
        assertThat(jdbc.queryForObject("SELECT permit_held FROM afv_remote_generation_job WHERE id = ?", Boolean.class, jobId)).isFalse();
    }

    private record Fixture(long id, String prompt, HttpHeaders headers, Remote remote) {
        Map<String, Object> request(int count) {
            return Map.of("requestId", UUID.randomUUID().toString(), "scope", Map.of("userId", id), "modelId", id, "prompt", prompt, "width", 64, "height", 64, "count", count);
        }
    }
    private String submit(Fixture f, Map<String, Object> body) { return ok(post(f, body)).asText(); }
    private ResponseEntity<JsonNode> post(Fixture f, Map<String, Object> body) {
        return http.postForEntity("/api/generation/image/submit", new HttpEntity<>(body, f.headers), JsonNode.class);
    }
    private ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
    private JsonNode awaitState(Fixture f, String task, String state) {
        String path = "/api/generation/image/" + task + "/execution";
        await().pollInterval(Duration.ofMillis(150)).atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var execution = ok(get(path, f.headers));
            assertThat(execution.path("state").asText()).as(execution.toString()).isEqualTo(state);
        });
        return ok(get(path, f.headers));
    }
    private JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("code").asInt()).as(response.toString()).isZero();
        return response.getBody().path("data");
    }
    private static final class Remote {
        final String mode;
        final String id;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger count = new AtomicInteger();
        final AtomicBoolean complete = new AtomicBoolean();
        final AtomicReference<String> inputUrl = new AtomicReference<>();
        final AtomicReference<String> contentType = new AtomicReference<>();
        final AtomicInteger inputReads = new AtomicInteger();
        final CountDownLatch inputStarted = new CountDownLatch(1);
        final CountDownLatch inputReleased = new CountDownLatch(1);
        Remote(String mode, String id) { this.mode = mode; this.id = id; }
    }

    private static final class ReviewRemote {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean allValid = new AtomicBoolean();
        final AtomicReference<String> input = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private record Reviewer(long id, ReviewRemote remote) {
    }

    private static void review(HttpExchange exchange) throws IOException {
        var body = JSON.readTree(exchange.getRequestBody());
        var imagePipeline = IMAGE_PIPELINES.get(body.path("model").asText());
        if (imagePipeline != null) {
            projectImageChat(exchange, body, imagePipeline);
            return;
        }
        if ("storyboard-acceptance".equals(body.path("model").asText())) {
            storyboardChat(exchange, body);
            return;
        }
        var remote = REVIEWERS.get(body.path("model").asText());
        if (remote == null) {
            respond(exchange, 404, Map.of("error", "unknown review fixture"));
            return;
        }
        remote.input.set(body.toString());
        int attempt = remote.calls.incrementAndGet();
        remote.started.countDown();
        try {
            if (!remote.release.await(20, TimeUnit.SECONDS)) {
                throw new IOException("审查测试没有释放等待");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException(stopped);
        }
        String report = attempt == 1 || remote.allValid.get() ? """
                {"verdict":"NEEDS_CHANGES","summary":"背景颜色不同","findings":[
                {"issue":"背景不符","evidence":"背景是黑色","suggestion":"改为蓝色"}],"limitations":[]}
                """ : "模型没有返回结构化结论";
        var delta = JSON.createObjectNode().put("id", "review-" + attempt).put("object", "chat.completion.chunk")
                .put("model", body.path("model").asText()).put("created", 1);
        delta.putArray("choices").addObject().put("index", 0).putNull("finish_reason")
                .putObject("delta").put("role", "assistant").put("content", report);
        var end = delta.deepCopy();
        ((ObjectNode) end.path("choices").get(0))
                .put("finish_reason", "stop").putObject("delta");
        byte[] bytes = ("data: " + delta + "\n\ndata: " + end + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void storyboardChat(HttpExchange exchange, JsonNode body) throws IOException {
        var messages = body.path("messages");
        boolean root = body.path("tools").findValuesAsText("name").contains("generate_storyboard_video");
        var delta = JSON.createObjectNode().put("role", "assistant");
        String finish = "stop";
        if ("tool".equals(messages.get(messages.size() - 1).path("role").asText())) {
            delta.put("content", "已经收到实际执行结果。");
        } else {
            finish = "tool_calls";
            var shots = STORYBOARD_SHOTS.get();
            var calls = delta.putArray("tool_calls");
            if (root) {
                for (int index = 0; index < shots.size(); index++) {
                    long id = shots.get(index).path("id").asLong();
                    calls.addObject().put("index", index).put("id", "shot-child-" + id).put("type", "function")
                            .putObject("function").put("name", "generate_storyboard_video")
                            .put("arguments", JSON.writeValueAsString(Map.of("message", "storyboardItemId: " + id + "。")));
                }
            } else {
                var input = messages.toString();
                var shot = shots.stream().filter(item -> input.contains("storyboardItemId: " + item.path("id").asLong() + "。"))
                        .findFirst().orElseThrow();
                long id = shot.path("id").asLong();
                String arguments = JSON.writeValueAsString(Map.of("prompt", DRAFT_STORYBOARD_PROMPT,
                        "reviewTargetId", id, "expectedVersion", shot.path("version").asInt(),
                        "ratio", "16:9", "resolution", "1280x720", "duration", 4));
                calls.addObject().put("index", 0).put("id", "shot-video-" + id).put("type", "function")
                        .putObject("function").put("name", "generate_video").put("arguments", arguments);
            }
        }
        chatChunk(exchange, body, delta, finish);
    }

    private static void projectImageChat(HttpExchange exchange, JsonNode body, ImagePipeline fixture) throws IOException {
        var messages = body.path("messages");
        boolean root = body.path("tools").findValuesAsText("name").contains(fixture.childTool());
        var delta = JSON.createObjectNode().put("role", "assistant");
        if ("tool".equals(messages.get(messages.size() - 1).path("role").asText())) {
            delta.put("content", "收到执行回执。");
            chatChunk(exchange, body, delta, "stop");
            return;
        }
        var arguments = JSON.createObjectNode();
        long target = fixture.target();
        if (root) {
            arguments.put("message", "处理目标 " + fixture.target());
        } else {
            if (fixture.secondTarget() != null && messages.toString().contains("目标 " + fixture.secondTarget() + "。")) {
                target = fixture.secondTarget();
            }
            arguments.put("prompt", fixture.prompt()).put("width", 1024).put("height", 1024)
                    .put("reviewTargetId", target).put("expectedVersion", fixture.version());
            if (fixture.parent() != null) {
                arguments.put("assetId", fixture.parent()).put("expectedParentVersion", fixture.parentVersion());
            } else {
                arguments.put("frameType", fixture.destination());
            }
        }
        var calls = delta.putArray("tool_calls");
        calls.addObject().put("index", 0)
                .put("id", root ? "image-child-call" : "image-generation-call").put("type", "function")
                .putObject("function").put("name", root ? fixture.childTool() : "generate_image")
                .put("arguments", arguments.toString());
        if (root && fixture.secondTarget() != null) {
            calls.addObject().put("index", 1).put("id", "variant-child-call").put("type", "function")
                    .putObject("function").put("name", fixture.childTool())
                    .put("arguments", JSON.writeValueAsString(Map.of("message", "处理目标 " + fixture.secondTarget() + "。")));
        }
        chatChunk(exchange, body, delta, "tool_calls");
    }

    private static void chatChunk(HttpExchange exchange, JsonNode body, ObjectNode delta, String finish) throws IOException {
        var chunk = JSON.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("object", "chat.completion.chunk").put("model", body.path("model").asText()).put("created", 1);
        chunk.putArray("choices").addObject().put("index", 0).putNull("finish_reason").set("delta", delta);
        var last = chunk.deepCopy();
        ((ObjectNode) last.path("choices").get(0)).put("finish_reason", finish).putObject("delta");
        byte[] response = ("data: " + chunk + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        sendMedia(exchange, "text/event-stream", response);
    }

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", GenerationExecutionWiringIT::review);
            server.createContext("/v1/videos", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/content")) {
                    boolean thumbnail = "variant=thumbnail".equals(exchange.getRequestURI().getQuery());
                    sendMedia(exchange, thumbnail ? "image/png" : "video/mp4", thumbnail ? PNG : VIDEO.get());
                } else {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String id = "synthetic-review-video";
                    if (request.contains("分镜验收")) {
                        id = "storyboard-review-video-" + STORYBOARD_SUBMISSIONS.incrementAndGet();
                        STORYBOARD_VIDEO_REQUESTS.put(id, request);
                    } else if (path.startsWith("/v1/videos/storyboard-review-video-")) {
                        id = path.substring("/v1/videos/".length());
                    }
                    boolean storyboard = id.startsWith("storyboard-review-video-");
                    String state = storyboard && !STORYBOARD_VIDEO_READY.get() ? "in_progress" : "completed";
                    respond(exchange, 200, Map.of("id", id, "status", state, "seconds", "4"));
                }
            });
            server.createContext("/preparation-input/", exchange -> {
                var remote = REMOTES.get(exchange.getRequestURI().getPath().substring("/preparation-input/".length()));
                if (remote == null) { respond(exchange, 404, Map.of("error", "unknown fixture")); return; }
                remote.inputReads.incrementAndGet();
                remote.inputStarted.countDown();
                if (remote.mode.equals("preparation-wait")) {
                    try {
                        if (!remote.inputReleased.await(20, TimeUnit.SECONDS)) throw new IOException("fixture was not released");
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                }
                if (remote.mode.equals("preparation-error")) { respond(exchange, 404, Map.of("error", "source no longer available")); return; }
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, PNG.length);
                try (var output = exchange.getResponseBody()) { output.write(PNG); }
            });
            server.createContext("/v1/images/edits", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                var remote = REMOTES.values().stream().filter(value -> request.contains(value.id)).findFirst().orElse(null);
                if (remote == null) { respond(exchange, 400, Map.of("error", "unknown fixture")); return; }
                remote.calls.incrementAndGet();
                remote.contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                respond(exchange, 200, Map.of("data", List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(PNG)))));
            });
            server.createContext("/v1/images/generations", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                var remote = REMOTES.get(request.path("prompt").asText());
                if (remote == null) { respond(exchange, 400, Map.of("error", "unknown test request")); return; }
                remote.calls.incrementAndGet();
                var refs = request.get("image_urls");
                if (refs != null && refs.isArray() && !refs.isEmpty()) remote.inputUrl.set(refs.get(0).asText());
                int n = request.path("n").asInt(1);
                remote.count.set(n);
                if (remote.mode.equals("unknown") && !remote.complete.get()) { exchange.close(); return; }
                if (remote.mode.equals("rejected") && !remote.complete.get()) { respond(exchange, 503, Map.of("error", Map.of("message", "video queue is full, please retry later"))); return; }
                if (remote.mode.equals("async")) { respond(exchange, 200, Map.of("task_id", remote.id)); return; }
                var output = JSON.createArrayNode();
                for (int i = 0; i < (remote.mode.equals("partial") ? n - 1 : n); i++) output.addObject().put("b64_json", Base64.getEncoder().encodeToString(PNG));
                respond(exchange, 200, Map.of("data", output));
            });
            server.createContext("/v1/tasks/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/v1/tasks/".length());
                var remote = REMOTES.get(id);
                if (remote == null) { respond(exchange, 404, Map.of("error", "not found")); return; }
                respond(exchange, 200, Map.of("data", Map.of("id", id, "status", remote.complete.get() ? "completed" : "processing",
                        "result", Map.of("images", List.of(Map.of("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG)))))));
            });
            server.start();
            return server;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void sendMedia(HttpExchange exchange, String mime, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", mime);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
    private static byte[] png() {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
