package com.start.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgnesMediaServiceTest {
    private HttpServer server;
    private Path outputDir;
    private AgnesMediaService service;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        outputDir = Files.createTempDirectory("agnes-media-test-");
        service = new AgnesMediaService(
                true, "test-key", baseUrl + "/v1", baseUrl + "/agnesapi", baseUrl + "/pricing",
                outputDir.toString(), 5_000, 1_000, 1, false,
                HttpClient.newHttpClient(), Executors.newSingleThreadExecutor());
    }

    @AfterEach
    void tearDown() throws IOException {
        service.close();
        server.stop(0);
        try (var paths = Files.walk(outputDir)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void imageSourceFailureBodyIsReducedToAnAllowlistedReason() {
        assertEquals("EXPIRED", AgnesMediaService.safeImageFetchFailureReason(
                "media link expired".getBytes(StandardCharsets.UTF_8)));
        assertEquals("SIGNATURE_REJECTED", AgnesMediaService.safeImageFetchFailureReason(
                "invalid rkey signature".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ACCESS_REJECTED", AgnesMediaService.safeImageFetchFailureReason(
                "permission denied".getBytes(StandardCharsets.UTF_8)));
        assertEquals("UNCLASSIFIED", AgnesMediaService.safeImageFetchFailureReason(
                "opaque private response".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void imageRequestUsesAgnesContractAndDownloadsNonEmptyFile() throws Exception {
        AtomicInteger imageRequests = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            imageRequests.incrementAndGet();
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String request = readBody(exchange);
            assertTrue(request.contains("agnes-image-2.5-flash"));
            assertTrue(request.contains("\"size\":\"2K\""));
            assertTrue(request.contains("\"ratio\":\"16:9\""));
            assertTrue(request.contains("cinematic photorealism"));
            sendJson(exchange, "{\"data\":[{\"url\":\"" + baseUrl + "/media.png\"}]}");
        });
        server.createContext("/media.png", exchange -> sendBytes(exchange, "png-bytes".getBytes(StandardCharsets.UTF_8)));

        List<AgnesMediaService.GeneratedMedia> results = service.generateImage(
                "一只糖果熊", "2K", "16:9", List.of());

        assertEquals(1, imageRequests.get());
        assertEquals(1, results.size());
        assertEquals("image", results.get(0).kind());
        assertArrayEquals("png-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(results.get(0).path()));
    }

    @Test
    void textImagePromptGetsRealisticDefaultsButExplicitStyleIsPreserved() {
        assertTrue(AgnesMediaService.optimizeImagePrompt("一位站在窗边的亚洲少女")
                .contains("cinematic photorealism"));
        assertTrue(AgnesMediaService.optimizeImagePrompt("一位站在窗边的亚洲少女")
                .contains("cohesive color palette"));
        assertTrue(AgnesMediaService.optimizeImagePrompt("一位站在窗边的亚洲少女")
                .contains("high-fidelity photographic detail"));
        assertEquals("一只可爱的Q版熊，水彩插画", AgnesMediaService.optimizeImagePrompt("一只可爱的Q版熊，水彩插画"));
    }

    @Test
    void videoSubmissionPollsSameTaskAndDownloadsResult() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/v1/videos", exchange -> {
            String request = readBody(exchange);
            assertTrue(request.contains("agnes-video-2.5-flash"));
            assertTrue(request.contains("\"seconds\":\"5\""));
            assertTrue(request.contains("\"size\":\"720P\""));
            sendJson(exchange, "{\"video_id\":\"task-123\"}");
        });
        server.createContext("/agnesapi", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("video_id=task-123"));
            if (polls.incrementAndGet() == 1) {
                sendJson(exchange, "{\"status\":\"processing\"}");
            } else {
                sendJson(exchange, "{\"status\":\"completed\",\"video_url\":\"" + baseUrl + "/video.mp4\"}");
            }
        });
        server.createContext("/video.mp4", exchange -> sendBytes(exchange, "mp4-bytes".getBytes(StandardCharsets.UTF_8)));

        String taskId = service.submitVideo(new AgnesMediaService.VideoRequest(
                "糖果熊挥手", "text", 5, "16:9", null, null, List.of(), List.of()));
        AgnesMediaService.GeneratedMedia media = service.pollVideo(taskId);

        assertEquals("task-123", taskId);
        assertEquals("video", media.kind());
        assertTrue(polls.get() >= 2);
        assertArrayEquals("mp4-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(media.path()));
    }

    @Test
    void videoPollingContinuesAfterTransientRateLimit() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/agnesapi", exchange -> {
            if (polls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
            } else {
                sendJson(exchange, "{\"status\":\"completed\",\"video_url\":\"" + baseUrl + "/video.mp4\"}");
            }
        });
        server.createContext("/video.mp4", exchange -> sendBytes(exchange, "mp4-bytes".getBytes(StandardCharsets.UTF_8)));

        AgnesMediaService.GeneratedMedia media = service.pollVideo("task-rate-limited");

        assertEquals("video", media.kind());
        assertEquals(2, polls.get());
        assertArrayEquals("mp4-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(media.path()));
    }

    @Test
    void videoPollingContinuesAfterTransientProviderUnavailable() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/agnesapi", exchange -> {
            if (polls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            } else {
                sendJson(exchange, "{\"status\":\"completed\",\"video_url\":\"" + baseUrl + "/video.mp4\"}");
            }
        });
        server.createContext("/video.mp4", exchange -> sendBytes(exchange, "mp4-bytes".getBytes(StandardCharsets.UTF_8)));

        AgnesMediaService.GeneratedMedia media = service.pollVideo("task-provider-unavailable");

        assertEquals("video", media.kind());
        assertEquals(2, polls.get());
        assertArrayEquals("mp4-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(media.path()));
    }

    @Test
    void videoJobsAreSerializedAndQueued() throws Exception {
        CountDownLatch firstSubmissionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSubmission = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        server.createContext("/v1/videos", exchange -> {
            int index = submissions.incrementAndGet();
            if (index == 1) {
                firstSubmissionStarted.countDown();
                try {
                    releaseFirstSubmission.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sendJson(exchange, "{\"video_id\":\"task-" + index + "\"}");
        });
        server.createContext("/agnesapi", exchange -> sendJson(exchange,
                "{\"status\":\"completed\",\"video_url\":\"" + baseUrl + "/video.mp4\"}"));
        server.createContext("/video.mp4", exchange -> sendBytes(exchange, "mp4-bytes".getBytes(StandardCharsets.UTF_8)));

        AgnesMediaService.VideoRequest request = new AgnesMediaService.VideoRequest(
                "糖果熊挥手", "text", 5, "16:9", null, null, List.of(), List.of());
        AgnesMediaService.VideoQueueTicket first = service.enqueueVideo(request);
        assertTrue(firstSubmissionStarted.await(1, TimeUnit.SECONDS));
        AgnesMediaService.VideoQueueTicket second = service.enqueueVideo(request);

        assertEquals(0, first.queuedAhead());
        assertEquals(1, second.queuedAhead());
        assertTrue(!second.future().isDone(), "前一个视频未结束时，后一个应留在队列");
        releaseFirstSubmission.countDown();
        assertTrue(first.future().get(2, TimeUnit.SECONDS) != null);
        assertTrue(second.future().get(2, TimeUnit.SECONDS) != null);
        assertEquals(2, submissions.get());
    }

    @Test
    void pricingGateStopsGenerationBeforeCreateRequest() {
        AtomicInteger imageRequests = new AtomicInteger();
        server.createContext("/pricing", exchange -> sendBytes(exchange,
                "agnes-image-2.5-flash price: $1".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/v1/images/generations", exchange -> {
            imageRequests.incrementAndGet();
            sendJson(exchange, "{\"data\":[]}");
        });
        service.close();
        service = new AgnesMediaService(
                true, "test-key", baseUrl + "/v1", baseUrl + "/agnesapi", baseUrl + "/pricing",
                outputDir.toString(), 5_000, 1_000, 1, true,
                HttpClient.newHttpClient(), Executors.newSingleThreadExecutor());

        AgnesMediaService.AgnesMediaException error = assertThrows(
                AgnesMediaService.AgnesMediaException.class,
                () -> service.generateImage("糖果熊", "1K", "1:1", List.of()));

        assertTrue(error.getMessage().contains("未发送请求"));
        assertEquals(0, imageRequests.get());
    }

    @Test
    void invalidVideoModeDoesNotCallProvider() {
        AtomicInteger videoRequests = new AtomicInteger();
        server.createContext("/v1/videos", exchange -> videoRequests.incrementAndGet());

        assertThrows(AgnesMediaService.AgnesMediaException.class, () -> service.submitVideo(
                new AgnesMediaService.VideoRequest(
                        "糖果熊", "text", 5, "16:9", "https://example.com/a.png", null, List.of(), List.of())));
        assertEquals(0, videoRequests.get());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendBytes(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
