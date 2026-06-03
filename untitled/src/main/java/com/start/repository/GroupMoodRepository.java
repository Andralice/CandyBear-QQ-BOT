package com.start.repository;

import com.start.model.GroupMood;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

public class GroupMoodRepository {

    private final DataSource dataSource;

    public GroupMoodRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<GroupMood> findByGroupId(String groupId) throws SQLException {
        String sql = "SELECT * FROM group_mood WHERE group_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    public void save(GroupMood gm) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            String upsertSql = "INSERT INTO group_mood (group_id, mood, last_topic_throw_time) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE mood = VALUES(mood), last_topic_throw_time = VALUES(last_topic_throw_time)";
            try (PreparedStatement ps = conn.prepareStatement(upsertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, gm.getGroupId());
                ps.setInt(2, gm.getMood());
                ps.setLong(3, gm.getLastTopicThrowTime());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    gm.setId(keys.getLong(1));
                }
            }
            conn.commit();
        }
    }

    private GroupMood mapRow(ResultSet rs) throws SQLException {
        GroupMood gm = new GroupMood();
        gm.setId(rs.getLong("id"));
        gm.setGroupId(rs.getString("group_id"));
        gm.setMood(rs.getInt("mood"));
        gm.setLastTopicThrowTime(rs.getLong("last_topic_throw_time"));
        gm.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        gm.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return gm;
    }
}
