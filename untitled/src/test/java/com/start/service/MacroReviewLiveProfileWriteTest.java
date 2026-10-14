package com.start.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.DatabaseConfig;
import com.start.model.AnalysisTask;
import com.start.repository.AnalysisTaskRepository;
import com.start.repository.MediaArtifactRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit paid-model E2E test. Writes only sanitized fixtures into the guarded local test database. */
@EnabledIfEnvironmentVariable(named = "RUN_MACRO_PROFILE_WRITE_TESTS", matches = "true")
class MacroReviewLiveProfileWriteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP_ID = "437625485";
    private static final String TARGET_USER_ID = "3611572057";
    private static final Pattern CQ_TAG = Pattern.compile("\\[CQ:[^\\]]*\\]");
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final String runKey = "codex-profile-e2e-" + UUID.randomUUID().toString().replace("-", "");
    private long taskId;
    private List<Long> seededMessageIds = List.of();

    @BeforeAll
    static void initializeLocalDatabase() {
        String url = System.getenv("DB_URL");
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3308/codex_profile_e2e?"),
                "此测试只允许写入本机 codex_profile_e2e 数据库");
        DatabaseConfig.initConnectionPool();
    }

    @Test
    void realModelObservationIsWrittenAndReturnedByProfileProvider() throws Exception {
        String rawMessages = System.getenv("MACRO_REVIEW_LIVE_JSON");
        String apiKey = System.getenv("MACRO_REVIEW_API_KEY");
        String replayResponse = System.getenv("MACRO_REVIEW_LIVE_RESPONSE_JSON");
        assertNotNull(rawMessages);
        assertFalse(rawMessages.isBlank());

        List<Map<String, Object>> messages = MAPPER.readValue(rawMessages,
                new TypeReference<>() {});
        messages.sort(Comparator.comparingLong(row -> number(row.get("id"))));
        assertTrue(messages.size() >= 8 && messages.size() <= 40);
        assertTrue(messages.stream().noneMatch(row -> bool(row.get("is_private"))));
        assertTrue(messages.stream().noneMatch(row -> !"AVAILABLE".equals(row.get("availability_status"))));
        assertTrue(messages.stream().anyMatch(row -> TARGET_USER_ID.equals(String.valueOf(row.get("user_id")))
                && List.of(63012L, 63013L).contains(number(row.get("id")))));

        seededMessageIds = seedMessages(messages);
        taskId = insertTask();

        MacroReviewService.ReviewResult review;
        if (replayResponse != null && !replayResponse.isBlank()) {
            review = new MacroReviewService.ReviewResult(MAPPER.readTree(replayResponse),
                    "gpt-5.6-luna", "replayed-live-response", 0, 0);
        } else {
            assertNotNull(apiKey);
            assertFalse(apiKey.isBlank());
            int timeoutMs = Integer.parseInt(System.getenv().getOrDefault("MACRO_REVIEW_LIVE_TIMEOUT_MS", "120000"));
            MacroReviewService model = new MacroReviewService(apiKey,
                    "https://www.wyy22.com/v1/chat/completions", "gpt-5.6-luna", timeoutMs,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), null, null);
            review = model.review(GROUP_ID, messages, Map.of());
        }
        var validated = EvidenceAnalysisWorker.validateMacroResult(review.result(), messages);

        AnalysisTask task = new AnalysisTask(taskId, runKey, "MACRO_REVIEW_BATCH", "PROCESSING", 0,
                GROUP_ID, TARGET_USER_ID, "{}", 1, 3, LocalDateTime.now(), "codex-live-profile-e2e",
                LocalDateTime.now().plusMinutes(5));
        ProfileFactRepository facts = new ProfileFactRepository(DatabaseConfig.getDataSource());
        EvidenceAnalysisWorker worker = new EvidenceAnalysisWorker(new AnalysisTaskRepository(),
                new MediaArtifactRepository(), new MessageRepository(), facts, null, "codex-live-profile-e2e");
        var writeSummary = worker.persistMacroObservations(task, GROUP_ID, messages, validated, review);

        var activeFacts = facts.findActiveByUserId(TARGET_USER_ID, false, 20);
        String profileContext = new ProfileProvider().getProfileContext(TARGET_USER_ID, GROUP_ID);
        boolean relevantFactRead = activeFacts.stream().anyMatch(fact ->
                ("interest".equals(fact.kind()) || "preference".equals(fact.kind()))
                        && fact.claim().contains("类人"));

        System.out.println("LIVE_PROFILE_E2E=" + MAPPER.writeValueAsString(Map.of(
                "model", review.modelName(),
                "window_messages", messages.size(),
                "input_tokens", review.inputTokens(),
                "output_tokens", review.outputTokens(),
                "validation", validated.path("validation"),
                "write_summary", writeSummary,
                "observations", validated.path("observations"),
                "relevant_active_fact_read", relevantFactRead,
                "profile_context", profileContext)));

        assertTrue(validated.path("validation").path("valid_observations").asInt() > 0,
                "模型未返回可追溯且主体匹配的观察");
        assertTrue(writeSummary.path("candidate_rows_written").asInt() > 0,
                "没有任何观察进入事实候选写入路径");
        assertTrue(relevantFactRead, "正式画像库中没有可读取的相关兴趣事实");
        assertTrue(profileContext.contains("类人"), "ProfileProvider 未返回刚写入的相关画像");
    }

    @AfterEach
    void cleanLocalTestRows() throws Exception {
        if (taskId == 0 && seededMessageIds.isEmpty()) return;
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (taskId > 0) {
                    execute(conn, "DELETE FROM profile_fact_changes WHERE entity_type='PROFILE_FACT' AND entity_id IN " +
                            "(SELECT id FROM profile_facts WHERE source_candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?))", taskId);
                    execute(conn, "DELETE FROM profile_fact_conflicts WHERE incoming_candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?) OR existing_fact_id IN " +
                            "(SELECT id FROM profile_facts WHERE source_candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?))", taskId, taskId);
                    execute(conn, "DELETE FROM profile_fact_evidence WHERE candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?) OR fact_id IN " +
                            "(SELECT id FROM profile_facts WHERE source_candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?))", taskId, taskId);
                    execute(conn, "DELETE FROM profile_facts WHERE source_candidate_id IN " +
                            "(SELECT id FROM profile_fact_candidates WHERE analysis_task_id=?)", taskId);
                    execute(conn, "DELETE FROM profile_fact_candidates WHERE analysis_task_id=?", taskId);
                    execute(conn, "DELETE FROM model_analysis_tasks WHERE id=?", taskId);
                }
                if (!seededMessageIds.isEmpty()) {
                    String placeholders = String.join(",", java.util.Collections.nCopies(seededMessageIds.size(), "?"));
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM messages WHERE id IN (" + placeholders + ") AND source_event_key LIKE ?")) {
                        int index = 1;
                        for (long id : seededMessageIds) ps.setLong(index++, id);
                        ps.setString(index, runKey + "%");
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private List<Long> seedMessages(List<Map<String, Object>> messages) throws Exception {
        String sql = "INSERT INTO messages (id, session_id, user_id, content, raw_content, source_event_key, " +
                "is_robot_reply, is_private, group_id, reply_to_id, sender_type, availability_status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : messages) {
                long id = number(row.get("id"));
                String content = sanitize(String.valueOf(row.getOrDefault("content", "")));
                ids.add(id);
                ps.setLong(1, id);
                ps.setString(2, runKey);
                ps.setString(3, String.valueOf(row.get("user_id")));
                ps.setString(4, content);
                ps.setString(5, content);
                ps.setString(6, runKey + "-" + id);
                ps.setBoolean(7, bool(row.get("is_robot_reply")));
                ps.setBoolean(8, false);
                ps.setString(9, GROUP_ID);
                if (row.get("reply_to_id") == null) ps.setNull(10, java.sql.Types.BIGINT);
                else ps.setLong(10, number(row.get("reply_to_id")));
                ps.setString(11, String.valueOf(row.get("sender_type")));
                ps.setString(12, "AVAILABLE");
                ps.setTimestamp(13, Timestamp.valueOf(LocalDateTime.parse(
                        String.valueOf(row.get("created_at")).replace(' ', 'T'))));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        for (Map<String, Object> row : messages) {
            String content = sanitize(String.valueOf(row.getOrDefault("content", "")));
            row.put("content", content);
            row.put("raw_content", content);
            row.put("is_private", false);
            row.put("availability_status", "AVAILABLE");
        }
        return List.copyOf(ids);
    }

    private long insertTask() throws Exception {
        String sql = "INSERT INTO model_analysis_tasks (task_key, task_type, status, priority, scope_group_id, " +
                "scope_user_id, input_refs, attempt_count, max_attempts, not_before, lease_owner, lease_until) " +
                "VALUES (?, 'MACRO_REVIEW_BATCH', 'PROCESSING', 0, ?, ?, '{}', 1, 3, CURRENT_TIMESTAMP(3), ?, " +
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE))";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, runKey);
            ps.setString(2, GROUP_ID);
            ps.setString(3, TARGET_USER_ID);
            ps.setString(4, "codex-live-profile-e2e");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("local test task id missing");
                return keys.getLong(1);
            }
        }
    }

    private static void execute(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static String sanitize(String content) {
        return URL.matcher(CQ_TAG.matcher(content).replaceAll("[media]")).replaceAll("[url removed]");
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
}
