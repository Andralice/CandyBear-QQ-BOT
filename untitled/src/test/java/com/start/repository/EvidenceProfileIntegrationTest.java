package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.AnalysisTask;
import com.start.model.ProfileFactCandidateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence profile integration tests. Disabled unless a real MySQL is explicitly requested.
 *
 * Run with: $env:RUN_DB_TESTS="true"; mvn test -Dtest=EvidenceProfileIntegrationTest
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class EvidenceProfileIntegrationTest {
    private static AnalysisTaskRepository taskRepository;
    private static ProfileFactRepository factRepository;
    private static ProfilePrivacyRepository privacyRepository;
    private static MessageRepository messageRepository;
    private static ConversationEventRepository eventRepository;

    private String keyPrefix;
    private String subjectUserId;
    private String groupId;

    @BeforeAll
    static void setUpAll() {
        taskRepository = new AnalysisTaskRepository();
        factRepository = new ProfileFactRepository(DatabaseConfig.getDataSource());
        privacyRepository = new ProfilePrivacyRepository(DatabaseConfig.getDataSource());
        messageRepository = new MessageRepository();
        eventRepository = new ConversationEventRepository();
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        keyPrefix = "it-evidence-" + suffix;
        subjectUserId = "it-user-" + suffix;
        groupId = "it-group-" + suffix;
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            execute(conn, "DELETE em FROM conversation_event_messages em JOIN conversation_events e ON e.id=em.event_id " +
                    "WHERE e.analysis_task_id IN (SELECT id FROM model_analysis_tasks WHERE task_key LIKE ?)", keyPrefix + "%");
            execute(conn, "DELETE FROM group_atmosphere_events WHERE analysis_task_id IN " +
                    "(SELECT id FROM model_analysis_tasks WHERE task_key LIKE ?)", keyPrefix + "%");
            execute(conn, "DELETE FROM conversation_events WHERE analysis_task_id IN " +
                    "(SELECT id FROM model_analysis_tasks WHERE task_key LIKE ?)", keyPrefix + "%");

            execute(conn, "DELETE FROM profile_fact_evidence WHERE candidate_id IN " +
                    "(SELECT id FROM profile_fact_candidates WHERE subject_user_id=?) OR fact_id IN " +
                    "(SELECT id FROM profile_facts WHERE subject_user_id=?) OR message_id IN " +
                    "(SELECT id FROM messages WHERE source_event_key LIKE ?)", subjectUserId, subjectUserId, keyPrefix + "%");
            execute(conn, "DELETE FROM profile_fact_conflicts WHERE incoming_candidate_id IN " +
                    "(SELECT id FROM profile_fact_candidates WHERE subject_user_id=?) OR existing_fact_id IN " +
                    "(SELECT id FROM profile_facts WHERE subject_user_id=?)", subjectUserId, subjectUserId);
            execute(conn, "DELETE FROM profile_fact_changes WHERE entity_type='PROFILE_FACT' AND entity_id IN " +
                    "(SELECT id FROM profile_facts WHERE subject_user_id=?) OR " +
                    "(reason_code='USER_PRIVACY_ERASURE' AND actor_id=?)", subjectUserId, subjectUserId);
            execute(conn, "DELETE FROM profile_facts WHERE subject_user_id=?", subjectUserId);
            execute(conn, "DELETE FROM profile_fact_candidates WHERE subject_user_id=?", subjectUserId);
            execute(conn, "DELETE FROM model_analysis_tasks WHERE task_key LIKE ?", keyPrefix + "%");
            execute(conn, "DELETE FROM profile_privacy_requests WHERE requester_user_id=?", subjectUserId);
            execute(conn, "DELETE ma FROM media_artifacts ma JOIN message_parts p ON p.id=ma.part_id " +
                    "WHERE p.message_id IN (SELECT id FROM messages WHERE source_event_key LIKE ?)", keyPrefix + "%");
            execute(conn, "DELETE FROM message_parts WHERE message_id IN " +
                    "(SELECT id FROM messages WHERE source_event_key LIKE ?)", keyPrefix + "%");
            execute(conn, "DELETE FROM messages WHERE source_event_key LIKE ?", keyPrefix + "%");
        }
    }

    @Test
    void evidenceBindingPreservesPartArtifactAndIsIdempotent() throws Exception {
        long messageId = insertMessage("media-binding", LocalDateTime.now());
        long partId = insertMessagePart(messageId, "image", "AVAILABLE");
        long artifactId = insertMediaArtifact(partId, "SUCCEEDED");
        long candidate = candidate("interest", "image-topic", "关注图片话题", "OBSERVED_BEHAVIOR", "ASSERT", 0.70,
                List.of(messageId));

        factRepository.addEvidence(candidate, messageId, partId, artifactId, "image", "{\"test\":true}",
                "AVAILABLE", 0.8);
        factRepository.addEvidence(candidate, messageId, partId, artifactId, "image", "{\"test\":true}",
                "AVAILABLE", 0.8);

        assertEquals(1, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE candidate_id=?", candidate));
        assertEquals(partId, ((Number) scalarValue("SELECT part_id FROM profile_fact_evidence WHERE candidate_id=?", candidate)).longValue());
        assertEquals(artifactId, ((Number) scalarValue("SELECT artifact_id FROM profile_fact_evidence WHERE candidate_id=?", candidate)).longValue());
        assertEquals("image", scalar("SELECT modality FROM profile_fact_evidence WHERE candidate_id=?", candidate));
    }

    @Test
    void unavailableMediaCannotPromoteCandidate() throws Exception {
        long messageId = insertMessage("media-unavailable", LocalDateTime.now(), "");
        long partId = insertMessagePart(messageId, "record", "UNAVAILABLE");
        long artifactId = insertMediaArtifact(partId, "UNAVAILABLE");
        long candidate = candidate("recent_state", "unavailable-audio", "发送了一段语音", "SELF_REPORTED", "ASSERT",
                0.90, List.of(messageId));
        factRepository.addEvidence(candidate, messageId, partId, artifactId, "audio", "{\"test\":true}",
                "UNAVAILABLE", 1.0);

        assertEquals(0, factRepository.promoteCandidate(candidate));
        assertEquals("CANDIDATE", scalar("SELECT status FROM profile_fact_candidates WHERE id=?", candidate));
        assertEquals(0, count("SELECT COUNT(*) FROM profile_facts WHERE subject_user_id=? AND claim_key=?",
                subjectUserId, "unavailable-audio"));
    }

    @Test
    void successfulMediaArtifactCanPromoteCandidate() throws Exception {
        long messageId = insertMessage("media-success", LocalDateTime.now(), "");
        long partId = insertMessagePart(messageId, "record", "AVAILABLE");
        long artifactId = insertMediaArtifact(partId, "SUCCEEDED");
        long candidate = candidate("recent_state", "available-audio", "发送了一段可解析语音", "SELF_REPORTED", "ASSERT",
                0.90, List.of(messageId));
        factRepository.addEvidence(candidate, messageId, partId, artifactId, "audio", "{\"test\":true}",
                "AVAILABLE", 1.0);

        long fact = factRepository.promoteCandidate(candidate);

        assertTrue(fact > 0);
        assertEquals("PROMOTED", scalar("SELECT status FROM profile_fact_candidates WHERE id=?", candidate));
        assertEquals(fact, ((Number) scalarValue("SELECT fact_id FROM profile_fact_evidence WHERE candidate_id=?", candidate)).longValue());
    }

    @Test
    void staleFactExpiresWithAuditHistory() throws Exception {
        LocalDateTime observedAt = LocalDateTime.now().minusDays(40);
        long messageId = insertMessage("stale-fact", observedAt);
        long candidate = candidate("recent_state", "stale-state", "曾经在旅行", "SELF_REPORTED", "ASSERT",
                0.90, List.of(messageId), observedAt, null);
        addEvidence(candidate, messageId);
        long fact = factRepository.promoteCandidate(candidate);
        assertTrue(fact > 0);

        int expired = factRepository.expireInactiveFacts(LocalDateTime.now(),
                LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(365));

        assertEquals(1, expired);
        assertEquals("EXPIRED", scalar("SELECT status FROM profile_facts WHERE id=?", fact));
        assertEquals("STALE_EVIDENCE", scalar("SELECT reason_code FROM profile_fact_changes " +
                "WHERE entity_id=? AND to_status='EXPIRED' ORDER BY id DESC LIMIT 1", fact));
    }

    @Test
    void concurrentClaimAllowsOnlyOneLease() throws Exception {
        insertTask(keyPrefix + "-claim", "TEST_CLAIM", Integer.MAX_VALUE, 3, "PENDING", LocalDateTime.now(), null, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<AnalysisTask>> first = executor.submit(() -> taskRepository.claimNext("worker-a", 60));
            Future<Optional<AnalysisTask>> second = executor.submit(() -> taskRepository.claimNext("worker-b", 60));

            boolean firstClaimed = first.get(10, TimeUnit.SECONDS).isPresent();
            boolean secondClaimed = second.get(10, TimeUnit.SECONDS).isPresent();
            assertTrue(firstClaimed ^ secondClaimed, "同一任务只能被一个 worker 抢到");
        } finally {
            executor.shutdownNow();
        }
        assertEquals("PROCESSING", scalar("SELECT status FROM model_analysis_tasks WHERE task_key=?", keyPrefix + "-claim"));
    }

    @Test
    void expiredLeaseIsRecoveredAndRetryEventuallyBecomesDead() throws Exception {
        insertTask(keyPrefix + "-lease", "TEST_LEASE", Integer.MAX_VALUE, 3, "PROCESSING",
                LocalDateTime.now().minusMinutes(2), LocalDateTime.now().minusMinutes(1), 1);
        assertTrue(taskRepository.recoverExpiredNow() >= 1);
        Optional<AnalysisTask> recovered = taskRepository.claimNext("worker-recovery", 60);
        assertTrue(recovered.isPresent());
        assertEquals(2, recovered.get().attemptCount());

        insertTask(keyPrefix + "-retry", "TEST_RETRY", Integer.MAX_VALUE, 2, "PENDING", LocalDateTime.now(), null, 0);
        AnalysisTask first = taskRepository.claimNext("worker-retry", 60).orElseThrow();
        taskRepository.fail(first, "TEMPORARY_TEST_ERROR", "first failure");
        forceReady(keyPrefix + "-retry");
        AnalysisTask second = taskRepository.claimNext("worker-retry", 60).orElseThrow();
        taskRepository.fail(second, "PERMANENT_TEST_ERROR", "second failure");

        assertEquals("DEAD", scalar("SELECT status FROM model_analysis_tasks WHERE task_key=?", keyPrefix + "-retry"));
        assertTrue(taskRepository.claimNext("worker-retry", 60).isEmpty());
    }

    @Test
    void repeatedEvidencePromotesStablePersonalFact() throws Exception {
        long first = insertMessage("m1", LocalDateTime.now().minusDays(2));
        long second = insertMessage("m2", LocalDateTime.now().minusDays(1));
        long third = insertMessage("m3", LocalDateTime.now());
        long candidate = candidate("interest", "game", "喜欢某款游戏", "OBSERVED_BEHAVIOR", "ASSERT", 0.78,
                List.of(first, second, third));
        addEvidence(candidate, first);
        addEvidence(candidate, second);
        addEvidence(candidate, third);

        long fact = factRepository.promoteCandidate(candidate);

        assertTrue(fact > 0);
        assertEquals("PROMOTED", scalar("SELECT status FROM profile_fact_candidates WHERE id=?", candidate));
        assertEquals("STABLE", scalar("SELECT stability FROM profile_facts WHERE id=?", fact));
        assertEquals("PERSONAL", scalar("SELECT scope FROM profile_facts WHERE id=?", fact));
        assertEquals(1, factRepository.findActiveByUserId(subjectUserId, false, 10).size());
    }

    @Test
    void explicitDenialSupersedesOldFactAndLeavesConflictHistory() throws Exception {
        long oldMessage = insertMessage("old", LocalDateTime.now().minusDays(2));
        long oldCandidate = candidate("preference", "coffee", "喜欢咖啡", "SELF_REPORTED", "ASSERT", 0.90, List.of(oldMessage));
        addEvidence(oldCandidate, oldMessage);
        long oldFact = factRepository.promoteCandidate(oldCandidate);
        assertTrue(oldFact > 0);

        long denialMessage = insertMessage("denial", LocalDateTime.now());
        long denialCandidate = candidate("preference", "coffee", "不喜欢咖啡", "SELF_REPORTED", "DENY", 0.95, List.of(denialMessage));
        addEvidence(denialCandidate, denialMessage);
        long denialFact = factRepository.promoteCandidate(denialCandidate);

        assertTrue(denialFact > 0);
        assertNotEquals(oldFact, denialFact);
        assertEquals("SUPERSEDED", scalar("SELECT status FROM profile_facts WHERE id=?", oldFact));
        assertEquals("ACTIVE", scalar("SELECT status FROM profile_facts WHERE id=?", denialFact));
        assertEquals("RESOLVED", scalar("SELECT status FROM profile_fact_conflicts WHERE existing_fact_id=? AND incoming_candidate_id=?",
                oldFact, denialCandidate));
        assertEquals("EXPLICIT_DENIAL", scalar("SELECT resolution FROM profile_fact_conflicts WHERE existing_fact_id=? AND incoming_candidate_id=?",
                oldFact, denialCandidate));
    }

    @Test
    void retractedEvidenceInvalidatesCandidateAndFormalFact() throws Exception {
        String providerMessageId = keyPrefix + "-provider";
        long messageId = insertMessage(providerMessageId, LocalDateTime.now());
        long candidate = candidate("recent_state", "trip", "正在旅行", "SELF_REPORTED", "ASSERT", 0.90, List.of(messageId));
        addEvidence(candidate, messageId);
        long fact = factRepository.promoteCandidate(candidate);
        assertTrue(fact > 0);

        var result = messageRepository.handleRetraction(providerMessageId, groupId, "onebot-test", keyPrefix);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData());
        assertEquals("REJECTED", scalar("SELECT status FROM profile_fact_candidates WHERE id=?", candidate));
        assertEquals("EXPIRED", scalar("SELECT status FROM profile_facts WHERE id=?", fact));
        assertEquals("RETRACTED", scalar("SELECT evidence_role FROM profile_fact_evidence WHERE candidate_id=?", candidate));
    }

    @Test
    void eventMessagesAndAtmosphereArePersistedTogether() throws Exception {
        long taskId = insertTask(keyPrefix + "-event", "TEST_EVENT", 1, 1, "PENDING", LocalDateTime.now(), null, 0);
        long first = insertMessage("event-1", LocalDateTime.now().minusMinutes(1));
        long second = insertMessage("event-2", LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();

        long eventId = eventRepository.insertClosedEventWithAtmosphere(groupId, "topic", "testing", "测试话题",
                "[\"" + subjectUserId + "\"]", "测试话题", now.minusMinutes(1), now,
                List.of(new ConversationEventRepository.EventMessage(first, 0, "CONTEXT", 0.5),
                        new ConversationEventRepository.EventMessage(second, 1, "TARGET", 0.9)),
                0.8, now, now.plusHours(12), taskId);

        assertEquals(2, count("SELECT COUNT(*) FROM conversation_event_messages WHERE event_id=?", eventId));
        assertEquals(1, count("SELECT COUNT(*) FROM group_atmosphere_events WHERE event_id=?", eventId));
        assertEquals(1, count("SELECT COUNT(*) FROM conversation_events WHERE id=? AND status='CLOSED'", eventId));
    }

    @Test
    void invalidAtmospherePayloadRollsBackTheWholeEventWrite() throws Exception {
        long taskId = insertTask(keyPrefix + "-rollback", "TEST_EVENT_ROLLBACK", Integer.MAX_VALUE, 1,
                "PENDING", LocalDateTime.now(), null, 0);
        long messageId = insertMessage("rollback", LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();

        assertThrows(SQLException.class, () -> eventRepository.insertClosedEventWithAtmosphere(groupId, "topic",
                keyPrefix + "-rollback-topic", "rollback", "not-json", "rollback", now, now,
                List.of(new ConversationEventRepository.EventMessage(messageId, 0, "TARGET", 1.0)),
                0.5, now, now.plusHours(1), taskId));

        assertEquals(0, count("SELECT COUNT(*) FROM conversation_events WHERE topic_key=?", keyPrefix + "-rollback-topic"));
    }

    @Test
    void confirmedPrivacyErasureRemovesProfileDataButKeepsChatArchive() throws Exception {
        long messageId = insertMessage("privacy", LocalDateTime.now(), "保留在聊天记录中的原文");
        long partId = insertMessagePart(messageId, "image", "AVAILABLE");
        insertMediaArtifact(partId, "SUCCEEDED");
        long candidate = candidate("interest", "privacy-game", "喜欢某款游戏", "SELF_REPORTED", "ASSERT",
                0.90, List.of(messageId));
        addEvidence(candidate, messageId);
        long fact = factRepository.promoteCandidate(candidate);
        assertTrue(fact > 0);
        insertTask(keyPrefix + "-privacy-task", "EXTRACT_PROFILE_FACTS", 1, 3,
                "PENDING", LocalDateTime.now(), null, 0);

        ProfilePrivacyRepository.ErasureRequest request = privacyRepository.createRequest(subjectUserId);
        assertEquals(8, request.confirmationCode().length());
        assertFalse(privacyRepository.erase(subjectUserId, "AAAAAAAA").confirmed());

        ProfilePrivacyRepository.ErasureResult result = privacyRepository.erase(
                subjectUserId, request.confirmationCode());

        assertTrue(result.confirmed());
        assertEquals(1, result.facts());
        assertEquals(1, result.candidates());
        assertEquals(1, result.evidence());
        assertEquals(0, count("SELECT COUNT(*) FROM profile_facts WHERE subject_user_id=?", subjectUserId));
        assertEquals(0, count("SELECT COUNT(*) FROM profile_fact_candidates WHERE subject_user_id=?", subjectUserId));
        assertEquals(0, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE message_id=?", messageId));
        assertEquals("REDACTED", scalar("SELECT status FROM media_artifacts WHERE part_id=?", partId));
        assertEquals("CANCELLED", scalar("SELECT status FROM model_analysis_tasks WHERE task_key=?", keyPrefix + "-privacy-task"));
        assertEquals("保留在聊天记录中的原文", scalar("SELECT content FROM messages WHERE id=?", messageId));
        assertTrue(scalar("SELECT old_snapshot FROM profile_fact_changes WHERE reason_code='USER_PRIVACY_ERASURE' " +
                "AND actor_id=? ORDER BY id DESC LIMIT 1", subjectUserId).contains("redacted"));
    }

    private long candidate(String kind, String claimKey, String claim, String epistemicType, String polarity,
                           double confidence, List<Long> evidenceIds) throws Exception {
        return candidate(kind, claimKey, claim, epistemicType, polarity, confidence, evidenceIds,
                LocalDateTime.now(), null);
    }

    private long candidate(String kind, String claimKey, String claim, String epistemicType, String polarity,
                           double confidence, List<Long> evidenceIds, LocalDateTime observedAt,
                           LocalDateTime validUntil) throws Exception {
        return factRepository.upsertCandidate(new ProfileFactCandidateInput(subjectUserId, groupId, kind, epistemicType,
                claimKey, claim, polarity, "ONE_OFF", confidence, "PERSONAL", observedAt, validUntil,
                "integration-test", null, "test", evidenceIds, "text", "{\"test\":true}", "AVAILABLE"));
    }

    private void addEvidence(long candidateId, long messageId) throws SQLException {
        factRepository.addEvidence(candidateId, messageId, "text", "{\"test\":true}", "AVAILABLE", 1.0);
    }

    private long insertMessage(String suffix, LocalDateTime createdAt) throws SQLException {
        return insertMessage(suffix, createdAt, "integration test " + suffix);
    }

    private long insertMessage(String suffix, LocalDateTime createdAt, String content) throws SQLException {
        String sql = "INSERT INTO messages (session_id, user_id, content, raw_content, source_event_key, " +
                "is_robot_reply, is_private, group_id, provider_message_id, sender_type, availability_status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, FALSE, FALSE, ?, ?, 'USER', 'AVAILABLE', ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, keyPrefix + "-session");
            ps.setString(2, subjectUserId);
            ps.setString(3, content);
            ps.setString(4, content);
            ps.setString(5, keyPrefix + "-message-" + suffix);
            ps.setString(6, groupId);
            ps.setString(7, keyPrefix + "-provider-" + suffix);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long insertMessagePart(long messageId, String partType, String availability) throws SQLException {
        String sql = "INSERT INTO message_parts (message_id, part_index, part_type, raw_part_json, availability_status) " +
                "VALUES (?, 0, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, messageId);
            ps.setString(2, partType);
            ps.setString(3, "{\"type\":\"" + partType + "\"}");
            ps.setString(4, availability);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long insertMediaArtifact(long partId, String status) throws SQLException {
        String sql = "INSERT INTO media_artifacts (part_id, artifact_type, parser_name, parser_version, input_sha256, " +
                "status, content_json, completed_at) VALUES (?, 'TEST_ARTIFACT', 'integration-test', 'v1', ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, partId);
            ps.setString(2, keyPrefix + "-artifact-" + partId);
            ps.setString(3, status);
            ps.setString(4, "SUCCEEDED".equals(status) ? "{\"text\":\"parsed\"}" : null);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long insertTask(String key, String type, int priority, int maxAttempts, String status,
                            LocalDateTime notBefore, LocalDateTime leaseUntil, int attempts) throws SQLException {
        String sql = "INSERT INTO model_analysis_tasks (task_key, task_type, status, priority, scope_group_id, " +
                "scope_user_id, input_refs, attempt_count, max_attempts, not_before, lease_until) " +
                "VALUES (?, ?, ?, ?, ?, ?, '{}', ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, key); ps.setString(2, type); ps.setString(3, status); ps.setInt(4, priority);
            ps.setString(5, groupId); ps.setString(6, subjectUserId); ps.setInt(7, attempts);
            ps.setInt(8, maxAttempts); ps.setTimestamp(9, Timestamp.valueOf(notBefore));
            if (leaseUntil == null) ps.setNull(10, java.sql.Types.TIMESTAMP); else ps.setTimestamp(10, Timestamp.valueOf(leaseUntil));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void forceReady(String key) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE model_analysis_tasks SET not_before=CURRENT_TIMESTAMP(3) WHERE task_key=?")) {
            ps.setString(1, key);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void execute(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            ps.executeUpdate();
        }
    }

    private static long count(String sql, Object... values) throws SQLException {
        Number value = (Number) scalarValue(sql, values);
        return value == null ? 0 : value.longValue();
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        Object value = scalarValue(sql, values);
        return value == null ? null : String.valueOf(value);
    }

    private static Object scalarValue(String sql, Object... values) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1);
            }
        }
    }
}
