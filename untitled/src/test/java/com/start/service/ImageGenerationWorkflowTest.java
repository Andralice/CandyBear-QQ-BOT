package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.agent.GenerateMediaTool;
import com.start.agent.QueryImagesTool;
import com.start.repository.MessageRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real query -> tool -> Agnes HTTP contract; no QQ messages or external generations are sent. */
class ImageGenerationWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=";
    @TempDir Path temp;
    private Path image;
    private HttpServer server;
    private AgnesMediaService service;
    private Repo repo;
    private QueryImagesTool query;
    private GenerateMediaTool tool;
    private final AtomicInteger submissions = new AtomicInteger();
    private JsonNode submitted;

    @BeforeEach void setup() throws Exception {
        image = temp.resolve("original.png");
        Files.write(image, Base64.getDecoder().decode(PNG));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            submissions.incrementAndGet();
            submitted = JSON.readTree(exchange.getRequestBody());
            byte[] body = ("{\"data\":[{\"b64_json\":\"" + PNG + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new AgnesMediaService(true, "test-key", base + "/v1", base + "/agnesapi", "",
                temp.toString(), 3000, 3000, 100, false, HttpClient.newHttpClient(), Executors.newSingleThreadExecutor());
        repo = new Repo();
        repo.rows.add(row(42, 1));
        query = new QueryImagesTool(repo, "group", "session", "alice");
        tool = new GenerateMediaTool(null, service, "group", "alice", "session", GenerateMediaTool.Kind.IMAGE, query);
    }

    @AfterEach void close() { service.close(); server.stop(0); }

    @Test void firstBatchOnlyLoadsRulesThenSelectedOriginalReachesAgnes() throws Exception {
        Map<String, Object> args = edit("42:1");
        assertTrue(tool.execute(args).startsWith(GenerateMediaTool.REQUIREMENTS_REQUIRED));
        assertTrue(tool.execute(args).startsWith(GenerateMediaTool.REQUIREMENTS_REQUIRED), "同批重复调用不能提前生成");
        assertEquals(0, submissions.get());
        List<Map<String, Object>> messages = new ArrayList<>();
        tool.injectImageRequirements(messages);
        tool.injectImageRequirements(messages);
        assertEquals(1, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        String rules = (String) messages.get(0).get("content");
        assertTrue(rules.contains("query_message_context") && rules.contains("超过五分钟") && rules.contains("query_user_profile"));
        assertTrue(rules.contains("电影写实") && rules.contains("纪实街拍")
                && rules.contains("商业人像") && rules.contains("产品/场景写实"));
        assertTrue(rules.contains("统一白平衡和色彩分级") && rules.contains("细腻微观纹理")
                && rules.contains("不能只写“不要彩色”"));
        JsonNode found = JSON.readTree(query.execute(Map.of("provider_message_id", "qq42")));
        assertEquals("42:1", found.path("items").get(0).path("image_id").asText());
        assertFalse(found.path("items").get(0).path("requires_confirmation").asBoolean());
        assertFalse(found.toString().contains(image.toString()), "本地路径不能塞进模型上下文");
        tool.execute(args);
        assertEquals(1, submissions.get());
        assertEquals("data:image/png;base64," + PNG, submitted.path("extra_body").path("image").get(0).asText());
        assertTrue(submitted.path("prompt").asText().contains("只将背景改成蓝色"));
    }

    @Test void olderImageRequiresConfirmationEvenWhenReplyTargetsIt() throws Exception {
        repo.rows.clear(); repo.rows.add(row(42, 6));
        tool.injectImageRequirements(new ArrayList<>());
        JsonNode found = JSON.readTree(query.execute(Map.of("provider_message_id", "qq42")));
        assertTrue(found.path("items").get(0).path("requires_confirmation").asBoolean());
        assertTrue(tool.execute(edit("42:1")).contains("必须先询问用户"));
        assertEquals(0, submissions.get());
        Map<String, Object> confirmed = new HashMap<>(edit("42:1"));
        confirmed.put("confirmed_image_ids", List.of("42:1"));
        confirmed.put("selection_reason", "用户已确认使用刚才询问的42号消息图片");
        tool.execute(confirmed);
        assertEquals(1, submissions.get());
    }

    @Test void missingOriginalUnknownHandleAndRetractionNeverGenerate() {
        tool.injectImageRequirements(new ArrayList<>());
        assertTrue(tool.execute(edit("42:1")).contains("尚未查询到图片"));
        Map<String, Object> missing = new HashMap<>(edit("42:1")); missing.remove("reference_image_ids");
        assertTrue(tool.execute(missing).contains("必须先定位原图"));
        query.execute(Map.of("provider_message_id", "qq42"));
        repo.rows.clear();
        assertTrue(tool.execute(edit("42:1")).contains("已不可用"));
        assertEquals(0, submissions.get());
    }

    @Test void blankKeywordUsesCurrentSenderAndSpecificReplyDoesNotGuessSender() throws Exception {
        query.execute(Map.of());
        assertEquals("alice", repo.sender);
        assertFalse(repo.includeOlder);
        query.execute(Map.of("provider_message_id", "qq42"));
        assertTrue(repo.sender == null || repo.sender.isBlank());
        assertEquals("qq42", repo.providerId);
        query.execute(Map.of("user_id", "candybear", "include_older", true));
        assertEquals("candybear", repo.sender);
        assertTrue(repo.includeOlder);
        query.execute(Map.of("group_id", "other"));
        assertEquals("group", repo.group);
    }

    @Test void multiImageMessageExposesIndividualIdsAndVisionDescriptions() throws Exception {
        Map<String, Object> row = row(42, 1);
        row.put("is_robot_reply", false);
        row.put("content", "给你两张参考图");
        row.put("raw_segments_json", "[{\"type\":\"image\",\"data\":{\"url\":\"https://example.com/a.png\"}},{\"type\":\"image\",\"data\":{\"url\":\"https://example.com/b.png\"}}]");
        row.put("image_data", "[{\"url\":\"https://example.com/a.png\",\"desc\":\"红色裙子\"},{\"url\":\"https://example.com/b.png\",\"desc\":\"蓝色大海\"}]");
        repo.rows.clear(); repo.rows.add(row);
        JsonNode items = JSON.readTree(query.execute(Map.of("message_id", "42"))).path("items");
        assertEquals(2, items.size());
        assertEquals("42:1", items.get(0).path("image_id").asText());
        assertEquals("42:2", items.get(1).path("image_id").asText());
        assertEquals("蓝色大海", items.get(1).path("description").asText());
        assertFalse(items.toString().contains("https://"));
    }

    @Test void loaderRejectsLocalNetworkNonImagesAndArbitraryLocalFiles() throws Exception {
        assertThrows(AgnesMediaService.AgnesMediaException.class, () -> service.loadReferenceImage("http://127.0.0.1/private.png", false));
        assertThrows(AgnesMediaService.AgnesMediaException.class, () -> service.loadReferenceImage(image.toString(), false));
        Files.writeString(image, "<html>not a picture</html>");
        assertThrows(AgnesMediaService.AgnesMediaException.class, () -> service.loadReferenceImage(image.toString(), true));
        Path nested = Files.createDirectory(temp.resolve("nested"));
        try (AgnesMediaService restricted = new AgnesMediaService(true, "test", "", "", "", nested.toString(),
                1000, 1000, 100, false, HttpClient.newHttpClient(), Executors.newSingleThreadExecutor())) {
            assertThrows(AgnesMediaService.AgnesMediaException.class, () -> restricted.loadReferenceImage(image.toString(), true));
        }
        assertEquals(0, submissions.get());
    }

    @Test void textGenerationAlsoNeedsContextSummary() {
        tool.injectImageRequirements(new ArrayList<>());
        assertTrue(tool.execute(Map.of("prompt", "画一只熊", "mode", "text")).contains("requirements_summary"));
        assertEquals(0, submissions.get());
        tool.execute(Map.of("prompt", "画一只熊", "mode", "text", "requirements_summary", "前文要求简笔画，当前画熊"));
        assertEquals(1, submissions.get());
        assertFalse(submitted.path("extra_body").has("image"));
    }

    private Map<String, Object> row(long id, int minutesAgo) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id); row.put("provider_message_id", "qq" + id); row.put("user_id", "candybear");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutesAgo)));
        row.put("is_robot_reply", true); row.put("content", "[CQ:image,file=" + image + "]");
        return row;
    }

    private Map<String, Object> edit(String id) {
        return Map.of("prompt", "只将背景改成蓝色，保留人物面貌、姿势和构图", "mode", "edit",
                "requirements_summary", "用户回复了原图，要求改背景，前文要求保留人物", "reference_image_ids", List.of(id),
                "selection_reason", "用户回复了qq42消息，只有一张图，与改背景的要求一致");
    }

    private static class Repo extends MessageRepository {
        final List<Map<String, Object>> rows = new ArrayList<>();
        String sender, group, providerId; boolean includeOlder;
        @Override public DatabaseResult<List<Map<String, Object>>> searchImageMessages(String group, String session, String sender,
                String keyword, long id, String providerId, boolean older, LocalDateTime time, long before, int limit) {
            this.group = group; this.sender = sender; this.providerId = providerId; this.includeOlder = older;
            return DatabaseResult.success(rows.stream().filter(row -> id == 0 || ((Number) row.get("id")).longValue() == id).toList());
        }
    }
}
