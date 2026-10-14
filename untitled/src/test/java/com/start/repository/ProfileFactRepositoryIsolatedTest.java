package com.start.repository;

import com.start.model.ProfileFactCandidateInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileFactRepositoryIsolatedTest {
    private DataSource dataSource;
    private ProfileFactRepository repository;
    private final String userId = "user-1";

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:profile_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        repository = new ProfileFactRepository(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE messages (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(64), " +
                    "group_id VARCHAR(64), is_private BOOLEAN, is_robot_reply BOOLEAN, sender_type VARCHAR(24), " +
                    "availability_status VARCHAR(32), created_at TIMESTAMP(3), retracted_at TIMESTAMP(3), " +
                    "redacted_at TIMESTAMP(3))");
            statement.execute("CREATE TABLE profile_fact_candidates (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "subject_user_id VARCHAR(64) NOT NULL, source_group_id VARCHAR(64), kind VARCHAR(40) NOT NULL, " +
                    "epistemic_type VARCHAR(40) NOT NULL, claim_key VARCHAR(255) NOT NULL, claim TEXT NOT NULL, " +
                    "polarity VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL, stability VARCHAR(24) NOT NULL, " +
                    "confidence DECIMAL(4,3) NOT NULL, scope VARCHAR(24) NOT NULL, first_observed_at TIMESTAMP(3) NOT NULL, " +
                    "last_observed_at TIMESTAMP(3) NOT NULL, valid_from TIMESTAMP(3) NOT NULL, valid_until TIMESTAMP(3), " +
                    "audit_rationale TEXT, analysis_task_id BIGINT, model_version VARCHAR(128), dedupe_key CHAR(64) UNIQUE NOT NULL, " +
                    "updated_at TIMESTAMP(3))");
            statement.execute("CREATE TABLE profile_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "subject_user_id VARCHAR(64) NOT NULL, kind VARCHAR(40) NOT NULL, epistemic_type VARCHAR(40) NOT NULL, " +
                    "claim_key VARCHAR(255) NOT NULL, claim TEXT NOT NULL, polarity VARCHAR(16) NOT NULL, " +
                    "status VARCHAR(24) NOT NULL, stability VARCHAR(24) NOT NULL, confidence DECIMAL(4,3) NOT NULL, " +
                    "scope VARCHAR(24) NOT NULL, first_observed_at TIMESTAMP(3) NOT NULL, last_observed_at TIMESTAMP(3) NOT NULL, " +
                    "valid_from TIMESTAMP(3) NOT NULL, valid_until TIMESTAMP(3), source_candidate_id BIGINT NOT NULL, " +
                    "confirmed_at TIMESTAMP(3), superseded_by_fact_id BIGINT, review_state VARCHAR(24), version INT)");
            statement.execute("CREATE TABLE profile_fact_evidence (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "candidate_id BIGINT, fact_id BIGINT, message_id BIGINT NOT NULL, part_id BIGINT, artifact_id BIGINT, " +
                    "event_id BIGINT, evidence_role VARCHAR(24) NOT NULL, modality VARCHAR(24) NOT NULL, " +
                    "quote_or_span JSON, availability_snapshot VARCHAR(32) NOT NULL, weight DECIMAL(4,3) NOT NULL)");
            statement.execute("CREATE TABLE profile_fact_conflicts (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "existing_fact_id BIGINT NOT NULL, incoming_candidate_id BIGINT NOT NULL, conflict_type VARCHAR(40), " +
                    "status VARCHAR(24), resolution VARCHAR(40), resolved_by VARCHAR(64), resolved_at TIMESTAMP(3))");
            statement.execute("CREATE TABLE profile_fact_changes (id BIGINT AUTO_INCREMENT PRIMARY KEY, entity_type VARCHAR(32), " +
                    "entity_id BIGINT, from_status VARCHAR(24), to_status VARCHAR(24), old_snapshot TEXT, new_snapshot TEXT, " +
                    "reason_code VARCHAR(40), actor_type VARCHAR(24), actor_id VARCHAR(64), task_id BIGINT)");
        }
    }

    @Test
    void sameClaimFromDifferentGroupsKeepsEvidenceInSeparateCandidates() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long firstMessage = insertMessage("group-a", now.minusDays(1));
        long firstMessage2 = insertMessage("group-a", now);
        long secondMessage = insertMessage("group-b", now.minusDays(1));
        long secondMessage2 = insertMessage("group-b", now);
        long firstCandidate = candidate("group-a", "interest", "game", "喜欢策略游戏", "OBSERVED_BEHAVIOR", now);
        long secondCandidate = candidate("group-b", "interest", "game", "喜欢策略游戏", "OBSERVED_BEHAVIOR", now);

        repository.addEvidence(firstCandidate, firstMessage, "text", "{}", "AVAILABLE", 0.6);
        repository.addEvidence(firstCandidate, firstMessage2, "text", "{}", "AVAILABLE", 0.6);
        repository.addEvidence(secondCandidate, secondMessage, "text", "{}", "AVAILABLE", 0.6);
        repository.addEvidence(secondCandidate, secondMessage2, "text", "{}", "AVAILABLE", 0.6);

        assertNotEquals(firstCandidate, secondCandidate);
        assertEquals(2, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE candidate_id=?", firstCandidate));
        assertEquals(2, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE candidate_id=?", secondCandidate));
        long firstFact = repository.promoteCandidate(firstCandidate);
        long secondFact = repository.promoteCandidate(secondCandidate);

        assertTrue(firstFact > 0);
        assertEquals(firstFact, secondFact);
        assertEquals(4, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE fact_id=?", firstFact));

        LocalDateTime retryAt = now.plusSeconds(1);
        long newEvidence = insertMessage("group-b", retryAt);
        repository.upsertCandidate(new ProfileFactCandidateInput(userId, "group-b", "interest",
                "OBSERVED_BEHAVIOR", "game", "喜欢策略游戏", "ASSERT", "ONE_OFF", 0.90,
                "PERSONAL", retryAt, null, "retry", null, "test-model", List.of(), "text", "{}", "AVAILABLE"));
        repository.addEvidence(secondCandidate, newEvidence, "text", "{}", "AVAILABLE", 0.6);

        assertEquals(firstFact, repository.promoteCandidate(secondCandidate));
        assertEquals(5, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE fact_id=?", firstFact));
    }

    @Test
    void oldCandidateDedupeKeyIsReusedWithinItsSourceGroup() throws Exception {
        LocalDateTime observedAt = LocalDateTime.now();
        String legacyKey = sha256(String.join("|", userId, "PERSONAL", "preference", "SELF_REPORTED",
                "quiet-chat", "喜欢安静的聊天", "ASSERT"));
        long oldCandidate;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO profile_fact_candidates (subject_user_id, source_group_id, kind, epistemic_type, " +
                        "claim_key, claim, polarity, status, stability, confidence, scope, first_observed_at, " +
                        "last_observed_at, valid_from, audit_rationale, model_version, dedupe_key) " +
                        "VALUES (?, ?, 'preference', 'SELF_REPORTED', 'quiet-chat', '喜欢安静的聊天', 'ASSERT', " +
                        "'CANDIDATE', 'ONE_OFF', 0.900, 'PERSONAL', ?, ?, ?, 'legacy', 'old-model', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, userId);
            statement.setString(2, "group-a");
            statement.setTimestamp(3, java.sql.Timestamp.valueOf(observedAt));
            statement.setTimestamp(4, java.sql.Timestamp.valueOf(observedAt));
            statement.setTimestamp(5, java.sql.Timestamp.valueOf(observedAt));
            statement.setString(6, legacyKey);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("legacy candidate has no generated id");
                oldCandidate = keys.getLong(1);
            }
        }

        long reused = candidate("group-a", "preference", "quiet-chat", "喜欢安静的聊天", "SELF_REPORTED", observedAt);
        long newGroup = candidate("group-b", "preference", "quiet-chat", "喜欢安静的聊天", "SELF_REPORTED", observedAt);

        assertEquals(oldCandidate, reused);
        assertNotEquals(oldCandidate, newGroup);
        assertEquals(2, countForUser("SELECT COUNT(*) FROM profile_fact_candidates WHERE subject_user_id=?"));
    }

    @Test
    void oneOffPersonalityObservationRemainsCandidate() throws Exception {
        LocalDateTime observedAt = LocalDateTime.now();
        long message = insertMessage("group-a", observedAt);
        long candidate = candidate("group-a", "personality", "appearance-judgment", "好不好看得看对比",
                "SELF_REPORTED", observedAt);
        repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 1.0);

        assertEquals(0, repository.promoteCandidate(candidate));
        assertEquals(0, count("SELECT COUNT(*) FROM profile_facts WHERE source_candidate_id=?", candidate));
    }

    @Test
    void explicitSelfReportedBirthdayCanBecomeStableFromOneMessage() throws Exception {
        LocalDateTime observedAt = LocalDateTime.now();
        long message = insertMessage("group-a", observedAt);
        long candidate = candidate("group-a", "birthday", "birthday", "生日是5月8日", "SELF_REPORTED", observedAt);
        repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 1.0);

        long fact = repository.promoteCandidate(candidate);

        assertTrue(fact > 0);
        assertEquals("STABLE", scalar("SELECT stability FROM profile_facts WHERE id=?", fact));
    }

    @Test
    void reprocessingSameClaimInSameGroupReusesCandidateAndFact() throws Exception {
        LocalDateTime observedAt = LocalDateTime.now();
        long message = insertMessage("group-a", observedAt);
        long firstCandidate = candidate("group-a", "preference", "quiet-chat", "喜欢安静的聊天",
                "SELF_REPORTED", observedAt);
        repository.addEvidence(firstCandidate, message, "text", "{}", "AVAILABLE", 1.0);
        long firstFact = repository.promoteCandidate(firstCandidate);

        long retryCandidate = candidate("group-a", "preference", "quiet-chat", "喜欢安静的聊天",
                "SELF_REPORTED", observedAt);
        repository.addEvidence(retryCandidate, message, "text", "{}", "AVAILABLE", 1.0);
        long retryFact = repository.promoteCandidate(retryCandidate);

        assertEquals(firstCandidate, retryCandidate);
        assertEquals(firstFact, retryFact);
        assertEquals(1, countForUser("SELECT COUNT(*) FROM profile_facts WHERE subject_user_id=?"));
        assertEquals(1, count("SELECT COUNT(*) FROM profile_fact_evidence WHERE candidate_id=?", firstCandidate));
    }

    @Test
    void laterEvidenceUpgradesPromotedTemporaryFactToStable() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long first = insertMessage("group-a", now.minusDays(2));
        long candidateId = candidate("group-a", "interest", "game", "喜欢策略游戏", "SELF_REPORTED",
                now.minusDays(2));
        repository.addEvidence(candidateId, first, "text", "{}", "AVAILABLE", 1.0);
        long fact = repository.promoteCandidate(candidateId);
        assertEquals("TEMPORARY", scalar("SELECT stability FROM profile_facts WHERE id=?", fact));

        long second = insertMessage("group-a", now.minusDays(1));
        long third = insertMessage("group-a", now.minusSeconds(1));
        long reusedCandidate = candidate("group-a", "interest", "game", "喜欢策略游戏", "SELF_REPORTED", now);
        assertEquals(candidateId, reusedCandidate);
        repository.addEvidence(candidateId, second, "text", "{}", "AVAILABLE", 1.0);
        repository.addEvidence(candidateId, third, "text", "{}", "AVAILABLE", 1.0);

        assertEquals(fact, repository.promoteCandidate(candidateId));
        assertEquals("STABLE", scalar("SELECT stability FROM profile_facts WHERE id=?", fact));
        assertEquals("EVIDENCE_THRESHOLD_MET", scalar("SELECT reason_code FROM profile_fact_changes " +
                "WHERE entity_id=? AND reason_code='EVIDENCE_THRESHOLD_MET'", fact));
    }

    @Test
    void newEvidenceReactivatesAnExpiredPromotedFact() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long first = insertMessage("group-a", now.minusDays(1));
        long candidateId = candidate("group-a", "interest", "game", "喜欢策略游戏", "SELF_REPORTED",
                now.minusDays(1));
        repository.addEvidence(candidateId, first, "text", "{}", "AVAILABLE", 1.0);
        long fact = repository.promoteCandidate(candidateId);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile_facts SET status='EXPIRED', valid_until=? WHERE id=?")) {
            statement.setTimestamp(1, java.sql.Timestamp.valueOf(now.minusHours(1)));
            statement.setLong(2, fact);
            statement.executeUpdate();
        }

        long second = insertMessage("group-a", now);
        long reusedCandidate = candidate("group-a", "interest", "game", "喜欢策略游戏", "SELF_REPORTED", now);
        assertEquals(candidateId, reusedCandidate);
        repository.addEvidence(candidateId, second, "text", "{}", "AVAILABLE", 1.0);

        assertEquals(fact, repository.promoteCandidate(candidateId));
        assertEquals("ACTIVE", scalar("SELECT status FROM profile_facts WHERE id=?", fact));
        assertEquals(1, count("SELECT COUNT(*) FROM profile_facts WHERE id=? AND valid_until IS NULL", fact));
        assertEquals("REOBSERVED_AFTER_EXPIRY", scalar("SELECT reason_code FROM profile_fact_changes " +
                "WHERE entity_id=? AND reason_code='REOBSERVED_AFTER_EXPIRY'", fact));
        assertTrue(repository.findActiveByUserId(userId, false, 10).stream().anyMatch(row -> row.id() == fact));
    }

    @Test
    void repeatedPersonalityRequiresThreeMessagesAcrossTwoDaysAndEvents() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long first = insertMessage("group-a", now.minusDays(2));
        long second = insertMessage("group-a", now.minusDays(1));
        long third = insertMessage("group-a", now);
        long candidate = candidate("group-a", "personality", "appearance-judgment", "评价外貌时会进行对比",
                "OBSERVED_BEHAVIOR", now);
        for (long message : List.of(first, second, third)) {
            repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 0.6);
        }

        long fact = repository.promoteCandidate(candidate);

        assertTrue(fact > 0);
        assertEquals("STABLE", scalar("SELECT stability FROM profile_facts WHERE id=?", fact));
    }

    @Test
    void boundedCorrectionDoesNotSupersedeAnUnboundedFact() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long oldFact = insertStableFact("location", "residence", "住在北京", now);
        long message = insertMessage("group-a", now);
        long candidate = candidate("group-a", "location", "residence", "暂住上海", "SELF_REPORTED",
                "ASSERT", now, now.plusDays(7));
        repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 1.0);

        long newFact = repository.promoteCandidate(candidate, true);

        assertTrue(newFact > 0);
        assertEquals("ACTIVE", scalar("SELECT status FROM profile_facts WHERE id=?", oldFact));
        assertEquals("TEMPORARY", scalar("SELECT stability FROM profile_facts WHERE id=?", newFact));
        assertEquals("OPEN", scalar("SELECT status FROM profile_fact_conflicts " +
                "WHERE existing_fact_id=? AND incoming_candidate_id=?", oldFact, candidate));
    }

    @Test
    void inferredDenialDoesNotSupersedeAnUnboundedFact() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long oldFact = insertStableFact("interest", "game", "喜欢策略游戏", now);
        long first = insertMessage("group-a", now.minusDays(2));
        long second = insertMessage("group-a", now.minusDays(1));
        long third = insertMessage("group-a", now);
        long candidate = candidate("group-a", "interest", "game", "不再喜欢策略游戏", "MODEL_INFERENCE",
                "DENY", now, null);
        for (long message : List.of(first, second, third)) {
            repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 0.6);
        }

        long newFact = repository.promoteCandidate(candidate);

        assertTrue(newFact > 0);
        assertEquals("ACTIVE", scalar("SELECT status FROM profile_facts WHERE id=?", oldFact));
        assertEquals("OPEN", scalar("SELECT status FROM profile_fact_conflicts " +
                "WHERE existing_fact_id=? AND incoming_candidate_id=?", oldFact, candidate));
    }

    @Test
    void unboundedSelfReportedCorrectionCanSupersedeAnOldFact() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long oldFact = insertStableFact("location", "residence", "住在北京", now);
        long message = insertMessage("group-a", now);
        long candidate = candidate("group-a", "location", "residence", "住在上海", "SELF_REPORTED",
                "ASSERT", now, null);
        repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 1.0);

        long newFact = repository.promoteCandidate(candidate, true);

        assertTrue(newFact > 0);
        assertEquals("SUPERSEDED", scalar("SELECT status FROM profile_facts WHERE id=?", oldFact));
        assertEquals("EXPLICIT_CORRECTION", scalar("SELECT resolution FROM profile_fact_conflicts " +
                "WHERE existing_fact_id=? AND incoming_candidate_id=?", oldFact, candidate));
    }

    private long insertStableFact(String kind, String claimKey, String claim, LocalDateTime observedAt)
            throws Exception {
        long first = insertMessage("group-a", observedAt.minusDays(2));
        long second = insertMessage("group-a", observedAt.minusDays(1));
        long third = insertMessage("group-a", observedAt.minusSeconds(1));
        long candidate = candidate("group-a", kind, claimKey, claim, "SELF_REPORTED", observedAt);
        for (long message : List.of(first, second, third)) {
            repository.addEvidence(candidate, message, "text", "{}", "AVAILABLE", 1.0);
        }
        return repository.promoteCandidate(candidate);
    }

    private long candidate(String groupId, String kind, String claimKey, String claim, String epistemicType,
                           LocalDateTime observedAt) throws Exception {
        return candidate(groupId, kind, claimKey, claim, epistemicType, "ASSERT", observedAt, null);
    }

    private long candidate(String groupId, String kind, String claimKey, String claim, String epistemicType,
                           String polarity, LocalDateTime observedAt, LocalDateTime validUntil) throws Exception {
        return repository.upsertCandidate(new ProfileFactCandidateInput(userId, groupId, kind, epistemicType,
                claimKey, claim, polarity, "ONE_OFF", 0.90, "PERSONAL", observedAt, validUntil,
                "isolated-test", null, "test-model", List.of(), "text", "{}", "AVAILABLE"));
    }

    private long insertMessage(String groupId, LocalDateTime createdAt) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO messages (user_id, group_id, is_private, is_robot_reply, sender_type, " +
                        "availability_status, created_at) VALUES (?, ?, FALSE, FALSE, 'USER', 'AVAILABLE', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, userId);
            statement.setString(2, groupId);
            statement.setTimestamp(3, java.sql.Timestamp.valueOf(createdAt));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("isolated test message has no generated id");
    }

    private int count(String sql, long... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setLong(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int countForUser(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private String scalar(String sql, long... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setLong(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("isolated test value not found");
                return result.getString(1);
            }
        }
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
