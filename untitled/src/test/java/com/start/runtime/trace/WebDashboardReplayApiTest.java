package com.start.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.runtime.conversation.InMemoryConversationStateStore;
import com.start.service.AIDatabaseService;
import com.start.service.ConversationInterpreter;
import com.start.service.ManualMessageReplayService;
import com.start.service.MainModelPool;
import com.zaxxer.hikari.HikariDataSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDashboardReplayApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private String originalHost;
    private String originalToken;
    private String originalPort;
    private HikariDataSource dataSource;
    private ManualMessageReplayService replay;
    private WebDashboardListener dashboard;
    private URI endpoint;
    private final AtomicInteger generationCalls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<ManualMessageReplayService.Turn> generatedTurn =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void startDashboard() throws Exception {
        originalHost = System.getProperty("dashboard.host");
        originalToken = System.getProperty("dashboard.token");
        originalPort = System.getProperty("dashboard.port");
        System.setProperty("dashboard.host", "127.0.0.1");
        System.setProperty("dashboard.port", "0");
        System.setProperty("dashboard.token", "replay-test-token");

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:replay_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS media_artifacts");
            statement.execute("DROP TABLE IF EXISTS message_parts");
            statement.execute("DROP TABLE IF EXISTS messages");
            statement.execute("CREATE TABLE messages ("
                    + "id BIGINT PRIMARY KEY, user_id VARCHAR(50), group_id VARCHAR(50), content CLOB, "
                    + "raw_segments_json CLOB, raw_event_json CLOB, image_data CLOB, is_robot_reply BOOLEAN, "
                    + "sender_type VARCHAR(16), provider_message_id VARCHAR(64), reply_to_id BIGINT, "
                    + "reply_provider_message_id VARCHAR(64), availability_status VARCHAR(32), "
                    + "created_at TIMESTAMP, is_private BOOLEAN, retracted_at TIMESTAMP NULL, redacted_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE message_parts (id BIGINT PRIMARY KEY, message_id BIGINT, part_index INT, "
                    + "part_type VARCHAR(32), raw_part_json CLOB, text_value CLOB, source_url CLOB, "
                    + "provider_file_id VARCHAR(255), target_id VARCHAR(64), mime_type VARCHAR(128), byte_size BIGINT, "
                    + "sha256 VARCHAR(64), storage_key VARCHAR(512), availability_status VARCHAR(32), error_code VARCHAR(64))");
            statement.execute("CREATE TABLE media_artifacts (id BIGINT PRIMARY KEY, part_id BIGINT, "
                    + "artifact_type VARCHAR(40), status VARCHAR(32), content_json CLOB, error_code VARCHAR(64), "
                    + "error_detail CLOB, input_sha256 VARCHAR(64), parser_name VARCHAR(80), "
                    + "parser_version VARCHAR(40), completed_at TIMESTAMP)");
            statement.execute("INSERT INTO messages VALUES (1,'123','437625485','你好','[]',"
                    + "'{\"sender\":{\"nickname\":\"member\"}}',NULL,FALSE,'USER',NULL,NULL,NULL,"
                    + "'AVAILABLE',TIMESTAMP '2026-10-01 12:00:00',FALSE,NULL,NULL)");
        }
        var candidate = MainModelPool.Candidate.fromBaseUrl(
                "test", "http://127.0.0.1:1/v1", "test-model", "openai-compatible", "", "test-key");
        replay = new ManualMessageReplayService(dataSource,
                () -> new ManualMessageReplayService.ReplayProfile("test-model", candidate, null, null, null),
                (turn, profile) -> {
                    generationCalls.incrementAndGet();
                    generatedTurn.set(turn);
                    return new ManualMessageReplayService.ReplayGeneration(
                            turn.allowSilence() ? "<NO_REPLY>" : "可见回复",
                            "实际 system prompt\n" + turn.turnEvidence(), turn.modelUserText(), "OPENAI",
                            turn.imageDataUris().size(), "fake-request-digest", null, true);
                }, new ConversationInterpreter(new InMemoryConversationStateStore(
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>()), new AIDatabaseService()));
        dashboard = new WebDashboardListener();
        dashboard.setReplayService(replay);
        dashboard.start();
        Field serverField = WebDashboardListener.class.getDeclaredField("server");
        serverField.setAccessible(true);
        HttpServer server = (HttpServer) serverField.get(dashboard);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/replay");
    }

    @AfterEach
    void stopAndRestore() {
        if (dashboard != null) dashboard.stop();
        if (replay != null) replay.close();
        if (dataSource != null) dataSource.close();
        restore("dashboard.host", originalHost);
        restore("dashboard.token", originalToken);
        restore("dashboard.port", originalPort);
    }

    @Test
    void replayRequiresDashboardAuthAndExplicitTestFlagAndDoesNotWriteMessages() throws Exception {
        HttpResponse<String> unauthorized = post("{\"test\":true,\"action\":\"start\"}", false);
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> missingTest = post("{\"test\":false,\"action\":\"start\"}", true);
        assertEquals(400, missingTest.statusCode());
        assertTrue(missingTest.body().contains("test_flag_required"));

        HttpResponse<String> started = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":1,\"toLocalMessageId\":1}", true);
        assertEquals(200, started.statusCode());
        JsonNode startJson = JSON.readTree(started.body());
        String replayId = startJson.path("replayId").asText();
        assertFalse(replayId.isBlank());
        assertEquals("HISTORICAL", startJson.path("mode").asText());
        assertEquals("test-model", startJson.path("modelId").asText());
        assertEquals("test", startJson.path("candidateId").asText());
        assertTrue(startJson.path("endpoint").asText().contains("127.0.0.1"));
        assertEquals("OPENAI", startJson.path("protocol").asText());
        assertEquals("", startJson.path("apiKeyEnv").asText());
        assertTrue(startJson.path("executionScope").asText().contains("no auto-trigger"));

        HttpResponse<String> injected = post("{\"test\":true,\"action\":\"inject\","
                + "\"replayId\":\"" + replayId + "\",\"localMessageId\":1}", true);
        assertEquals(200, injected.statusCode());
        JsonNode response = JSON.readTree(injected.body());
        assertEquals("HISTORICAL", response.path("mode").asText());
        assertEquals("REPLY", response.path("decision").asText());
        assertEquals("可见回复", response.path("reply").asText());
        assertTrue(response.path("replyToCurrentMessage").asBoolean());
        assertEquals("test", response.path("candidateId").asText());
        assertTrue(response.path("endpoint").asText().contains("127.0.0.1"));
        assertEquals("", response.path("apiKeyEnv").asText());
        assertEquals("1", response.path("contextMessageIds").get(0).asText());
        assertTrue(response.path("systemPrompt").asText().contains("本轮待处理"));
        assertTrue(response.path("modelUserText").asText().contains("你好"));
        assertEquals("OPENAI", response.path("protocol").asText());
        assertTrue(response.path("modelCallMade").asBoolean());
        assertEquals("fake-request-digest", response.path("requestBodySha256").asText());
        assertTrue(response.path("turnEvidence").asText().contains("local_id=1"));
        assertEquals(0, response.path("imageEvidence").size());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void advanceIsExplicitHistoryOnlyAndDoesNotCallTheModel() throws Exception {
        HttpResponse<String> started = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":1,\"toLocalMessageId\":1}", true);
        assertEquals(200, started.statusCode());
        String replayId = JSON.readTree(started.body()).path("replayId").asText();

        HttpResponse<String> advanced = post("{\"test\":true,\"action\":\"advance\","
                + "\"replayId\":\"" + replayId + "\",\"localMessageId\":1}", true);
        assertEquals(200, advanced.statusCode());
        JsonNode response = JSON.readTree(advanced.body());
        assertEquals("HISTORICAL", response.path("mode").asText());
        assertEquals("OBSERVED", response.path("decision").asText());
        assertFalse(response.path("modelCallMade").asBoolean());
        assertTrue(response.path("nextLocalMessageId").isNull());
        assertEquals(0, generationCalls.get());
    }

    @Test
    void counterfactualModeMustBeExplicitInApi() throws Exception {
        HttpResponse<String> started = post("{\"test\":true,\"action\":\"start\","
                + "\"mode\":\"COUNTERFACTUAL\",\"groupId\":\"437625485\","
                + "\"fromLocalMessageId\":1,\"toLocalMessageId\":1}", true);
        assertEquals(200, started.statusCode());
        JsonNode startJson = JSON.readTree(started.body());
        assertEquals("COUNTERFACTUAL", startJson.path("mode").asText());

        HttpResponse<String> injected = post("{\"test\":true,\"action\":\"inject\","
                + "\"replayId\":\"" + startJson.path("replayId").asText()
                + "\",\"localMessageId\":1}", true);
        assertEquals(200, injected.statusCode());
        assertEquals("COUNTERFACTUAL", JSON.readTree(injected.body()).path("mode").asText());
    }

    @Test
    void proactiveCandidateReplayIsExplicitAndReportsSilentModelDecisionWithoutWriting() throws Exception {
        HttpResponse<String> noCandidateStart = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":1,\"toLocalMessageId\":1}", true);
        String noCandidateReplayId = JSON.readTree(noCandidateStart.body()).path("replayId").asText();
        HttpResponse<String> noCandidate = post("{\"test\":true,\"action\":\"inject\","
                + "\"evaluateProactiveCandidate\":true,\"replayId\":\"" + noCandidateReplayId
                + "\",\"localMessageId\":1}", true);

        assertEquals(200, noCandidate.statusCode());
        JsonNode skipped = JSON.readTree(noCandidate.body());
        assertEquals("NO_CANDIDATE", skipped.path("decision").asText());
        assertEquals("NOTHING", skipped.path("triggerEvent").asText());
        assertFalse(skipped.path("proactiveCandidate").asBoolean());
        assertFalse(skipped.path("modelCallMade").asBoolean());
        assertEquals(0, generationCalls.get());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO messages VALUES (2,'456','437625485','大家最近推荐什么游戏？','[]',"
                    + "'{\"sender\":{\"nickname\":\"member-456\"}}',NULL,FALSE,'USER',NULL,NULL,NULL,"
                    + "'AVAILABLE',TIMESTAMP '2026-10-01 12:01:00',FALSE,NULL,NULL)");
        }
        HttpResponse<String> candidateStart = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":2,\"toLocalMessageId\":2}", true);
        String candidateReplayId = JSON.readTree(candidateStart.body()).path("replayId").asText();
        HttpResponse<String> candidate = post("{\"test\":true,\"action\":\"inject\","
                + "\"evaluateProactiveCandidate\":true,\"replayId\":\"" + candidateReplayId
                + "\",\"localMessageId\":2}", true);

        assertEquals(200, candidate.statusCode());
        JsonNode response = JSON.readTree(candidate.body());
        assertEquals("PROBABILISTIC", response.path("triggerEvent").asText());
        assertTrue(response.path("proactiveCandidate").asBoolean());
        assertTrue(response.path("modelCallMade").asBoolean());
        assertEquals("SILENT", response.path("decision").asText());
        assertEquals("", response.path("reply").asText());
        assertTrue(generatedTurn.get().allowSilence());
        assertTrue(generatedTurn.get().prompt().contains("旧主动话题关键词命中的候选"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
            rows.next();
            assertEquals(2, rows.getInt(1));
        }
    }

    @Test
    void replayApiPreservesRawAndResolvedReplyIdentity() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO messages VALUES (2,'456','437625485','引用上一条','[]',"
                    + "'{\"sender\":{\"nickname\":\"member-456\"}}',NULL,FALSE,'USER',"
                    + "'provider-current',1,NULL,'AVAILABLE',TIMESTAMP '2026-10-01 12:01:00',FALSE,NULL,NULL)");
        }

        HttpResponse<String> started = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":2,\"toLocalMessageId\":2}", true);
        assertEquals(200, started.statusCode());
        JsonNode startJson = JSON.readTree(started.body());
        JsonNode message = startJson.path("messages").get(0);
        assertEquals("1", message.path("replyToRawId").asText());
        assertEquals("1", message.path("replyReference").asText());
        assertEquals("REPLY_TO_ID_LEGACY_AMBIGUOUS", message.path("replyReferenceSource").asText());
        assertEquals(1L, message.path("replyToLocalMessageId").asLong());
        assertEquals("RESOLVED_LEGACY_LOCAL_ID", message.path("replyResolutionStatus").asText());

        HttpResponse<String> injected = post("{\"test\":true,\"action\":\"inject\","
                + "\"replayId\":\"" + startJson.path("replayId").asText()
                + "\",\"localMessageId\":2}", true);
        assertEquals(200, injected.statusCode());
        JsonNode response = JSON.readTree(injected.body());
        assertEquals("1", response.path("replyToRawId").asText());
        assertEquals("1", response.path("replyReference").asText());
        assertEquals("REPLY_TO_ID_LEGACY_AMBIGUOUS", response.path("replyReferenceSource").asText());
        assertEquals(1L, response.path("replyToLocalMessageId").asLong());
        assertTrue(response.path("turnEvidence").asText().contains("reply_to_id_raw=1"));
        assertTrue(response.path("systemPrompt").asText().contains("reply_to_content=你好"));
    }

    @Test
    void imageEvidenceFailureReturnsCompleteJsonWithoutCallingModel() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO message_parts VALUES (2,1,0,'image','{}',NULL,"
                    + "'https://127.0.0.1/private.png?appid=1407&fileid=private-file&rkey=private-rkey',"
                    + "'opaque-file-id',NULL,'image/png',123,'failed-image-hash','opaque/storage/path',"
                    + "'UNAVAILABLE_REFERENCE','SOURCE_UNAVAILABLE')");
            statement.execute("INSERT INTO media_artifacts (id,part_id,artifact_type,status,content_json,error_code,"
                    + "error_detail,input_sha256,parser_name,parser_version,completed_at) VALUES "
                    + "(3,2,'IMAGE_DESCRIPTION','FAILED',NULL,'VISION_EMPTY_RESULT','no verifiable description',"
                    + "'failed-image-hash','vision','evidence-v1',TIMESTAMP '2026-10-01 12:00:00')");
        }

        HttpResponse<String> started = post("{\"test\":true,\"action\":\"start\","
                + "\"groupId\":\"437625485\",\"fromLocalMessageId\":1,\"toLocalMessageId\":1}", true);
        assertEquals(200, started.statusCode());
        String replayId = JSON.readTree(started.body()).path("replayId").asText();

        HttpResponse<String> injected = post("{\"test\":true,\"action\":\"inject\","
                + "\"replayId\":\"" + replayId + "\",\"localMessageId\":1}", true);

        assertEquals(424, injected.statusCode());
        JsonNode response = JSON.readTree(injected.body());
        assertEquals("required_image_evidence_unavailable", response.path("error").asText());
        JsonNode details = response.path("details");
        assertFalse(details.path("modelCallMade").asBoolean());
        assertTrue(details.path("turnEvidence").asText().contains("VISION_EMPTY_RESULT"));
        JsonNode image = details.path("imageEvidence").get(0);
        assertEquals(1L, image.path("messageLocalId").asLong());
        assertEquals(2L, image.path("partId").asLong());
        assertEquals(3L, image.path("artifactId").asLong());
        assertEquals("FAILED", image.path("status").asText());
        assertEquals("VISION_EMPTY_RESULT", image.path("errorCode").asText());
        assertEquals("failed-image-hash", image.path("sha256").asText());
        assertEquals("2026-10-01T04:00:00Z", image.path("completedAt").asText());
        assertTrue(image.path("sourceReferencePresent").isBoolean());
        assertEquals("https", image.path("sourceScheme").asText());
        assertEquals("127.0.0.1", image.path("sourceHost").asText());
        assertTrue(image.path("sourceLength").asInt() > 0);
        assertEquals("[\"appid\",\"fileid\",\"rkey\"]", image.path("sourceQueryParameterNames").toString());
        assertTrue(image.path("providerFileIdPresent").asBoolean());
        assertTrue(image.path("providerFileIdLength").asInt() > 0);
        assertTrue(image.path("storageKeyPresent").asBoolean());
        assertTrue(image.path("storageKeyLength").asInt() > 0);
        assertTrue(image.path("sourceLoadStatus").asText().equals("FAILED")
                || image.path("sourceLoadStatus").asText().equals("DISABLED"));
        if (image.path("sourceLoadStatus").asText().equals("FAILED")) {
            assertEquals("NON_PUBLIC_HOST_BLOCKED", image.path("sourceLoadErrorCode").asText());
        }
        assertFalse(image.path("attachedAsImage").asBoolean());
        assertFalse(injected.body().contains("private.png"));
        assertFalse(injected.body().contains("private-file"));
        assertFalse(injected.body().contains("private-rkey"));
        assertFalse(injected.body().contains("opaque-file-id"));
        assertFalse(injected.body().contains("opaque/storage/path"));
        assertFalse(injected.body().contains("sourceUrl"));
        assertEquals(0, generationCalls.get());
    }

    @Test
    void replayOnlyDashboardDoesNotRegisterOtherDashboardRoutes() throws Exception {
        String originalReplayHost = System.getProperty("dashboard.replay.host");
        String originalReplayPort = System.getProperty("dashboard.replay.port");
        String originalReplayToken = System.getProperty("dashboard.replay.token");
        System.setProperty("dashboard.replay.host", "127.0.0.1");
        System.setProperty("dashboard.replay.port", "0");
        System.setProperty("dashboard.replay.token", "replay-test-token");
        WebDashboardListener replayOnly = new WebDashboardListener(true);
        replayOnly.setReplayService(replay);
        replayOnly.start();
        try {
            assertTrue(WebDashboardListener.getInstance() == replayOnly);
            Field serverField = WebDashboardListener.class.getDeclaredField("server");
            serverField.setAccessible(true);
            HttpServer server = (HttpServer) serverField.get(replayOnly);
            int port = server.getAddress().getPort();

            HttpResponse<String> stickers = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stickers"))
                            .header("X-Dashboard-Token", "replay-test-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> dashboard = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                            .header("X-Dashboard-Token", "replay-test-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(404, stickers.statusCode());
            assertEquals(404, dashboard.statusCode());
        } finally {
            replayOnly.stop();
            restore("dashboard.replay.host", originalReplayHost);
            restore("dashboard.replay.port", originalReplayPort);
            restore("dashboard.replay.token", originalReplayToken);
        }
    }

    private HttpResponse<String> post(String body, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) builder.header("X-Dashboard-Token", "replay-test-token");
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
