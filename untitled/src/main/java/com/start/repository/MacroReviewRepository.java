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
}
