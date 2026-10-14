package com.start.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Persistence for the live shadow cursor and its immutable review runs. */
public final class MacroReviewRepository {
    private final DataSource dataSource;

    public MacroReviewRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public long findCursor(String groupId) throws SQLException {
        if (groupId == null || groupId.isBlank()) return 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_message_id FROM macro_review_cursors WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public void advanceCursor(String groupId, long messageId) throws SQLException {
        if (groupId == null || groupId.isBlank() || messageId <= 0) return;
        String sql = "INSERT INTO macro_review_cursors (group_id, last_message_id) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE last_message_id=GREATEST(last_message_id, VALUES(last_message_id)), " +
                "updated_at=CURRENT_TIMESTAMP(3)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setLong(2, messageId);
            ps.executeUpdate();
        }
    }

    public HistoryCursor findHistoryCursor(String groupId) throws SQLException {
        if (groupId == null || groupId.isBlank()) return HistoryCursor.missing();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_message_id, target_message_id, status " +
                             "FROM macro_review_history_cursors WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new HistoryCursor(true, rs.getLong(1), rs.getLong(2), rs.getString(3))
                        : HistoryCursor.missing();
            }
        }
    }

    /** Captures the historical end point once; later live messages stay on the live cursor. */
    public void initializeHistoryCursor(String groupId, long targetMessageId) throws SQLException {
        initializeHistoryCursor(groupId, targetMessageId, 0);
    }

    /** Captures the end point once and skips everything before the configured history boundary. */
    public void initializeHistoryCursor(String groupId, long targetMessageId, long initialMessageId)
            throws SQLException {
        if (groupId == null || groupId.isBlank() || targetMessageId <= 0) return;
        String sql = "INSERT INTO macro_review_history_cursors " +
                "(group_id, last_message_id, target_message_id, status) VALUES (?, ?, ?, 'PENDING') " +
                "ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), " +
                "last_message_id=GREATEST(last_message_id, VALUES(last_message_id)), " +
                "target_message_id=CASE WHEN target_message_id<=0 THEN VALUES(target_message_id) " +
                "ELSE target_message_id END";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setLong(2, Math.max(0, initialMessageId));
            ps.setLong(3, targetMessageId);
            ps.executeUpdate();
        }
    }

    public void advanceHistoryCursor(String groupId, long messageId, boolean complete) throws SQLException {
        if (groupId == null || groupId.isBlank() || messageId <= 0) return;
        String sql = "UPDATE macro_review_history_cursors SET " +
                "last_message_id=GREATEST(last_message_id, ?), " +
                "status=CASE WHEN ? = 1 THEN 'COMPLETE' ELSE status END, " +
                "updated_at=CURRENT_TIMESTAMP(3) WHERE group_id=?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setBoolean(2, complete);
            ps.setString(3, groupId);
            ps.executeUpdate();
        }
    }

    public String findLatestSuccessfulHistoryResultJson(String groupId) throws SQLException {
        if (groupId == null || groupId.isBlank()) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT r.result_json FROM macro_review_runs r " +
                             "JOIN model_analysis_tasks t ON t.id=r.task_id " +
                             "WHERE r.group_id=? AND r.status='SUCCEEDED' " +
                             "AND r.result_json IS NOT NULL AND t.task_type='MACRO_REVIEW_HISTORY_BATCH' " +
                             "ORDER BY r.id DESC LIMIT 1")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void saveRun(RunInput input) throws SQLException {
        String sql = "INSERT INTO macro_review_runs " +
                "(task_id, group_id, from_message_id, to_message_id, processed_through_message_id, " +
                "overlap_message_count, processed_message_count, window_truncated, status, result_json, " +
                "model_name, prompt_version, input_tokens, output_tokens) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, input.taskId());
            ps.setString(2, input.groupId());
            ps.setLong(3, input.fromMessageId());
            ps.setLong(4, input.toMessageId());
            ps.setLong(5, input.processedThroughMessageId());
            ps.setInt(6, input.overlapMessageCount());
            ps.setInt(7, input.processedMessageCount());
            ps.setBoolean(8, input.windowTruncated());
            ps.setString(9, input.status());
            if (input.resultJson() == null || input.resultJson().isBlank()) ps.setNull(10, java.sql.Types.LONGVARCHAR);
            else ps.setString(10, input.resultJson());
            ps.setString(11, input.modelName());
            ps.setString(12, input.promptVersion());
            if (input.inputTokens() == null) ps.setNull(13, java.sql.Types.INTEGER);
            else ps.setInt(13, input.inputTokens());
            if (input.outputTokens() == null) ps.setNull(14, java.sql.Types.INTEGER);
            else ps.setInt(14, input.outputTokens());
            ps.executeUpdate();
        }
    }

    public record RunInput(long taskId, String groupId, long fromMessageId, long toMessageId,
                           long processedThroughMessageId, int overlapMessageCount,
                           int processedMessageCount, boolean windowTruncated, String status,
                           String resultJson, String modelName, String promptVersion,
                           Integer inputTokens, Integer outputTokens) {}

    public record HistoryCursor(boolean initialized, long lastMessageId,
                                long targetMessageId, String status) {
        static HistoryCursor missing() {
            return new HistoryCursor(false, 0, 0, "MISSING");
        }
    }
}
