package com.start.repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Handles the two-step, self-only erasure of structured personal profile data. */
public final class ProfilePrivacyRepository implements Repository {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DataSource dataSource;

    public ProfilePrivacyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    public record ErasureRequest(long id, String confirmationCode, LocalDateTime expiresAt) {}

    public record ErasureResult(boolean confirmed, int facts, int candidates, int evidence,
                                int artifacts, int tasks, int legacyProfiles) {
        public static ErasureResult invalid() {
            return new ErasureResult(false, 0, 0, 0, 0, 0, 0);
        }
    }

    public ErasureRequest createRequest(String userId) throws SQLException {
        requireUser(userId);
        String code = randomCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement cancel = conn.prepareStatement(
                        "UPDATE profile_privacy_requests SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP(3) "
                                + "WHERE requester_user_id=? AND status='PENDING'")) {
                    cancel.setString(1, userId);
                    cancel.executeUpdate();
                }
                long id;
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO profile_privacy_requests (subject_user_id, requester_user_id, scope, status, "
                                + "confirmation_hash, expires_at) VALUES (?, ?, 'PROFILE_AND_DERIVED_EVIDENCE', 'PENDING', ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, userId);
                    insert.setString(2, userId);
                    insert.setString(3, sha256(code));
                    insert.setTimestamp(4, Timestamp.valueOf(expiresAt));
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("隐私清理请求未生成");
                        id = keys.getLong(1);
                    }
                }
                conn.commit();
                return new ErasureRequest(id, code, expiresAt);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Returns invalid() for an unknown, expired, or mismatched code without changing data. */
    public ErasureResult erase(String userId, String confirmationCode) throws SQLException {
        requireUser(userId);
        if (confirmationCode == null || confirmationCode.isBlank()) return ErasureResult.invalid();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long requestId;
                String storedHash;
                try (PreparedStatement find = conn.prepareStatement(
                        "SELECT id, confirmation_hash FROM profile_privacy_requests "
                                + "WHERE subject_user_id=? AND requester_user_id=? AND status='PENDING' "
                                + "AND expires_at>CURRENT_TIMESTAMP(3) ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
                    find.setString(1, userId);
                    find.setString(2, userId);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return ErasureResult.invalid();
                        }
                        requestId = rs.getLong("id");
                        storedHash = rs.getString("confirmation_hash");
                    }
                }
                if (!MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.UTF_8),
                        sha256(confirmationCode.trim()).getBytes(StandardCharsets.UTF_8))) {
                    conn.rollback();
                    return ErasureResult.invalid();
                }

                int facts = count(conn, "SELECT COUNT(*) FROM profile_facts WHERE subject_user_id=?", userId);
                int candidates = count(conn, "SELECT COUNT(*) FROM profile_fact_candidates WHERE subject_user_id=?", userId);
                int evidence = count(conn, "SELECT COUNT(*) FROM profile_fact_evidence WHERE candidate_id IN "
                        + "(SELECT id FROM profile_fact_candidates WHERE subject_user_id=?) OR fact_id IN "
                        + "(SELECT id FROM profile_facts WHERE subject_user_id=?)", userId, userId);
                int artifacts = count(conn, "SELECT COUNT(*) FROM media_artifacts a JOIN message_parts p ON p.id=a.part_id "
                        + "JOIN messages m ON m.id=p.message_id WHERE m.user_id=?", userId);
                int tasks = count(conn, "SELECT COUNT(*) FROM model_analysis_tasks WHERE scope_user_id=?", userId);
                int legacyProfiles = count(conn, "SELECT COUNT(*) FROM user_profiles WHERE user_id=?", userId);

                String redacted = "JSON_OBJECT('redacted', TRUE, 'reason', 'USER_PRIVACY_ERASURE')";
                try (PreparedStatement scrubHistory = conn.prepareStatement(
                        "UPDATE profile_fact_changes c JOIN profile_facts f ON c.entity_type='PROFILE_FACT' "
                                + "AND c.entity_id=f.id SET c.old_snapshot=" + redacted + ", c.new_snapshot=" + redacted
                                + " WHERE f.subject_user_id=?")) {
                    scrubHistory.setString(1, userId);
                    scrubHistory.executeUpdate();
                }
                insertErasureAudit(conn, "PROFILE_FACT", "profile_facts", userId, redacted);
                insertErasureAudit(conn, "PROFILE_FACT_CANDIDATE", "profile_fact_candidates", userId, redacted);

                execute(conn, "DELETE FROM profile_fact_evidence WHERE candidate_id IN "
                        + "(SELECT id FROM profile_fact_candidates WHERE subject_user_id=?) OR fact_id IN "
                        + "(SELECT id FROM profile_facts WHERE subject_user_id=?)", userId, userId);
                execute(conn, "DELETE FROM profile_fact_conflicts WHERE incoming_candidate_id IN "
                        + "(SELECT id FROM profile_fact_candidates WHERE subject_user_id=?) OR existing_fact_id IN "
                        + "(SELECT id FROM profile_facts WHERE subject_user_id=?)", userId, userId);
                execute(conn, "DELETE FROM profile_facts WHERE subject_user_id=?", userId);
                execute(conn, "DELETE FROM profile_fact_candidates WHERE subject_user_id=?", userId);
                execute(conn, "DELETE FROM user_profiles WHERE user_id=?", userId);

                execute(conn, "UPDATE media_artifacts a JOIN message_parts p ON p.id=a.part_id JOIN messages m ON m.id=p.message_id "
                        + "SET a.status='REDACTED', a.content_json=JSON_OBJECT('redacted', TRUE, 'reason', 'USER_PRIVACY_ERASURE'), "
                        + "a.confidence=NULL, a.error_code='USER_PRIVACY_ERASURE', a.error_detail='派生产物已按用户请求擦除' "
                        + "WHERE m.user_id=?", userId);
                execute(conn, "UPDATE model_analysis_tasks SET "
                        + "status=CASE WHEN status IN ('PENDING','PROCESSING') THEN 'CANCELLED' ELSE status END, "
                        + "input_refs=JSON_OBJECT('redacted', TRUE, 'reason', 'USER_PRIVACY_ERASURE'), result_json=NULL, "
                        + "error_code='USER_PRIVACY_ERASURE', error_detail='分析输入和结果已按用户请求擦除' WHERE scope_user_id=?", userId);

                try (PreparedStatement complete = conn.prepareStatement(
                        "UPDATE profile_privacy_requests SET status='COMPLETED', confirmed_at=CURRENT_TIMESTAMP(3), "
                                + "completed_at=CURRENT_TIMESTAMP(3), deleted_counts=JSON_OBJECT('facts', ?, 'candidates', ?, "
                                + "'evidence', ?, 'artifacts', ?, 'tasks', ?, 'legacy_profiles', ?) WHERE id=?")) {
                    complete.setInt(1, facts);
                    complete.setInt(2, candidates);
                    complete.setInt(3, evidence);
                    complete.setInt(4, artifacts);
                    complete.setInt(5, tasks);
                    complete.setInt(6, legacyProfiles);
                    complete.setLong(7, requestId);
                    complete.executeUpdate();
                }
                conn.commit();
                return new ErasureResult(true, facts, candidates, evidence, artifacts, tasks, legacyProfiles);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void insertErasureAudit(Connection conn, String entityType, String table,
                                    String userId, String redacted) throws SQLException {
        String sql = "INSERT INTO profile_fact_changes (entity_type, entity_id, from_status, to_status, "
                + "old_snapshot, new_snapshot, reason_code, actor_type, actor_id, task_id) "
                + "SELECT ?, id, status, 'ERASED', " + redacted + ", " + redacted
                + ", 'USER_PRIVACY_ERASURE', 'USER', ?, NULL FROM " + table + " WHERE subject_user_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, userId);
            ps.setString(3, userId);
            ps.executeUpdate();
        }
    }

    private static int count(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void execute(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            ps.executeUpdate();
        }
    }

    private static String randomCode() {
        StringBuilder result = new StringBuilder(8);
        for (int i = 0; i < 8; i++) result.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        return result.toString();
    }

    private static String sha256(String value) throws SQLException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new SQLException("无法生成隐私确认摘要", e);
        }
    }

    private static void requireUser(String userId) throws SQLException {
        if (userId == null || userId.isBlank()) throw new SQLException("缺少当前用户标识");
    }
}
