package com.start.repository;

import com.start.model.ProfileFactSummary;
import com.start.model.ProfileFactCandidateInput;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only access to confirmed, globally keyed personal profile facts. */
public class ProfileFactRepository implements Repository {
    private final DataSource dataSource;

    public ProfileFactRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    public List<ProfileFactSummary> findActiveByUserId(String userId, boolean privateContext, int limit)
            throws Exception {
        String scopeClause = privateContext
                ? "scope IN ('PERSONAL', 'PRIVATE_ONLY')"
                : "scope = 'PERSONAL'";
        String sql = "SELECT id, kind, epistemic_type, claim, stability, confidence, scope, " +
                "last_observed_at, valid_until FROM profile_facts " +
                "WHERE subject_user_id = ? AND status = 'ACTIVE' " +
                "AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP(3)) AND " + scopeClause + " " +
                "ORDER BY confidence DESC, last_observed_at DESC LIMIT ?";
        List<ProfileFactSummary> facts = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setInt(2, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp observed = rs.getTimestamp("last_observed_at");
                    Timestamp validUntil = rs.getTimestamp("valid_until");
                    facts.add(new ProfileFactSummary(
                            rs.getLong("id"), rs.getString("kind"), rs.getString("epistemic_type"),
                            rs.getString("claim"), rs.getString("stability"), rs.getDouble("confidence"),
                            rs.getString("scope"),
                            observed != null ? observed.toLocalDateTime() : null,
                            validUntil != null ? validUntil.toLocalDateTime() : null));
                }
            }
        }
        return List.copyOf(facts);
    }

    /** Expires facts that reached an explicit validity bound or became stale without new evidence. */
    public int expireInactiveFacts(LocalDateTime now, LocalDateTime temporaryBefore,
                                   LocalDateTime stableBefore) throws SQLException {
        String where = "status='ACTIVE' AND (" +
                "(valid_until IS NOT NULL AND valid_until <= ?) OR " +
                "(valid_until IS NULL AND stability='TEMPORARY' AND last_observed_at < ?) OR " +
                "(valid_until IS NULL AND stability='STABLE' AND last_observed_at < ?))";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String audit = "INSERT INTO profile_fact_changes (entity_type, entity_id, from_status, to_status, " +
                        "old_snapshot, new_snapshot, reason_code, actor_type, actor_id, task_id) " +
                        "SELECT 'PROFILE_FACT', id, 'ACTIVE', 'EXPIRED', " +
                        "JSON_OBJECT('claim', claim, 'stability', stability, 'confidence', confidence), " +
                        "JSON_OBJECT('status', 'EXPIRED', 'expired_at', ?), " +
                        "CASE WHEN valid_until IS NOT NULL AND valid_until <= ? " +
                        "THEN 'VALID_UNTIL_REACHED' ELSE 'STALE_EVIDENCE' END, " +
                        "'SYSTEM', 'retention-service', NULL FROM profile_facts WHERE " + where;
                try (PreparedStatement ps = conn.prepareStatement(audit)) {
                    ps.setTimestamp(1, Timestamp.valueOf(now));
                    ps.setTimestamp(2, Timestamp.valueOf(now));
                    ps.setTimestamp(3, Timestamp.valueOf(now));
                    ps.setTimestamp(4, Timestamp.valueOf(temporaryBefore));
                    ps.setTimestamp(5, Timestamp.valueOf(stableBefore));
                    ps.executeUpdate();
                }
                String update = "UPDATE profile_facts SET status='EXPIRED', valid_until=COALESCE(valid_until, ?), " +
                        "version=version+1 WHERE " + where;
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setTimestamp(1, Timestamp.valueOf(now));
                    ps.setTimestamp(2, Timestamp.valueOf(now));
                    ps.setTimestamp(3, Timestamp.valueOf(temporaryBefore));
                    ps.setTimestamp(4, Timestamp.valueOf(stableBefore));
                    int affected = ps.executeUpdate();
                    conn.commit();
                    return affected;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public long upsertCandidate(ProfileFactCandidateInput input) throws Exception {
        LocalDateTime now = input.observedAt() == null ? LocalDateTime.now() : input.observedAt();
        String dedupeKey = sha256(String.join("|", safe(input.subjectUserId()), safe(input.scope()),
                safe(input.kind()), safe(input.epistemicType()), safe(input.claimKey()),
                safe(input.claim()), safe(input.polarity())));
        String sql = "INSERT INTO profile_fact_candidates (subject_user_id, source_group_id, kind, epistemic_type, " +
                "claim_key, claim, polarity, status, stability, confidence, scope, first_observed_at, last_observed_at, " +
                "valid_from, valid_until, audit_rationale, analysis_task_id, model_version, dedupe_key) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE last_observed_at=GREATEST(last_observed_at, VALUES(last_observed_at)), " +
                "confidence=GREATEST(confidence, VALUES(confidence)), audit_rationale=VALUES(audit_rationale), " +
                "analysis_task_id=VALUES(analysis_task_id), model_version=VALUES(model_version)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, input.subjectUserId());
            ps.setString(2, input.sourceGroupId());
            ps.setString(3, input.kind());
            ps.setString(4, input.epistemicType());
            ps.setString(5, input.claimKey());
            ps.setString(6, input.claim());
            ps.setString(7, input.polarity());
            ps.setString(8, input.stability());
            ps.setDouble(9, input.confidence());
            ps.setString(10, input.scope());
            ps.setTimestamp(11, Timestamp.valueOf(now));
            ps.setTimestamp(12, Timestamp.valueOf(now));
            ps.setTimestamp(13, Timestamp.valueOf(now));
            if (input.validUntil() == null) ps.setNull(14, Types.TIMESTAMP);
            else ps.setTimestamp(14, Timestamp.valueOf(input.validUntil()));
            ps.setString(15, input.auditRationale());
            if (input.analysisTaskId() == null) ps.setNull(16, Types.BIGINT);
            else ps.setLong(16, input.analysisTaskId());
            ps.setString(17, input.modelVersion());
            ps.setString(18, dedupeKey);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next() && keys.getLong(1) > 0) return keys.getLong(1);
            }
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM profile_fact_candidates WHERE dedupe_key=?")) {
            ps.setString(1, dedupeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("候选画像事实已写入但未返回主键");
    }

    public List<Map<String, Object>> findEvidenceForFact(long factId, int limit) throws SQLException {
        String sql = "SELECT e.message_id, m.provider_message_id, m.user_id AS sender_id, m.group_id, " +
                "m.created_at, m.availability_status, e.part_id, e.artifact_id, e.modality, e.evidence_role, e.availability_snapshot FROM profile_fact_evidence e " +
                "JOIN messages m ON m.id=e.message_id WHERE e.fact_id=? ORDER BY m.created_at DESC LIMIT ?";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, factId);
            ps.setInt(2, Math.max(1, Math.min(limit, 10)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("message_id", rs.getLong("message_id"));
                    row.put("provider_message_id", rs.getString("provider_message_id"));
                    row.put("sender_id", rs.getString("sender_id"));
                    row.put("group_id", rs.getString("group_id"));
                    row.put("sent_at", rs.getTimestamp("created_at"));
                    row.put("availability_status", rs.getString("availability_status"));
                    row.put("part_id", rs.getObject("part_id"));
                    row.put("artifact_id", rs.getObject("artifact_id"));
                    row.put("modality", rs.getString("modality"));
                    row.put("evidence_role", rs.getString("evidence_role"));
                    row.put("availability_snapshot", rs.getString("availability_snapshot"));
                    result.add(row);
                }
            }
        }
        return List.copyOf(result);
    }

    public void addEvidence(long candidateId, long messageId, String modality, String quoteOrSpanJson,
                             String availabilitySnapshot, double weight) throws SQLException {
        addEvidence(candidateId, messageId, null, null, modality, quoteOrSpanJson, availabilitySnapshot, weight);
    }

    public void addEvidence(long candidateId, long messageId, Long partId, Long artifactId,
                            String modality, String quoteOrSpanJson, String availabilitySnapshot,
                            double weight) throws SQLException {
        String sql = "INSERT INTO profile_fact_evidence (candidate_id, message_id, part_id, artifact_id, " +
                "evidence_role, modality, quote_or_span, availability_snapshot, weight) " +
                "SELECT ?, ?, ?, ?, 'SUPPORT', ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS (" +
                "SELECT 1 FROM profile_fact_evidence WHERE candidate_id=? AND message_id=? " +
                "AND evidence_role='SUPPORT' AND COALESCE(part_id,0)=COALESCE(?,0) " +
                "AND COALESCE(artifact_id,0)=COALESCE(?,0))";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, candidateId);
            ps.setLong(2, messageId);
            ps.setObject(3, partId);
            ps.setObject(4, artifactId);
            ps.setString(5, modality == null ? "text" : modality);
            ps.setString(6, quoteOrSpanJson);
            ps.setString(7, availabilitySnapshot == null ? "AVAILABLE" : availabilitySnapshot);
            ps.setDouble(8, Math.max(0.0, Math.min(1.0, weight)));
            ps.setLong(9, candidateId);
            ps.setLong(10, messageId);
            ps.setObject(11, partId);
            ps.setObject(12, artifactId);
            ps.executeUpdate();
        }
    }

    /**
     * Promotes only when deterministic evidence thresholds are met. Returns the formal fact id, or 0 when
     * the candidate remains a candidate. Existing facts are superseded, never deleted.
     */
    public long promoteCandidate(long candidateId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                CandidateRow candidate = lockCandidate(conn, candidateId);
                if (candidate == null) {
                    conn.commit();
                    return 0;
                }
                EvidenceStats stats = evidenceStats(conn, candidateId);
                if (stats.messageCount == 0) { conn.commit(); return 0; }
                if ("PROMOTED".equals(candidate.status)) {
                    long existingFactId = findFactByCandidate(conn, candidateId);
                    if (existingFactId == 0) {
                        conn.commit();
                        return 0;
                    }
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE profile_facts SET last_observed_at=GREATEST(last_observed_at, ?), confidence=GREATEST(confidence, ?) WHERE id=?")) {
                        update.setTimestamp(1, Timestamp.valueOf(candidate.lastObservedAt));
                        update.setDouble(2, candidate.confidence);
                        update.setLong(3, existingFactId);
                        update.executeUpdate();
                    }
                    try (PreparedStatement updateEvidence = conn.prepareStatement(
                            "UPDATE profile_fact_evidence SET fact_id=? WHERE candidate_id=?")) {
                        updateEvidence.setLong(1, existingFactId);
                        updateEvidence.setLong(2, candidateId);
                        updateEvidence.executeUpdate();
                    }
                    conn.commit();
                    return existingFactId;
                }
                if (!"CANDIDATE".equals(candidate.status)) {
                    conn.commit();
                    return 0;
                }
                boolean stableKind = List.of("interest", "preference", "habit").contains(candidate.kind);
                boolean stable = candidate.validUntil == null && stableKind && !"MODEL_INFERENCE".equals(candidate.epistemicType)
                        && "ASSERT".equals(candidate.polarity)
                        && stats.messageCount >= 3 && stats.dayCount >= 2 && stats.eventCount >= 2;
                boolean temporary = "SELF_REPORTED".equals(candidate.epistemicType)
                        || stats.messageCount >= 2;
                if (!stable && !temporary) {
                    conn.commit();
                    return 0;
                }
                String stability = stable ? "STABLE" : "TEMPORARY";
                double confidence = Math.max(0.0, Math.min(1.0, candidate.confidence));
                if ("MODEL_INFERENCE".equals(candidate.epistemicType)) confidence = Math.min(confidence, 0.55);
                long factId = findOrInsertFact(conn, candidate, stability, confidence);
                resolveConflicts(conn, candidate, factId);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE profile_fact_evidence SET fact_id=? WHERE candidate_id=?")) {
                    ps.setLong(1, factId);
                    ps.setLong(2, candidateId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE profile_fact_candidates SET status='PROMOTED', stability=?, updated_at=CURRENT_TIMESTAMP(3) WHERE id=?")) {
                    ps.setString(1, stability);
                    ps.setLong(2, candidateId);
                    ps.executeUpdate();
                }
                insertChange(conn, factId, "ACTIVE", "ACTIVE", "PROMOTED_FROM_CANDIDATE",
                        candidate.claim, stability);
                conn.commit();
                return factId;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlException) throw sqlException;
                throw new SQLException("候选事实晋级失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private long findOrInsertFact(Connection conn, CandidateRow candidate, String stability,
                                  double confidence) throws SQLException {
        String find = "SELECT id FROM profile_facts WHERE subject_user_id=? AND claim_key=? AND claim=? " +
                "AND polarity=? AND scope=? AND ((valid_until IS NULL AND ? IS NULL) OR valid_until=?) " +
                "AND status='ACTIVE' ORDER BY id DESC LIMIT 1 FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(find)) {
            ps.setString(1, candidate.subjectUserId);
            ps.setString(2, candidate.claimKey);
            ps.setString(3, candidate.claim);
            ps.setString(4, candidate.polarity);
            ps.setString(5, candidate.scope);
            ps.setObject(6, candidate.validUntil);
            ps.setObject(7, candidate.validUntil);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE profile_facts SET last_observed_at=GREATEST(last_observed_at, ?), " +
                                    "confidence=GREATEST(confidence, ?), stability=?, valid_until=? WHERE id=?")) {
                        update.setTimestamp(1, Timestamp.valueOf(candidate.lastObservedAt));
                        update.setDouble(2, confidence);
                        update.setString(3, stability);
                        if (candidate.validUntil == null) update.setNull(4, Types.TIMESTAMP);
                        else update.setTimestamp(4, Timestamp.valueOf(candidate.validUntil));
                        update.setLong(5, id);
                        update.executeUpdate();
                    }
                    return id;
                }
            }
        }
        String insert = "INSERT INTO profile_facts (subject_user_id, kind, epistemic_type, claim_key, claim, polarity, " +
                "status, stability, confidence, scope, first_observed_at, last_observed_at, valid_from, valid_until, " +
                "source_candidate_id, confirmed_at, review_state, version) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), 'AUTO', 1)";
        try (PreparedStatement ps = conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, candidate.subjectUserId());
            ps.setString(2, candidate.kind());
            ps.setString(3, candidate.epistemicType());
            ps.setString(4, candidate.claimKey());
            ps.setString(5, candidate.claim());
            ps.setString(6, candidate.polarity());
            ps.setString(7, stability);
            ps.setDouble(8, confidence);
            ps.setString(9, candidate.scope);
            ps.setTimestamp(10, Timestamp.valueOf(candidate.firstObservedAt));
            ps.setTimestamp(11, Timestamp.valueOf(candidate.lastObservedAt));
            ps.setTimestamp(12, Timestamp.valueOf(candidate.validFrom));
            if (candidate.validUntil == null) ps.setNull(13, Types.TIMESTAMP);
            else ps.setTimestamp(13, Timestamp.valueOf(candidate.validUntil));
            ps.setLong(14, candidate.id);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("正式画像事实未返回主键");
    }

    private long findFactByCandidate(Connection conn, long candidateId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM profile_facts WHERE source_candidate_id=? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void resolveConflicts(Connection conn, CandidateRow candidate, long newFactId) throws SQLException {
        String sql = "SELECT id, claim, polarity FROM profile_facts WHERE subject_user_id=? AND claim_key=? " +
                "AND status='ACTIVE' AND id<>? AND scope=? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, candidate.subjectUserId);
            ps.setString(2, candidate.claimKey);
            ps.setLong(3, newFactId);
            ps.setString(4, candidate.scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long existingId = rs.getLong("id");
                    boolean differs = !candidate.claim.equals(rs.getString("claim"))
                            || !candidate.polarity.equals(rs.getString("polarity"));
                    if (!differs) continue;
                    // A bounded exception must not retire the standing preference it temporarily overrides.
                    String resolution = "DENY".equals(candidate.polarity) && candidate.validUntil == null
                            ? "EXPLICIT_DENIAL" : null;
                    try (PreparedStatement conflict = conn.prepareStatement(
                            "INSERT INTO profile_fact_conflicts (existing_fact_id, incoming_candidate_id, conflict_type, status, resolution, resolved_by, resolved_at) VALUES (?, ?, ?, ?, ?, 'SYSTEM_RULE', ?)")) {
                        conflict.setLong(1, existingId);
                        conflict.setLong(2, candidate.id);
                        conflict.setString(3, "CLAIM_KEY_COLLISION");
                        conflict.setString(4, resolution == null ? "OPEN" : "RESOLVED");
                        conflict.setString(5, resolution);
                        if (resolution == null) conflict.setNull(6, Types.TIMESTAMP);
                        else conflict.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
                        conflict.executeUpdate();
                    }
                    if (resolution != null) {
                        try (PreparedStatement update = conn.prepareStatement(
                                "UPDATE profile_facts SET status='SUPERSEDED', superseded_by_fact_id=?, valid_until=CURRENT_TIMESTAMP(3) WHERE id=?")) {
                            update.setLong(1, newFactId);
                            update.setLong(2, existingId);
                            update.executeUpdate();
                        }
                        insertChange(conn, existingId, "ACTIVE", "SUPERSEDED", "EXPLICIT_DENIAL",
                                candidate.claim, "SUPERSEDED");
                    }
                }
            }
        }
    }

    private void insertChange(Connection conn, long factId, String from, String to, String reason,
                              String claim, String stability) throws SQLException {
        String sql = "INSERT INTO profile_fact_changes (entity_type, entity_id, from_status, to_status, old_snapshot, new_snapshot, reason_code, actor_type, actor_id, task_id) VALUES ('PROFILE_FACT', ?, ?, ?, NULL, ?, ?, 'SYSTEM', 'analysis-worker', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, factId);
            ps.setString(2, from);
            ps.setString(3, to);
            ps.setString(4, "{\"claim\":" + quoteJson(claim) + ",\"stability\":\"" + stability + "\"}");
            ps.setString(5, reason);
            ps.setNull(6, Types.BIGINT);
            ps.executeUpdate();
        }
    }

    private CandidateRow lockCandidate(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, subject_user_id, source_group_id, kind, epistemic_type, claim_key, claim, polarity, status, " +
                "confidence, scope, first_observed_at, last_observed_at, valid_from, valid_until FROM profile_fact_candidates WHERE id=? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new CandidateRow(rs.getLong("id"), rs.getString("subject_user_id"), rs.getString("source_group_id"),
                        rs.getString("kind"), rs.getString("epistemic_type"), rs.getString("claim_key"),
                        rs.getString("claim"), rs.getString("polarity"), rs.getString("status"),
                        rs.getDouble("confidence"), rs.getString("scope"),
                        rs.getTimestamp("first_observed_at").toLocalDateTime(),
                        rs.getTimestamp("last_observed_at").toLocalDateTime(),
                        rs.getTimestamp("valid_from").toLocalDateTime(),
                        rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toLocalDateTime());
            }
        }
    }

    private EvidenceStats evidenceStats(Connection conn, long candidateId) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT e.message_id), COUNT(DISTINCT DATE(m.created_at)), " +
                "COUNT(DISTINCT COALESCE(e.event_id, e.message_id)) FROM profile_fact_evidence e " +
                "JOIN messages m ON m.id=e.message_id WHERE e.candidate_id=? AND e.evidence_role='SUPPORT' " +
                "AND e.availability_snapshot='AVAILABLE' AND m.retracted_at IS NULL " +
                "AND m.redacted_at IS NULL AND m.availability_status='AVAILABLE' " +
                "AND m.is_robot_reply=FALSE AND m.sender_type='USER' " +
                "AND EXISTS (SELECT 1 FROM profile_fact_candidates c WHERE c.id=e.candidate_id " +
                "AND c.subject_user_id=m.user_id AND m.created_at<=c.last_observed_at " +
                "AND ((c.scope='PRIVATE_ONLY' AND m.group_id IS NULL AND m.is_private=TRUE) " +
                "OR (c.scope='PERSONAL' AND m.group_id=c.source_group_id AND m.is_private=FALSE)))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new EvidenceStats(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        }
    }

    private record CandidateRow(long id, String subjectUserId, String sourceGroupId, String kind,
                                String epistemicType, String claimKey, String claim, String polarity,
                                String status, double confidence, String scope,
                                LocalDateTime firstObservedAt, LocalDateTime lastObservedAt,
                                LocalDateTime validFrom, LocalDateTime validUntil) {}

    private record EvidenceStats(int messageCount, int dayCount, int eventCount) {}

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String quoteJson(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }
}
