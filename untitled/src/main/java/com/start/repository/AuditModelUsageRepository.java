package com.start.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

/** Writes one row for every audit-model HTTP attempt, including failed attempts. */
public class AuditModelUsageRepository implements Repository {
    private final DataSource dataSource;

    public AuditModelUsageRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    public void insert(String scene, String model, String endpointHost,
                       String messageId, String groupId, String userId,
                       String sessionId, String functionName, int attempt,
                       Integer httpStatus, boolean success, int inputTokens, int outputTokens,
                       int totalTokens, int cachedInputTokens, Double cacheHitRate,
                       long latencyMs, String errorType, String errorMessage,
                       LocalDateTime createdAt) throws SQLException {
        String sql = "INSERT INTO audit_model_usage " +
                "(scene, model_name, endpoint_host, message_id, group_id, user_id, session_id, function_name, " +
                "attempt_no, http_status, success, " +
                "input_tokens, output_tokens, total_tokens, cached_input_tokens, cache_hit_rate, " +
                "latency_ms, error_type, error_message, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scene);
            setNullable(ps, 2, model);
            setNullable(ps, 3, endpointHost);
            setNullable(ps, 4, messageId);
            setNullable(ps, 5, groupId);
            setNullable(ps, 6, userId);
            setNullable(ps, 7, sessionId);
            setNullable(ps, 8, functionName);
            ps.setInt(9, Math.max(1, attempt));
            if (httpStatus == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, httpStatus);
            ps.setBoolean(11, success);
            ps.setInt(12, Math.max(0, inputTokens));
            ps.setInt(13, Math.max(0, outputTokens));
            ps.setInt(14, Math.max(0, totalTokens));
            ps.setInt(15, Math.max(0, cachedInputTokens));
            if (cacheHitRate == null) ps.setNull(16, Types.DECIMAL); else ps.setDouble(16, cacheHitRate);
            ps.setLong(17, Math.max(0, latencyMs));
            setNullable(ps, 18, errorType);
            setNullable(ps, 19, errorMessage);
            ps.setTimestamp(20, Timestamp.valueOf(createdAt != null ? createdAt : LocalDateTime.now()));
            ps.executeUpdate();
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }
}
