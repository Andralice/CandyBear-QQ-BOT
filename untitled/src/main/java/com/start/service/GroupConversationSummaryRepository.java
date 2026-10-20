package com.start.service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores one reviewable summary per closed group conversation session. */
public final class GroupConversationSummaryRepository {
    private static final String TABLE = "group_conversation_summaries";
    private final DataSource dataSource;

    public GroupConversationSummaryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "session_id VARCHAR(191) PRIMARY KEY,"
                + "group_id VARCHAR(64) NOT NULL,"
                + "thread_id VARCHAR(128) NOT NULL,"
                + "status VARCHAR(24) NOT NULL,"
                + "summary_text TEXT NULL,"
                + "confirmed_content TEXT NULL,"
                + "uncertain_content TEXT NULL,"
                + "unresolved_questions TEXT NULL,"
                + "close_reason VARCHAR(128) NULL,"
                + "evidence_message_ids TEXT NULL,"
                + "error_detail TEXT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "INDEX idx_summary_group (group_id, updated_at),"
                + "INDEX idx_summary_status (status, updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create " + TABLE, e);
        }
    }

    public void savePending(GroupConversationSession session, String evidenceText) {
        upsert(session, "PENDING", "", "", "", "", evidenceText, null);
    }

    public void saveCompleted(GroupConversationSession session, String summary,
                              String confirmed, String uncertain, String unresolved,
                              String evidenceText) {
        upsert(session, "COMPLETED", summary, confirmed, uncertain, unresolved, evidenceText, null);
    }

    public void saveFailed(GroupConversationSession session, String fallbackSummary,
                           String evidenceText, String errorDetail) {
        upsert(session, "FAILED", fallbackSummary, "", "", "", evidenceText, errorDetail);
    }

    public String findStatus(String sessionId) {
        String sql = "SELECT status FROM " + TABLE + " WHERE session_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load group conversation summary", e);
        }
    }

    private void upsert(GroupConversationSession session, String status, String summary,
                        String confirmed, String uncertain, String unresolved,
                        String evidenceText, String errorDetail) {
        String sql = "INSERT INTO " + TABLE + " (session_id, group_id, thread_id, status, summary_text, "
                + "confirmed_content, uncertain_content, unresolved_questions, close_reason, evidence_message_ids, error_detail) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE status=VALUES(status), summary_text=VALUES(summary_text), "
                + "confirmed_content=VALUES(confirmed_content), uncertain_content=VALUES(uncertain_content), "
                + "unresolved_questions=VALUES(unresolved_questions), close_reason=VALUES(close_reason), "
                + "evidence_message_ids=VALUES(evidence_message_ids), error_detail=VALUES(error_detail)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, session.sessionId());
            ps.setString(2, session.groupId());
            ps.setString(3, session.threadId());
            ps.setString(4, status);
            ps.setString(5, clean(summary));
            ps.setString(6, clean(confirmed));
            ps.setString(7, clean(uncertain));
            ps.setString(8, clean(unresolved));
            ps.setString(9, session.closeReason());
            ps.setString(10, clean(evidenceText));
            ps.setString(11, clean(errorDetail));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist group conversation summary", e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
