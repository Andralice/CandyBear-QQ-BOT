package com.start.service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** SQL persistence for group conversation snapshots. */
public final class GroupConversationSessionRepository {
    private static final String TABLE = "group_conversation_sessions";
    private final DataSource dataSource;

    public GroupConversationSessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "session_id VARCHAR(191) PRIMARY KEY,"
                + "group_id VARCHAR(64) NOT NULL,"
                + "thread_id VARCHAR(128) NOT NULL,"
                + "lifecycle_state VARCHAR(32) NOT NULL,"
                + "bot_position VARCHAR(40) NOT NULL,"
                + "topic VARCHAR(255) NULL,"
                + "last_message_id VARCHAR(128) NULL,"
                + "last_bot_message_id VARCHAR(128) NULL,"
                + "close_reason VARCHAR(128) NULL,"
                + "last_activity_at BIGINT NOT NULL DEFAULT 0,"
                + "last_bot_message_at BIGINT NOT NULL DEFAULT 0,"
                + "participant_ids TEXT NULL,"
                + "evidence_message_ids TEXT NULL,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "INDEX idx_group_session (group_id, lifecycle_state),"
                + "INDEX idx_session_activity (last_activity_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create " + TABLE, e);
        }
    }

    public void upsert(GroupConversationSession session) {
        String sql = "INSERT INTO " + TABLE + " (session_id, group_id, thread_id, lifecycle_state, bot_position, topic, "
                + "last_message_id, last_bot_message_id, close_reason, last_activity_at, last_bot_message_at, "
                + "participant_ids, evidence_message_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), thread_id=VALUES(thread_id), "
                + "lifecycle_state=VALUES(lifecycle_state), bot_position=VALUES(bot_position), topic=VALUES(topic), "
                + "last_message_id=VALUES(last_message_id), last_bot_message_id=VALUES(last_bot_message_id), "
                + "close_reason=VALUES(close_reason), last_activity_at=VALUES(last_activity_at), "
                + "last_bot_message_at=VALUES(last_bot_message_at), participant_ids=VALUES(participant_ids), "
                + "evidence_message_ids=VALUES(evidence_message_ids)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, session.sessionId());
            ps.setString(2, session.groupId());
            ps.setString(3, session.threadId());
            ps.setString(4, session.lifecycleState().name());
            ps.setString(5, session.botPosition().name());
            ps.setString(6, session.topic());
            ps.setString(7, session.lastMessageId());
            ps.setString(8, session.lastBotMessageId());
            ps.setString(9, session.closeReason());
            ps.setLong(10, session.lastActivityAt());
            ps.setLong(11, session.lastBotMessageAt());
            ps.setString(12, join(session.participantIds()));
            ps.setString(13, join(session.evidenceMessageIds()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist group conversation session", e);
        }
    }

    public Optional<GroupConversationSession> find(String sessionId) {
        String sql = "SELECT * FROM " + TABLE + " WHERE session_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load group conversation session", e);
        }
    }

    private GroupConversationSession map(ResultSet rs) throws SQLException {
        return new GroupConversationSession(
                rs.getString("session_id"),
                rs.getString("group_id"),
                rs.getString("thread_id"),
                parseState(rs.getString("lifecycle_state")),
                parsePosition(rs.getString("bot_position")),
                rs.getString("topic"),
                rs.getString("last_message_id"),
                rs.getString("last_bot_message_id"),
                rs.getString("close_reason"),
                rs.getLong("last_activity_at"),
                rs.getLong("last_bot_message_at"),
                split(rs.getString("participant_ids")),
                split(rs.getString("evidence_message_ids")));
    }

    private static String join(Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join("\u001e", values);
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(value.split("\\u001e")));
    }

    private static GroupConversationSession.LifecycleState parseState(String value) {
        try { return GroupConversationSession.LifecycleState.valueOf(value); }
        catch (Exception ignored) { return GroupConversationSession.LifecycleState.OBSERVING; }
    }

    private static GroupConversationSession.BotPosition parsePosition(String value) {
        try { return GroupConversationSession.BotPosition.valueOf(value); }
        catch (Exception ignored) { return GroupConversationSession.BotPosition.UNKNOWN; }
    }
}
