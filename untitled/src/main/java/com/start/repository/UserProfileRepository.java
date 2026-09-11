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

    /** Transitional legacy cache lookup; callers must label the result as low confidence. */
    public Optional<UserProfile> findLatestByUserId(String userId) throws SQLException {
        String sql = "SELECT * FROM user_profiles WHERE user_id=? ORDER BY updated_at DESC, id DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
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
        }
    }

    /**
     * @deprecated Legacy free-text overwrite is intentionally disabled. Use the
     * evidence candidate/fact pipeline instead.
     */
    @Deprecated
    public void saveOrUpdate(UserProfile profile) throws SQLException {
        throw new UnsupportedOperationException("旧 profile_text 覆盖逻辑已停用；请写入证据候选事实");
    }
}
