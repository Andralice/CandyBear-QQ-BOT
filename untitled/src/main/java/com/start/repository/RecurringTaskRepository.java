package com.start.repository;

import com.start.model.RecurringTask;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RecurringTaskRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public RecurringTaskRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(RecurringTask t) throws SQLException {
        String sql = "INSERT INTO recurring_tasks (user_id, group_id, task_name, cron_expr, trigger_prompt, expire_days, enabled, next_fire_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, t.getUserId());
            ps.setString(2, t.getGroupId());
            ps.setString(3, t.getTaskName());
            ps.setString(4, t.getCronExpr());
            ps.setString(5, t.getTriggerPrompt());
            ps.setInt(6, t.getExpireDays());
            ps.setBoolean(7, t.isEnabled());
            ps.setTimestamp(8, t.getNextFireAt() != null ? Timestamp.valueOf(t.getNextFireAt()) : null);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) t.setId(keys.getLong(1));
            }
        }
    }

    /** 查询所有已到触发时间且已启用的任务 */
    public List<RecurringTask> findDueTasks() throws SQLException {
        String sql = "SELECT * FROM recurring_tasks WHERE enabled = TRUE AND next_fire_at IS NOT NULL AND next_fire_at <= NOW() ORDER BY next_fire_at ASC LIMIT 20";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return mapResults(ps);
        }
    }

    /** 更新 last_fired_at 和 next_fire_at */
    public void markFired(long id, LocalDateTime nextFireAt) throws SQLException {
        String sql = "UPDATE recurring_tasks SET last_fired_at = NOW(), next_fire_at = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, nextFireAt != null ? Timestamp.valueOf(nextFireAt) : null);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 禁用任务 */
    public void disable(long id) throws SQLException {
        String sql = "UPDATE recurring_tasks SET enabled = FALSE, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 查询用户的所有活跃任务 */
    public List<RecurringTask> listByUser(String userId, String groupId) throws SQLException {
        String sql;
        if (groupId != null && !groupId.isBlank()) {
            sql = "SELECT * FROM recurring_tasks WHERE user_id = ? AND group_id = ? AND enabled = TRUE ORDER BY created_at DESC";
        } else {
            sql = "SELECT * FROM recurring_tasks WHERE user_id = ? AND enabled = TRUE ORDER BY created_at DESC";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            if (groupId != null && !groupId.isBlank()) ps.setString(2, groupId);
            return mapResults(ps);
        }
    }

    /** 定期清理过期任务 */
    public void expireOldTasks() throws SQLException {
        String sql = "UPDATE recurring_tasks SET enabled = FALSE WHERE enabled = TRUE AND created_at < DATE_SUB(NOW(), INTERVAL expire_days DAY)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private List<RecurringTask> mapResults(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<RecurringTask> results = new ArrayList<>();
            while (rs.next()) {
                RecurringTask t = new RecurringTask();
                t.setId(rs.getLong("id"));
                t.setUserId(rs.getString("user_id"));
                t.setGroupId(rs.getString("group_id"));
                t.setTaskName(rs.getString("task_name"));
                t.setCronExpr(rs.getString("cron_expr"));
                t.setTriggerPrompt(rs.getString("trigger_prompt"));
                t.setExpireDays(rs.getInt("expire_days"));
                t.setEnabled(rs.getBoolean("enabled"));
                Timestamp lfa = rs.getTimestamp("last_fired_at");
                if (lfa != null) t.setLastFiredAt(lfa.toLocalDateTime());
                Timestamp nfa = rs.getTimestamp("next_fire_at");
                if (nfa != null) t.setNextFireAt(nfa.toLocalDateTime());
                t.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                t.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                results.add(t);
            }
            return results;
        }
    }
}
