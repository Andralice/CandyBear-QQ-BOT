package com.start.repository;

import com.start.model.LongTermMemory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class LongTermMemoryRepository {

    private final DataSource dataSource;

    public LongTermMemoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 插入一条新记忆（含可选的定时触发时间） */
    public void insert(LongTermMemory m) throws SQLException {
        String sql = "INSERT INTO long_term_memories (user_id, group_id, source_message_id, content, memory_type, keywords, importance, trigger_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.getUserId());
            ps.setString(2, m.getGroupId());
            if (m.getSourceMessageId() != null) ps.setLong(3, m.getSourceMessageId());
            else ps.setNull(3, Types.BIGINT);
            ps.setString(4, m.getContent());
            ps.setString(5, m.getMemoryType() != null ? m.getMemoryType() : "fact");
            ps.setString(6, m.getKeywords());
            ps.setInt(7, m.getImportance());
            if (m.getTriggerAt() != null) ps.setTimestamp(8, Timestamp.valueOf(m.getTriggerAt()));
            else ps.setNull(8, Types.TIMESTAMP);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) m.setId(keys.getLong(1));
        }
    }

    /** 按用户+群检索记忆，关键词模糊匹配，排除已触发的定时事件 */
    public List<LongTermMemory> search(String userId, String groupId, String keyword, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND (group_id = ? OR group_id IS NULL) AND triggered = FALSE ");
        List<String> params = new ArrayList<>();
        params.add(userId);
        params.add(groupId);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (content LIKE ? OR keywords LIKE ?) ");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }

        sql.append("ORDER BY importance DESC, recall_count DESC, created_at DESC LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, limit);

            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    /** 查询所有到期的定时事件（trigger_at <= NOW() AND triggered = FALSE） */
    public List<LongTermMemory> findDueEvents() throws SQLException {
        String sql = "SELECT * FROM long_term_memories WHERE trigger_at IS NOT NULL AND triggered = FALSE AND trigger_at <= NOW() ORDER BY trigger_at ASC LIMIT 20";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    /** 标记事件已触发 */
    public void markTriggered(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET triggered = TRUE WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 更新召回时间和计数 */
    public void markRecalled(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET last_recalled = NOW(), recall_count = recall_count + 1 WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 删除记忆 */
    public void delete(long id) throws SQLException {
        String sql = "DELETE FROM long_term_memories WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private LongTermMemory mapRow(ResultSet rs) throws SQLException {
        LongTermMemory m = new LongTermMemory();
        m.setId(rs.getLong("id"));
        m.setUserId(rs.getString("user_id"));
        m.setGroupId(rs.getString("group_id"));
        long srcMsgId = rs.getLong("source_message_id");
        if (!rs.wasNull()) m.setSourceMessageId(srcMsgId);
        m.setContent(rs.getString("content"));
        m.setMemoryType(rs.getString("memory_type"));
        m.setKeywords(rs.getString("keywords"));
        m.setImportance(rs.getInt("importance"));
        Timestamp lr = rs.getTimestamp("last_recalled");
        if (lr != null) m.setLastRecalled(lr.toLocalDateTime());
        m.setRecallCount(rs.getInt("recall_count"));
        Timestamp ta = rs.getTimestamp("trigger_at");
        if (ta != null) m.setTriggerAt(ta.toLocalDateTime());
        m.setTriggered(rs.getBoolean("triggered"));
        m.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        m.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return m;
    }
}
