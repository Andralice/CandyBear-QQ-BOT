package com.start.repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded read queries used by evidence tools. Every query receives an explicit conversation scope. */
public class EvidenceQueryRepository extends BaseRepository {

    public List<Map<String, Object>> messageContext(String groupId, String sessionId, long centerId,
                                                     long cursor, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 20));
        String scope = groupId != null && !groupId.isBlank() ? "group_id=?" : "group_id IS NULL AND session_id=?";
        String sql = "SELECT id, provider_message_id, user_id, sender_type, group_id, session_id, content, " +
                "raw_content, reply_to_id, reply_provider_message_id, availability_status, created_at " +
                "FROM messages WHERE " + scope + " AND id<=? AND (?=0 OR id<?) AND retracted_at IS NULL " +
                "ORDER BY id DESC LIMIT ?";
        return query(sql, groupId != null && !groupId.isBlank() ? groupId : sessionId, centerId,
                cursor, cursor, bounded);
    }

    /** Reads messages that arrived after a local message cursor in the same scope. */
    public List<Map<String, Object>> messageContextAfter(String groupId, String sessionId,
                                                          long afterMessageId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 20));
        String scope = groupId != null && !groupId.isBlank() ? "group_id=?" : "group_id IS NULL AND session_id=?";
        String sql = "SELECT id, provider_message_id, user_id, sender_type, group_id, session_id, content, " +
                "raw_content, reply_to_id, reply_provider_message_id, availability_status, created_at " +
                "FROM messages WHERE " + scope + " AND id>? AND retracted_at IS NULL " +
                "ORDER BY id ASC LIMIT ?";
        return query(sql, groupId != null && !groupId.isBlank() ? groupId : sessionId,
                Math.max(0L, afterMessageId), bounded);
    }

    public List<Map<String, Object>> thread(String groupId, String sessionId, String providerMessageId,
                                            long cursor, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 30));
        String scope = groupId != null && !groupId.isBlank() ? "group_id=?" : "group_id IS NULL AND session_id=?";
        String sql = "SELECT id, provider_message_id, user_id, sender_type, group_id, session_id, content, " +
                "raw_content, reply_to_id, reply_provider_message_id, availability_status, created_at " +
                "FROM messages WHERE " + scope + " AND (provider_message_id=? OR reply_provider_message_id=? " +
                "OR reply_to_id=?) AND (?=0 OR id<?) AND retracted_at IS NULL ORDER BY id ASC LIMIT ?";
        return query(sql, groupId != null && !groupId.isBlank() ? groupId : sessionId,
                providerMessageId, providerMessageId, parseLong(providerMessageId), cursor, cursor, bounded);
    }

    public List<Map<String, Object>> mediaArtifacts(String groupId, String sessionId, long partId,
                                                     long messageId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 10));
        String scope = groupId != null && !groupId.isBlank() ? "m.group_id=?" : "m.group_id IS NULL AND m.session_id=?";
        String sql = "SELECT a.id AS artifact_id, a.part_id, a.artifact_type, a.parser_name, a.parser_version, " +
                "a.status, a.content_json, a.confidence, a.language, a.error_code, a.error_detail, " +
                "a.completed_at, p.part_type, m.id AS message_id, m.provider_message_id, m.user_id, " +
                "m.group_id, m.created_at, m.availability_status FROM media_artifacts a " +
                "JOIN message_parts p ON p.id=a.part_id JOIN messages m ON m.id=p.message_id WHERE " + scope +
                " AND (?=0 OR a.part_id=?) AND (?=0 OR m.id=?) ORDER BY a.id DESC LIMIT ?";
        return query(sql, groupId != null && !groupId.isBlank() ? groupId : sessionId,
                partId, partId, messageId, messageId, bounded);
    }

    public List<Map<String, Object>> forwardChain(String groupId, String sessionId, long partId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 30));
        String scope = groupId != null && !groupId.isBlank() ? "m.group_id=?" : "m.group_id IS NULL AND m.session_id=?";
        String sql = "SELECT e.id AS edge_id, e.parent_part_id, e.child_message_id, e.provider_node_id, e.depth, " +
                "e.ordinal, e.original_sender_id, e.original_sender_name, e.original_sent_at, e.raw_node_json, " +
                "e.availability_status, m.id AS message_id, m.provider_message_id, m.user_id, m.group_id, " +
                "m.created_at FROM forward_message_edges e JOIN message_parts p ON p.id=e.parent_part_id " +
                "JOIN messages m ON m.id=p.message_id WHERE " + scope + " AND e.parent_part_id=? " +
                "ORDER BY e.depth ASC, e.ordinal ASC LIMIT ?";
        return query(sql, groupId != null && !groupId.isBlank() ? groupId : sessionId, partId, bounded);
    }

    public List<Map<String, Object>> atmosphere(String groupId, long cursor, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 20));
        String sql = "SELECT id, group_id, event_id, kind, claim, status, confidence, valid_from, valid_until, " +
                "created_at, (SELECT GROUP_CONCAT(DISTINCT message_id) FROM conversation_event_messages em WHERE em.event_id=g.event_id) AS source_message_ids, " +
                "(SELECT GROUP_CONCAT(DISTINCT m.user_id) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=g.event_id) AS source_sender_ids, " +
                "(SELECT MAX(m.created_at) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=g.event_id) AS source_sent_at, " +
                "COALESCE((SELECT MIN(m.availability_status) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=g.event_id), 'DERIVED') AS availability_status " +
                "FROM group_atmosphere_events g WHERE group_id=? AND status='ACTIVE' " +
                "AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP(3)) AND (?=0 OR id<?) " +
                "ORDER BY valid_from DESC, id DESC LIMIT ?";
        return query(sql, groupId, cursor, cursor, bounded);
    }

    public List<Map<String, Object>> similarEvents(String groupId, String keyword, long cursor, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 20));
        String sql = "SELECT id, group_id, event_type, status, topic_key, topic_text, participant_ids, summary_text, " +
                "started_at, ended_at, (SELECT GROUP_CONCAT(DISTINCT message_id) FROM conversation_event_messages em WHERE em.event_id=e.id) AS source_message_ids, " +
                "(SELECT GROUP_CONCAT(DISTINCT m.user_id) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=e.id) AS source_sender_ids, " +
                "(SELECT MAX(m.created_at) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=e.id) AS source_sent_at, " +
                "COALESCE((SELECT MIN(m.availability_status) FROM conversation_event_messages em JOIN messages m ON m.id=em.message_id WHERE em.event_id=e.id), 'DERIVED') AS availability_status " +
                "FROM conversation_events e WHERE group_id=? AND (? IS NULL OR topic_key LIKE CONCAT('%', ?, '%') " +
                "OR topic_text LIKE CONCAT('%', ?, '%') OR summary_text LIKE CONCAT('%', ?, '%')) " +
                "AND (?=0 OR id<?) ORDER BY started_at DESC, id DESC LIMIT ?";
        String term = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return query(sql, groupId, term, term, term, term, cursor, cursor, bounded);
    }

    private List<Map<String, Object>> query(String sql, Object... values) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    result.add(row);
                }
            }
        }
        return List.copyOf(result);
    }

    private static Long parseLong(String value) { try { return value == null ? null : Long.parseLong(value); } catch (Exception e) { return null; } }
}
