package com.start.repository;

import com.start.model.UserProfile;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

/**
 * 用户资料仓库
 */
public class UserProfileRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public UserProfileRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<UserProfile> findByUserIdAndGroupId(String userId, String groupId) throws SQLException {
        String sql = "SELECT * FROM user_profiles WHERE user_id = ? AND group_id " +
                (groupId == null ? "IS NULL" : "= ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            if (groupId != null) {
                ps.setString(2, groupId);
            }
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UserProfile profile = new UserProfile();
                profile.setId(rs.getLong("id"));
                profile.setUserId(rs.getString("user_id"));
                profile.setGroupId(rs.getString("group_id"));
                profile.setProfileText(rs.getString("profile_text"));
                profile.setMessageCountSnapshot(rs.getInt("message_count_snapshot"));
                profile.setLastMessageId(rs.getLong("last_message_id"));
                profile.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                profile.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                return Optional.of(profile);
            }
            return Optional.empty();
        }
    }

    public void saveOrUpdate(UserProfile profile) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            String updateSql = "UPDATE user_profiles SET profile_text = ?, message_count_snapshot = ?, last_message_id = ?, updated_at = NOW() " +
                    "WHERE user_id = ? AND group_id " + (profile.getGroupId() == null ? "IS NULL" : "= ?");
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, profile.getProfileText());
                ps.setInt(2, profile.getMessageCountSnapshot());
                ps.setLong(3, profile.getLastMessageId());
                ps.setString(4, profile.getUserId());
                if (profile.getGroupId() != null) {
                    ps.setString(5, profile.getGroupId());
                }
                if (ps.executeUpdate() == 0) {
                    String insertSql = "INSERT INTO user_profiles (user_id, group_id, profile_text, message_count_snapshot, last_message_id) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement ins = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, profile.getUserId());
                        ins.setString(2, profile.getGroupId());
                        ins.setString(3, profile.getProfileText());
                        ins.setInt(4, profile.getMessageCountSnapshot());
                        ins.setLong(5, profile.getLastMessageId());
                        ins.executeUpdate();
                        ResultSet keys = ins.getGeneratedKeys();
                        if (keys.next()) {
                            profile.setId(keys.getLong(1));
                        }
                    }
                }
            }
            conn.commit();
        }
    }
}
