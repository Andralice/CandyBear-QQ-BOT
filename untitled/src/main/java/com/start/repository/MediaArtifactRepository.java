package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.MediaArtifactSummary;
import com.start.model.MediaPartInput;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence for derived media results. Raw message parts remain the source of truth. */
public class MediaArtifactRepository extends BaseRepository {

    public void saveForwardEdge(long parentPartId, String providerNodeId, int depth, int ordinal,
                                String senderId, String senderName, LocalDateTime sentAt,
                                String rawNodeJson, String availabilityStatus) throws SQLException {
        String sql = "INSERT INTO forward_message_edges (parent_part_id, provider_node_id, depth, ordinal, " +
                "original_sender_id, original_sender_name, original_sent_at, raw_node_json, availability_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                "provider_node_id=VALUES(provider_node_id), original_sender_id=VALUES(original_sender_id), " +
                "original_sender_name=VALUES(original_sender_name), original_sent_at=VALUES(original_sent_at), " +
                "raw_node_json=VALUES(raw_node_json), availability_status=VALUES(availability_status)";
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parentPartId);
            ps.setString(2, providerNodeId);
            ps.setInt(3, Math.max(0, depth));
            ps.setInt(4, Math.max(0, ordinal));
            ps.setString(5, senderId);
            ps.setString(6, senderName);
            if (sentAt == null) ps.setNull(7, Types.TIMESTAMP); else ps.setTimestamp(7, Timestamp.valueOf(sentAt));
            ps.setString(8, rawNodeJson);
            ps.setString(9, availabilityStatus == null ? "AVAILABLE" : availabilityStatus);
            ps.executeUpdate();
        }
    }

    public MediaPartInput findPart(long partId) throws SQLException {
        String sql = "SELECT id, message_id, part_type, raw_part_json, text_value, source_url, " +
                "provider_file_id, target_id, mime_type, availability_status FROM message_parts WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, partId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new MediaPartInput(rs.getLong("id"), rs.getLong("message_id"),
                        rs.getString("part_type"), rs.getString("raw_part_json"), rs.getString("text_value"),
                        rs.getString("source_url"), rs.getString("provider_file_id"), rs.getString("target_id"),
                        rs.getString("mime_type"), rs.getString("availability_status"));
            }
        }
    }

    public long saveArtifact(long partId, String artifactType, String parserName, String parserVersion,
                             String inputSha256, String status, String contentJson, Double confidence,
                             String language, String errorCode, String errorDetail,
                             LocalDateTime startedAt, LocalDateTime completedAt) throws SQLException {
        String sql = "INSERT INTO media_artifacts (part_id, artifact_type, parser_name, parser_version, input_sha256, " +
                "status, content_json, confidence, language, error_code, error_detail, started_at, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE status=VALUES(status), content_json=VALUES(content_json), " +
                "confidence=VALUES(confidence), language=VALUES(language), error_code=VALUES(error_code), " +
                "error_detail=VALUES(error_detail), started_at=VALUES(started_at), completed_at=VALUES(completed_at)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, partId);
            ps.setString(2, artifactType);
            ps.setString(3, parserName);
            ps.setString(4, parserVersion);
            ps.setString(5, inputSha256 == null ? "" : inputSha256);
            ps.setString(6, status);
            ps.setString(7, contentJson);
            if (confidence == null) ps.setNull(8, Types.DECIMAL); else ps.setDouble(8, confidence);
            ps.setString(9, language);
            ps.setString(10, errorCode);
            ps.setString(11, errorDetail);
            if (startedAt == null) ps.setNull(12, Types.TIMESTAMP); else ps.setTimestamp(12, Timestamp.valueOf(startedAt));
            if (completedAt == null) ps.setNull(13, Types.TIMESTAMP); else ps.setTimestamp(13, Timestamp.valueOf(completedAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM media_artifacts WHERE part_id=? AND artifact_type=? AND parser_version=? AND input_sha256=?")) {
            ps.setLong(1, partId);
            ps.setString(2, artifactType);
            ps.setString(3, parserVersion);
            ps.setString(4, inputSha256 == null ? "" : inputSha256);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("解析产物已写入但未返回主键");
    }

    public List<MediaArtifactSummary> findByPart(long partId, int limit) throws SQLException {
        String sql = "SELECT id, part_id, artifact_type, parser_name, parser_version, status, content_json, " +
                "confidence, language, error_code, error_detail, completed_at FROM media_artifacts " +
                "WHERE part_id=? ORDER BY id DESC LIMIT ?";
        List<MediaArtifactSummary> result = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, partId);
            ps.setInt(2, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp completed = rs.getTimestamp("completed_at");
                    result.add(new MediaArtifactSummary(rs.getLong("id"), rs.getLong("part_id"),
                            rs.getString("artifact_type"), rs.getString("parser_name"),
                            rs.getString("parser_version"), rs.getString("status"),
                            rs.getString("content_json"), (Double) rs.getObject("confidence"),
                            rs.getString("language"), rs.getString("error_code"), rs.getString("error_detail"),
                            completed == null ? null : completed.toLocalDateTime()));
                }
            }
        }
        return List.copyOf(result);
    }

    public List<Map<String, Object>> findForwardNodes(long partId, int limit) throws SQLException {
        String sql = "SELECT id, provider_node_id, depth, ordinal, original_sender_id, original_sender_name, " +
                "original_sent_at, raw_node_json, availability_status FROM forward_message_edges " +
                "WHERE parent_part_id=? ORDER BY depth ASC, ordinal ASC LIMIT ?";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, partId);
            ps.setInt(2, Math.max(1, Math.min(limit, 30)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("provider_node_id", rs.getString("provider_node_id"));
                    row.put("depth", rs.getInt("depth"));
                    row.put("ordinal", rs.getInt("ordinal"));
                    row.put("original_sender_id", rs.getString("original_sender_id"));
                    row.put("original_sender_name", rs.getString("original_sender_name"));
                    row.put("original_sent_at", rs.getTimestamp("original_sent_at"));
                    row.put("raw_node_json", rs.getString("raw_node_json"));
                    row.put("availability_status", rs.getString("availability_status"));
                    result.add(row);
                }
            }
        }
        return List.copyOf(result);
    }

    public int expireArtifactsBefore(String artifactType, java.time.LocalDateTime before) throws SQLException {
        String sql = "UPDATE media_artifacts SET status='EXPIRED', content_json=NULL, error_code='RETENTION_EXPIRED', " +
                "error_detail='派生内容按保留策略过期，原始消息部件状态另行记录' WHERE artifact_type=? " +
                "AND status IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL AND completed_at < ?";
        try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, artifactType);
            ps.setTimestamp(2, Timestamp.valueOf(before));
            return ps.executeUpdate();
        }
    }
}
