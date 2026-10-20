package com.start.service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQL persistence for evidence bound to a conversation session. */
public final class GroupConversationEvidenceRepository {
    private static final String TABLE = "group_conversation_evidence";
    private final DataSource dataSource;

    public GroupConversationEvidenceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "evidence_id VARCHAR(191) PRIMARY KEY,"
                + "session_id VARCHAR(191) NOT NULL,"
                + "evidence_type VARCHAR(40) NOT NULL,"
                + "source_message_id VARCHAR(128) NULL,"
                + "image_message_id VARCHAR(128) NULL,"
                + "image_index INT NOT NULL DEFAULT 0,"
                + "content TEXT NULL,"
                + "source_url VARCHAR(1000) NULL,"
                + "confidence DECIMAL(5,4) NOT NULL DEFAULT 0,"
                + "verification_status VARCHAR(24) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "allow_summary TINYINT(1) NOT NULL DEFAULT 0,"
                + "INDEX idx_evidence_session (session_id, created_at),"
                + "INDEX idx_evidence_source (source_message_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create " + TABLE, e);
        }
    }

    public void upsert(GroupConversationEvidence evidence) {
        String sql = "INSERT INTO " + TABLE + " (evidence_id, session_id, evidence_type, source_message_id, "
                + "image_message_id, image_index, content, source_url, confidence, verification_status, "
                + "created_at, allow_summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE content=VALUES(content), source_url=VALUES(source_url), "
                + "confidence=VALUES(confidence), verification_status=VALUES(verification_status), "
                + "allow_summary=VALUES(allow_summary)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, evidence.evidenceId());
            ps.setString(2, evidence.sessionId());
            ps.setString(3, evidence.type().name());
            ps.setString(4, evidence.sourceMessageId());
            ps.setString(5, evidence.imageMessageId());
            ps.setInt(6, evidence.imageIndex());
            ps.setString(7, evidence.content());
            ps.setString(8, evidence.sourceUrl());
            ps.setDouble(9, evidence.confidence());
            ps.setString(10, evidence.status().name());
            ps.setLong(11, evidence.createdAt());
            ps.setBoolean(12, evidence.allowSummary());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist group conversation evidence", e);
        }
    }

    public List<GroupConversationEvidence> findBySessionId(String sessionId) {
        String sql = "SELECT * FROM " + TABLE + " WHERE session_id = ? ORDER BY created_at, evidence_id";
        List<GroupConversationEvidence> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load group conversation evidence", e);
        }
        return List.copyOf(result);
    }

    private static GroupConversationEvidence map(ResultSet rs) throws SQLException {
        return new GroupConversationEvidence(
                rs.getString("evidence_id"), rs.getString("session_id"),
                parseType(rs.getString("evidence_type")), rs.getString("source_message_id"),
                rs.getString("image_message_id"), rs.getInt("image_index"), rs.getString("content"),
                rs.getString("source_url"), rs.getDouble("confidence"),
                parseStatus(rs.getString("verification_status")), rs.getLong("created_at"),
                rs.getBoolean("allow_summary"));
    }

    private static GroupConversationEvidence.Type parseType(String value) {
        try { return GroupConversationEvidence.Type.valueOf(value); }
        catch (Exception ignored) { return GroupConversationEvidence.Type.UNVERIFIED_EXTERNAL_CONTENT; }
    }

    private static GroupConversationEvidence.Status parseStatus(String value) {
        try { return GroupConversationEvidence.Status.valueOf(value); }
        catch (Exception ignored) { return GroupConversationEvidence.Status.UNVERIFIED; }
    }
}
