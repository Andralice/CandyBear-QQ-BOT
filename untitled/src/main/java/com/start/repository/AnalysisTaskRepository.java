package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.AnalysisTask;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Database queue with a lease so a restart can recover unfinished work. */
public class AnalysisTaskRepository extends BaseRepository {

    public Optional<AnalysisTask> claimNext(String owner, int leaseSeconds) throws SQLException {
        return claimNext(owner, leaseSeconds, null, true);
    }

    /** Claims only the requested task type for an independent low-priority worker. */
    public Optional<AnalysisTask> claimNextForType(String owner, int leaseSeconds, String taskType)
            throws SQLException {
        if (taskType == null || taskType.isBlank()) return Optional.empty();
        return claimNext(owner, leaseSeconds, taskType, true);
    }

    /** Keeps the live worker from consuming work owned by the historical line. */
    public Optional<AnalysisTask> claimNextExcludingType(String owner, int leaseSeconds, String taskType)
            throws SQLException {
        if (taskType == null || taskType.isBlank()) return claimNext(owner, leaseSeconds);
        return claimNext(owner, leaseSeconds, taskType, false);
    }

    private Optional<AnalysisTask> claimNext(String owner, int leaseSeconds,
                                             String taskType, boolean includeTaskType) throws SQLException {
        String worker = owner == null || owner.isBlank() ? "analysis-worker" : owner;
        int lease = Math.max(10, Math.min(900, leaseSeconds));
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(true);
            recoverExpiredLeases(conn);
            conn.setAutoCommit(false);
            try {
                String sql = "SELECT id, task_key, task_type, status, priority, scope_group_id, scope_user_id, " +
                        "input_refs, attempt_count, max_attempts, not_before, lease_owner, lease_until " +
                        "FROM model_analysis_tasks " +
                        "WHERE status IN ('PENDING','RETRY') AND not_before <= CURRENT_TIMESTAMP(3) " +
                        "AND attempt_count < max_attempts" +
                        (taskType == null ? "" : " AND task_type" + (includeTaskType ? "=?" : "<>?")) +
                        " ORDER BY priority DESC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED";
                AnalysisTask task;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    if (taskType != null) ps.setString(1, taskType);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return Optional.empty();
                        }
                        task = map(rs);
                    }
                }

                LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(lease);
                String update = "UPDATE model_analysis_tasks SET status='PROCESSING', attempt_count=attempt_count+1, " +
                        "lease_owner=?, lease_until=?, updated_at=CURRENT_TIMESTAMP(3) WHERE id=?";
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setString(1, worker);
                    ps.setTimestamp(2, Timestamp.valueOf(leaseUntil));
                    ps.setLong(3, task.id());
                    if (ps.executeUpdate() != 1) throw new SQLException("分析任务抢占失败 id=" + task.id());
                }
                conn.commit();
                return Optional.of(new AnalysisTask(task.id(), task.taskKey(), task.taskType(), "PROCESSING",
                        task.priority(), task.scopeGroupId(), task.scopeUserId(), task.inputRefsJson(),
                        task.attemptCount() + 1, task.maxAttempts(), task.notBefore(), worker, leaseUntil));
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlException) throw sqlException;
                throw new SQLException("领取分析任务失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void recoverExpiredLeases(Connection conn) throws SQLException {
        String sql = "UPDATE model_analysis_tasks SET status=CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'RETRY' END, " +
                "not_before=CASE WHEN attempt_count >= max_attempts THEN not_before " +
                "ELSE CURRENT_TIMESTAMP(3) END, lease_owner=NULL, lease_until=NULL, " +
                "error_code=CASE WHEN attempt_count >= max_attempts THEN 'LEASE_EXPIRED_MAX_ATTEMPTS' ELSE error_code END, " +
                "updated_at=CURRENT_TIMESTAMP(3) WHERE status='PROCESSING' AND lease_until < CURRENT_TIMESTAMP(3)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public void complete(AnalysisTask task, String resultJson, String modelName, String parserVersion,
                         Integer inputTokens, Integer outputTokens, Long costMicrounits) throws SQLException {
        String sql = "UPDATE model_analysis_tasks SET status='SUCCEEDED', result_json=?, model_name=?, " +
                "parser_version=?, input_tokens=?, output_tokens=?, cost_microunits=?, lease_owner=NULL, " +
                "lease_until=NULL, error_code=NULL, error_detail=NULL, updated_at=CURRENT_TIMESTAMP(3) " +
                "WHERE id=? AND status='PROCESSING' AND lease_owner=?";
        int updated = updateTask(sql, resultJson, modelName, parserVersion, inputTokens, outputTokens,
                costMicrounits, task.id(), task.leaseOwner());
        if (updated != 1) throw new SQLException("分析任务完成状态未写入 id=" + task.id());
    }

    public void fail(AnalysisTask task, String errorCode, String detail) throws SQLException {
        boolean dead = task.attemptCount() >= task.maxAttempts();
        long delaySeconds = Math.min(3600L, 5L * (1L << Math.min(8, Math.max(0, task.attemptCount() - 1))));
        String sql = "UPDATE model_analysis_tasks SET status=?, not_before=?, error_code=?, error_detail=?, " +
                "lease_owner=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(3) " +
                "WHERE id=? AND status='PROCESSING' AND lease_owner=?";
        int updated = updateTask(sql, dead ? "DEAD" : "RETRY",
                Timestamp.valueOf(LocalDateTime.now().plusSeconds(dead ? 0 : delaySeconds)),
                trim(errorCode, 64), trim(detail, 4000), task.id(), task.leaseOwner());
        if (updated != 1) throw new SQLException("分析任务失败状态未写入 id=" + task.id());
    }

    public boolean renew(AnalysisTask task, int leaseSeconds) throws SQLException {
        String sql = "UPDATE model_analysis_tasks SET lease_until=?, updated_at=CURRENT_TIMESTAMP(3) " +
                "WHERE id=? AND status='PROCESSING' AND lease_owner=?";
        return updateTask(sql, Timestamp.valueOf(LocalDateTime.now().plusSeconds(Math.max(10, leaseSeconds))),
                task.id(), task.leaseOwner()) == 1;
    }

    public int recoverExpiredNow() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE model_analysis_tasks SET status=CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'RETRY' END, " +
                             "lease_owner=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(3) " +
                             "WHERE status='PROCESSING' AND lease_until < CURRENT_TIMESTAMP(3)")) {
            return ps.executeUpdate();
        }
    }

    private int updateTask(String sql, Object... values) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            return ps.executeUpdate();
        }
    }

    private static AnalysisTask map(ResultSet rs) throws SQLException {
        Timestamp notBefore = rs.getTimestamp("not_before");
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new AnalysisTask(rs.getLong("id"), rs.getString("task_key"), rs.getString("task_type"),
                rs.getString("status"), rs.getInt("priority"), rs.getString("scope_group_id"),
                rs.getString("scope_user_id"), rs.getString("input_refs"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), notBefore == null ? null : notBefore.toLocalDateTime(),
                rs.getString("lease_owner"), leaseUntil == null ? null : leaseUntil.toLocalDateTime());
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
