package com.start.repository;

import com.start.model.ConversationThread;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 群聊 Thread 存储。表在构造时自动创建。 */
public class ThreadRepository implements Repository {

    /** 新主题线程模型使用独立表，避免复用旧表的用户线程数据。 */
    private static final String TABLE_NAME = "conversation_threads_v2";

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public ThreadRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS conversation_threads_v2 (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id VARCHAR(64) NOT NULL,
                topic VARCHAR(255),
                status VARCHAR(16) DEFAULT 'ACTIVE',
                merged_into_id BIGINT NULL,
                participant_ids JSON,
                last_message_at TIMESTAMP NULL,
                message_count INT DEFAULT 0,
                attention_weight DOUBLE DEFAULT 0.0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_group_status (group_id, status),
                INDEX idx_group_attention (group_id, attention_weight DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create " + TABLE_NAME + " table", e);
        }
    }

    public void save(ConversationThread thread) {
        String sql = "INSERT INTO " + TABLE_NAME + " (group_id, topic, status, merged_into_id, participant_ids, last_message_at, message_count, attention_weight, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, thread.getGroupId());
            ps.setString(2, thread.getTopic());
            ps.setString(3, thread.getStatus());
            if (thread.getMergedIntoId() != null) ps.setLong(4, thread.getMergedIntoId());
            else ps.setNull(4, Types.BIGINT);
            ps.setString(5, thread.getParticipantIds());
            ps.setTimestamp(6, thread.getLastMessageAt() != null ? Timestamp.from(thread.getLastMessageAt()) : null);
            ps.setInt(7, thread.getMessageCount());
            ps.setDouble(8, thread.getAttentionWeight());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) thread.setId(keys.getLong(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save ConversationThread", e);
        }
    }

    /** 查询群内所有活跃 Thread，按权重降序。 */
    public List<ConversationThread> findActiveByGroup(String groupId) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE group_id = ? AND status IN ('ACTIVE', 'IDLE') ORDER BY attention_weight DESC";
        List<ConversationThread> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active threads", e);
        }
        return list;
    }

    /** 按 ID 查询单条 Thread。 */
    public ConversationThread findById(long id) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find thread by id", e);
        }
        return null;
    }

    /** 更新 Thread 状态（ACTIVE → IDLE → RESOLVED / MERGED）。 */
    public void updateStatus(long id, String status, Long mergedIntoId) {
        String sql = mergedIntoId != null
                ? "UPDATE " + TABLE_NAME + " SET status = ?, merged_into_id = ?, updated_at = NOW() WHERE id = ?"
                : "UPDATE " + TABLE_NAME + " SET status = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            if (mergedIntoId != null) {
                ps.setLong(2, mergedIntoId);
                ps.setLong(3, id);
            } else {
                ps.setLong(2, id);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update thread status", e);
        }
    }

    /** 消息到达后更新 Thread：增加计数、更新时间、更新参与者和权重。 */
    public void updateAfterMessage(long id, String participantIds, double newWeight) {
        String sql = "UPDATE " + TABLE_NAME + " SET message_count = message_count + 1, last_message_at = NOW(), participant_ids = ?, attention_weight = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, participantIds);
            ps.setDouble(2, newWeight);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update thread after message", e);
        }
    }

    /** 更新 Thread topic（聚合重算后持久化）。 */
    public void updateTopic(long id, String topic) {
        String sql = "UPDATE " + TABLE_NAME + " SET topic = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, topic);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update thread topic", e);
        }
    }

    /** 合并后更新 target Thread：参与者、消息数、最后消息时间，不增加计数。 */
    public void updateMergeTarget(long id, String participantIds, int messageCount, java.time.Instant lastMessageAt) {
        String sql = "UPDATE " + TABLE_NAME + " SET participant_ids = ?, message_count = ?, last_message_at = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, participantIds);
            ps.setInt(2, messageCount);
            ps.setTimestamp(3, lastMessageAt != null ? Timestamp.from(lastMessageAt) : null);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update merge target", e);
        }
    }

    /** 将超过指定分钟数无消息的 ACTIVE Thread 标记为 IDLE。 */
    public int markIdle(long idleMinutes) {
        String sql = "UPDATE " + TABLE_NAME + " SET status = 'IDLE', updated_at = NOW() WHERE status = 'ACTIVE' AND last_message_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idleMinutes);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark idle threads", e);
        }
    }

    /** 将超过指定分钟数无消息的 IDLE Thread 标记为 RESOLVED。 */
    public int markResolved(long resolveMinutes) {
        String sql = "UPDATE " + TABLE_NAME + " SET status = 'RESOLVED', updated_at = NOW() WHERE status = 'IDLE' AND last_message_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, resolveMinutes);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark resolved threads", e);
        }
    }

    private static ConversationThread mapRow(ResultSet rs) throws SQLException {
        ConversationThread t = new ConversationThread();
        t.setId(rs.getLong("id"));
        t.setGroupId(rs.getString("group_id"));
        t.setTopic(rs.getString("topic"));
        t.setStatus(rs.getString("status"));
        long mergedInto = rs.getLong("merged_into_id");
        if (!rs.wasNull()) t.setMergedIntoId(mergedInto);
        t.setParticipantIds(rs.getString("participant_ids"));
        Timestamp lma = rs.getTimestamp("last_message_at");
        if (lma != null) t.setLastMessageAt(lma.toInstant());
        t.setMessageCount(rs.getInt("message_count"));
        t.setAttentionWeight(rs.getDouble("attention_weight"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) t.setCreatedAt(ca.toInstant());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) t.setUpdatedAt(ua.toInstant());
        return t;
    }
}
