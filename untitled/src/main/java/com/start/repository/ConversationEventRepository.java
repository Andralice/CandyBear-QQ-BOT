package com.start.repository;

import com.start.config.DatabaseConfig;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;

/** Writes bounded, group-scoped event observations separately from personal facts. */
public class ConversationEventRepository extends BaseRepository {
    public record EventMessage(long messageId, int ordinal, String role, double relevance) {}

    public long insertEvent(String groupId, String eventType, String topicKey, String topicText,
                            String participantIdsJson, String summary, LocalDateTime startedAt,
                            LocalDateTime endedAt, long taskId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return insertEvent(conn, groupId, eventType, topicKey, topicText, participantIdsJson,
                    summary, startedAt, endedAt, taskId);
        }
    }

    public void addEventMessage(long eventId, long messageId, int ordinal, String role, double relevance) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            addEventMessage(conn, eventId, messageId, ordinal, role, relevance);
        }
    }

    public void insertAtmosphere(String groupId, long eventId, String kind, String claim,
                                 double confidence, LocalDateTime validFrom, LocalDateTime validUntil,
                                 long taskId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            insertAtmosphere(conn, groupId, eventId, kind, claim, confidence, validFrom, validUntil, taskId);
        }
    }

    /** Writes the event, its message mapping, and its atmosphere observation atomically. */
    public long insertClosedEventWithAtmosphere(String groupId, String eventType, String topicKey, String topicText,
                                                String participantIdsJson, String summary, LocalDateTime startedAt,
                                                LocalDateTime endedAt, List<EventMessage> messages, double confidence,
                                                LocalDateTime validFrom, LocalDateTime validUntil, long taskId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long eventId = insertEvent(conn, groupId, eventType, topicKey, topicText, participantIdsJson,
                        summary, startedAt, endedAt, taskId);
                if (messages != null) {
                    for (EventMessage message : messages) {
                        addEventMessage(conn, eventId, message.messageId(), message.ordinal(), message.role(), message.relevance());
                    }
                }
                insertAtmosphere(conn, groupId, eventId, eventType, summary, confidence, validFrom, validUntil, taskId);
                conn.commit();
                return eventId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private long insertEvent(Connection conn, String groupId, String eventType, String topicKey, String topicText,
                             String participantIdsJson, String summary, LocalDateTime startedAt,
                             LocalDateTime endedAt, long taskId) throws SQLException {
        String sql = "INSERT INTO conversation_events (group_id, event_type, status, topic_key, topic_text, " +
                "participant_ids, summary_text, started_at, ended_at, builder_version, analysis_task_id) " +
                "VALUES (?, ?, 'CLOSED', ?, ?, ?, ?, ?, ?, 'evidence-v1', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, groupId); ps.setString(2, eventType); ps.setString(3, topicKey); ps.setString(4, topicText);
            ps.setString(5, participantIdsJson); ps.setString(6, summary);
            ps.setTimestamp(7, Timestamp.valueOf(startedAt));
            if (endedAt == null) ps.setNull(8, Types.TIMESTAMP); else ps.setTimestamp(8, Timestamp.valueOf(endedAt));
            ps.setLong(9, taskId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("群聊事件未返回主键");
    }

    private void addEventMessage(Connection conn, long eventId, long messageId, int ordinal,
                                 String role, double relevance) throws SQLException {
        String sql = "INSERT INTO conversation_event_messages (event_id, message_id, ordinal, relation_role, relevance) " +
                "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE relation_role=VALUES(relation_role), relevance=VALUES(relevance)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, eventId); ps.setLong(2, messageId); ps.setInt(3, ordinal);
            ps.setString(4, role); ps.setDouble(5, relevance); ps.executeUpdate();
        }
    }

    private void insertAtmosphere(Connection conn, String groupId, long eventId, String kind, String claim,
                                  double confidence, LocalDateTime validFrom, LocalDateTime validUntil,
                                  long taskId) throws SQLException {
        String sql = "INSERT INTO group_atmosphere_events (group_id, event_id, kind, claim, status, confidence, valid_from, valid_until, analysis_task_id) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId); ps.setLong(2, eventId); ps.setString(3, kind); ps.setString(4, claim);
            ps.setDouble(5, Math.max(0.0, Math.min(1.0, confidence)));
            ps.setTimestamp(6, Timestamp.valueOf(validFrom));
            if (validUntil == null) ps.setNull(7, Types.TIMESTAMP); else ps.setTimestamp(7, Timestamp.valueOf(validUntil));
            ps.setLong(8, taskId); ps.executeUpdate();
        }
    }
}
