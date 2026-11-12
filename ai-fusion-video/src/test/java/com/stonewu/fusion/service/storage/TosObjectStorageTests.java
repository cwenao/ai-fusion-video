package com.stonewu.fusion.service.storage;

import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.ai.agentscope.workspace.AgentWorkspaceBackend;
import com.stonewu.fusion.service.ai.agentscope.workspace.AgentWorkspaceLocation;
import com.stonewu.fusion.service.ai.agentscope.workspace.AgentWorkspacePayloadService;
import com.stonewu.fusion.service.storage.strategy.ObjectStorageStrategy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.tos.TosServerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 用真实 TOS SDK 请求本地 HTTP 服务，覆盖签名、对象定位和读取契约。 */
class TosObjectStorageTests {

    private static final String ETAG = "\"fixed-version\"";
    private static final byte[] VIDEO = {1, 2, 3, 4};
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final S3ClientFactory s3 = mock(S3ClientFactory.class);
    private final ObjectStorageClients clients = new ObjectStorageClients(s3);
    private final ObjectStorageConfigResolver resolver = new ObjectStorageConfigResolver(new StorageProviderRegistry());
    private final StorageUrlResolver urls = new StorageUrlResolver();
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source.mp4", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, VIDEO.length);
                exchange.getResponseBody().write(VIDEO);
            }
        });
        server.createContext("/bucket/", this::handleObject);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        clients.closeAll();
        server.stop(0);
    }

    @Test
    void defaultObjectStorageUploadsBytesLocalFilesAndRemoteStreams(@TempDir Path directory) throws IOException {
        var settings = mock(StorageConfigService.class);
        when(settings.getDefaultConfig()).thenReturn(config());
        var service = new MediaStorageService(settings, List.of(new ObjectStorageStrategy(resolver, clients, urls)));
        Path file = Files.write(directory.resolve("test.mp4"), VIDEO);

        String bytesUrl = service.storeBytes(VIDEO, "videos", "mp4");
        String fileUrl = service.storeFile(file, "videos", "mp4");
        String remoteUrl = service.downloadAndStore(endpoint + "/source.mp4", "videos");

        assertThat(objects).hasSize(3);
        for (String url : new String[]{bytesUrl, fileUrl, remoteUrl}) {
            assertThat(url).startsWith(endpoint + "/bucket/media/videos/").endsWith(".mp4");
            StoredObject object = objects.get(url.substring(endpoint.length()));
            assertThat(object.bytes()).containsExactly(VIDEO);
            assertThat(object.contentType()).isEqualTo("video/mp4");
        }
        verifyNoInteractions(s3);
    }

    @Test
    void connectionTestVerifiesAnonymousReadAndRemovesItsObject() {
        var service = new StorageConnectionTestService(resolver, clients, urls);

        var result = service.test(config());

        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(result.publicUrl()).startsWith(endpoint + "/bucket/media/.healthcheck/");
        assertThat(objects).isEmpty();
        verifyNoInteractions(s3);
    }

    @Test
    void readsWorkspaceContentAndDeletesIt(@TempDir Path directory) {
        var settings = mock(StorageConfigService.class);
        when(settings.getById(21L)).thenReturn(config());
        var service = new AgentWorkspacePayloadService(settings, resolver, clients, directory.toString());
        var location = new AgentWorkspaceLocation(AgentWorkspaceBackend.OBJECT_STORAGE, 21L, null);

        var stored = service.write("{\"内容\":true}", location);

        assertThat(stored.contentRef()).startsWith("media/agent-workspace/objects/");
        assertThat(service.read(stored)).isEqualTo("{\"内容\":true}");
        service.delete(stored);
        assertThat(objects).isEmpty();
        verifyNoInteractions(s3);
    }

    @Test
    void rangeReadsRequireTheExpectedVersionAndResponseRange() throws IOException {
        var client = clients.forManagedMedia(resolver.resolve(config()));
        String key = "media/test.mp4";
        client.putBytes(key, "video/mp4", VIDEO);
        client.putBytes("ignore-range.mp4", "video/mp4", VIDEO);

        assertThat(client.head(key)).isEqualTo(new ObjectStorageClient.ObjectMetadata(4, ETAG));
        assertThat(client.getRange(key, ETAG, 1, 2, VIDEO.length)).containsExactly(2, 3);
        assertThatThrownBy(() -> client.getRange(key, "\"other-version\"", 1, 2, VIDEO.length))
                .isInstanceOf(TosServerException.class);
        assertThatThrownBy(() -> client.getRange("ignore-range.mp4", ETAG, 1, 2, VIDEO.length))
                .isInstanceOf(IOException.class).hasMessageContaining("未按范围");
        verifyNoInteractions(s3);
    }

    private StorageConfig config() {
        return StorageConfig.builder().id(21L).type("s3").provider(StorageProviderRegistry.VOLCENGINE_TOS)
                .region("cn-beijing").endpoint(endpoint).bucketName("bucket").status(1)
                .basePath("/media/").customDomain(endpoint + "/bucket")
                .accessKey("test-ak").secretKey("test-sk").build();
    }

    private void handleObject(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            boolean publicProbe = "HEAD".equals(exchange.getRequestMethod()) && path.contains("/.healthcheck/");
            if (publicProbe ? authorization != null
                    : authorization == null || !authorization.startsWith("TOS4-HMAC-SHA256 ")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path, new StoredObject(exchange.getRequestBody().readAllBytes(),
                            exchange.getRequestHeaders().getFirst("Content-Type")));
                    exchange.getResponseHeaders().set("ETag", ETAG);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "DELETE" -> {
                    objects.remove(path);
                    exchange.sendResponseHeaders(204, -1);
                }
                case "GET", "HEAD" -> readObject(exchange, path);
                default -> exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private void readObject(HttpExchange exchange, String path) throws IOException {
        StoredObject object = objects.get(path);
        if (object == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.getResponseHeaders().set("ETag", ETAG);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(object.bytes().length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null || path.endsWith("/ignore-range.mp4")) {
            exchange.sendResponseHeaders(200, object.bytes().length);
            exchange.getResponseBody().write(object.bytes());
            return;
        }
        if (!ETAG.equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
            byte[] body = "{\"Code\":\"PreconditionFailed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(412, body.length);
            exchange.getResponseBody().write(body);
            return;
        }
        String[] bounds = range.substring("bytes=".length()).split("-");
        int start = Integer.parseInt(bounds[0]);
        int end = Integer.parseInt(bounds[1]);
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + object.bytes().length);
        exchange.sendResponseHeaders(206, end - start + 1);
        exchange.getResponseBody().write(Arrays.copyOfRange(object.bytes(), start, end + 1));
    }

    private record StoredObject(byte[] bytes, String contentType) {
    }
}
