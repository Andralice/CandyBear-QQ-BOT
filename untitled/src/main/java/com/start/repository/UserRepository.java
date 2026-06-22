package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.ChatUser;
import lombok.SneakyThrows;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * 用户数据访问类
 */
public class UserRepository extends BaseRepository {

    /**
     * 创建或更新用户
     */
    public DatabaseResult<Void> createOrUpdateUser(String userId, String nickname) {
        return safeExecute(() -> {
            // 先检查用户是否存在
            String checkSql = "SELECT COUNT(*) FROM users WHERE user_id = ?";
            try (var conn = DatabaseConfig.getConnection();
                 var pstmt = conn.prepareStatement(checkSql)) {

                pstmt.setString(1, userId);
                try (var rs = pstmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        // 更新用户
                        updateUser(userId, nickname);
                    } else {
                        // 创建新用户
                        createUser(userId, nickname);
                    }
                }
            }
            return null;
        });
    }

    private void createUser(String userId, String nickname) throws SQLException {
        String sql = "INSERT INTO users (user_id, nickname, first_seen, last_active, total_messages) " +
                "VALUES (?, ?, NOW(), NOW(), 1)";
        executeUpdate(sql, userId, nickname).getDataOrElse(0);
    }

    private void updateUser(String userId, String nickname) throws SQLException {
        // 只有真正的好昵称才更新，避免空字符串或"未知用户"覆盖正确昵称
        if (nickname != null && !nickname.isEmpty() && !"未知用户".equals(nickname)) {
            String sql = "UPDATE users SET nickname = ?, last_active = NOW(), " +
                    "total_messages = total_messages + 1 WHERE user_id = ?";
            executeUpdate(sql, nickname, userId).getDataOrElse(0);
        } else {
            String sql = "UPDATE users SET last_active = NOW(), " +
                    "total_messages = total_messages + 1 WHERE user_id = ?";
            executeUpdate(sql, userId).getDataOrElse(0);
        }
    }

    /**
     * 根据用户ID查找用户
     */
    public DatabaseResult<Optional<ChatUser>> findUserById(String userId) {
        String sql = "SELECT * FROM users WHERE user_id = ?";

        DatabaseResult<List<ChatUser>> result = executeQuery(sql, this::mapToUser, userId);

        if (result.isSuccess()) {
            List<ChatUser> users = result.getData();
            return DatabaseResult.success(
                    users.isEmpty() ? Optional.empty() : Optional.of(users.get(0))
            );
        } else {
            return DatabaseResult.failure(result.getError());
        }
    }

    /**
     * 增加用户消息计数
     */
    public DatabaseResult<Integer> incrementMessageCount(String userId) {
        String sql = "UPDATE users SET total_messages = total_messages + 1, " +
                "last_active = NOW() WHERE user_id = ?";
        return executeUpdate(sql, userId);
    }

    /**
     * 添加用户偏好
     */
    public DatabaseResult<Integer> addUserPreference(String userId, String topic, int score) {
        return safeExecute(() -> {
            // 先检查是否已存在
            String checkSql = "SELECT id FROM user_preferences WHERE user_id = ? AND topic = ?";
            DatabaseResult<Long> checkResult = executeQuerySingle(checkSql, rs -> {
                try {
                    return rs.getLong("id");
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, userId, topic);

            if (checkResult.isSuccess() && checkResult.getData() != null) {
                // 更新已有偏好
                Long existingId = checkResult.getData();
                String updateSql = "UPDATE user_preferences SET score = score + ?, " +
                        "last_updated = NOW() WHERE id = ?";
                return executeUpdate(updateSql, score, existingId).getDataOrElse(0);
            } else {
                // 创建新偏好
                String insertSql = "INSERT INTO user_preferences (user_id, topic, score) VALUES (?, ?, ?)";
                return executeUpdate(insertSql, userId, topic, score).getDataOrElse(0);
            }
        });
    }

    /**
     * 获取用户偏好话题
     */
    public DatabaseResult<List<String>> getUserPreferences(String userId) {
        String sql = "SELECT topic FROM user_preferences WHERE user_id = ? " +
                "ORDER BY score DESC LIMIT 5";

        return executeQuery(sql, rs -> {
            try {
                return rs.getString("topic");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, userId);
    }

    /**
     * 获取用户最近活跃时间
     */
    public DatabaseResult<LocalDateTime> getLastActiveTime(String userId) {
        String sql = "SELECT last_active FROM users WHERE user_id = ?";

        return executeQuerySingle(sql, rs -> {
            Timestamp timestamp = null;
            try {
                timestamp = rs.getTimestamp("last_active");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return timestamp != null ? timestamp.toLocalDateTime() : null;
        }, userId);
    }

    /**
     * 结果集映射到ChatUser对象
     */
    @SneakyThrows
    private ChatUser mapToUser(ResultSet rs) {
        try{
        ChatUser user = new ChatUser();
        user.setId(rs.getLong("id"));
        user.setUserId(rs.getString("user_id"));
        user.setNickname(rs.getString("nickname"));

        var firstSeen = rs.getTimestamp("first_seen");
        if (firstSeen != null) user.setFirstSeen(firstSeen.toLocalDateTime());

        var lastActive = rs.getTimestamp("last_active");
        if (lastActive != null) user.setLastActive(lastActive.toLocalDateTime());

        user.setTotalMessages(rs.getInt("total_messages"));

        return user;}catch (SQLException e){
            throw new IllegalStateException("Failed to map ResultSet to ChatUser", e);
        }
    }
}