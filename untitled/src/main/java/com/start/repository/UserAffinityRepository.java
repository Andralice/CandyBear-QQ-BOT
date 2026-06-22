package com.start.repository;

import com.start.model.UserAffinity;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

/**
 * UserAffinity 数据库操作类
 */
public class UserAffinityRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public UserAffinityRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<UserAffinity> findByUserIdAndGroupId(String userId, String groupId) throws SQLException {
        String sql = "SELECT * FROM user_affinity WHERE user_id = ? AND group_id " +
                (groupId == null ? "IS NULL" : "= ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            if (groupId != null) {
                ps.setString(2, groupId);
            }
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UserAffinity affinity = new UserAffinity();
                affinity.setId(rs.getLong("id"));
                affinity.setUserId(rs.getString("user_id"));
                affinity.setGroupId(rs.getString("group_id"));
                affinity.setAffinityScore(rs.getInt("affinity_score"));
                affinity.setLastUpdatedMessageId(rs.getLong("last_updated_message_id"));
                affinity.setMessageCountSnapshot(rs.getInt("message_count_snapshot"));
                affinity.setReasonLog(rs.getString("reason_log"));
                affinity.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                affinity.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                return Optional.of(affinity);
            }
            return Optional.empty();
        }
    }

    public void saveOrUpdate(UserAffinity affinity) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            String updateSql = "UPDATE user_affinity SET affinity_score = ?, last_updated_message_id = ?, message_count_snapshot = ?, reason_log = ?, updated_at = NOW() " +
                    "WHERE user_id = ? AND group_id " + (affinity.getGroupId() == null ? "IS NULL" : "= ?");
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, affinity.getAffinityScore());
                ps.setLong(2, affinity.getLastUpdatedMessageId());
                ps.setInt(3, affinity.getMessageCountSnapshot());
                ps.setString(4, affinity.getReasonLog());
                ps.setString(5, affinity.getUserId());
                if (affinity.getGroupId() != null) {
                    ps.setString(6, affinity.getGroupId());
                }
                if (ps.executeUpdate() == 0) {
                    String insertSql = "INSERT INTO user_affinity (user_id, group_id, affinity_score, last_updated_message_id, message_count_snapshot, reason_log) VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ins = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, affinity.getUserId());
                        ins.setString(2, affinity.getGroupId());
                        ins.setInt(3, affinity.getAffinityScore());
                        ins.setLong(4, affinity.getLastUpdatedMessageId());
                        ins.setInt(5, affinity.getMessageCountSnapshot());
                        ins.setString(6, affinity.getReasonLog());
                        ins.executeUpdate();
                        ResultSet keys = ins.getGeneratedKeys();
                        if (keys.next()) {
                            affinity.setId(keys.getLong(1));
                        }
                    }
                }
            }
            conn.commit();
        }
    }
}
