package com.start.repository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 对话线程仓库
 */
public class ConversationThreadRepository extends BaseRepository {

    /**
     * 创建或更新对话线程
     */
    public DatabaseResult<Integer> createOrUpdateThread(String threadKey, String groupId,
                                                        String userId, String lastBotReply) {
        return safeExecute(() -> {
            // 先检查是否存在
            String checkSql = "SELECT id FROM conversation_threads WHERE thread_key = ?";
            DatabaseResult<Long> checkResult = executeQuerySingle(checkSql, rs -> {
                try {
                    return rs.getLong("id");
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, threadKey);

            if (checkResult.isSuccess() && checkResult.getData() != null) {
                // 更新现有线程
                return updateThread(threadKey, lastBotReply);
            } else {
                // 创建新线程
                return createThread(threadKey, groupId, userId, lastBotReply);
            }
        });
    }

    private Integer createThread(String threadKey, String groupId, String userId, String lastBotReply)
            throws SQLException {

        String sql = "INSERT INTO conversation_threads (thread_key, group_id, user_id, " +
                "last_bot_reply, last_interaction, interaction_count) " +
                "VALUES (?, ?, ?, ?, NOW(), 1)";

        return executeUpdate(sql, threadKey, groupId, userId, lastBotReply).getData();
    }

    private Integer updateThread(String threadKey, String lastBotReply) throws SQLException {
        String sql = "UPDATE conversation_threads SET last_bot_reply = ?, " +
                "last_interaction = NOW(), interaction_count = interaction_count + 1 " +
                "WHERE thread_key = ?";

        return executeUpdate(sql, lastBotReply, threadKey).getData();
    }

    /**
     * 根据线程键查找线程
     */
    public DatabaseResult<Optional<ThreadInfo>> findThreadByKey(String threadKey) {
        String sql = "SELECT * FROM conversation_threads WHERE thread_key = ?";

        DatabaseResult<List<ThreadInfo>> result = executeQuery(sql, this::mapToThreadInfo, threadKey);
        if (result.isSuccess()) {
            List<ThreadInfo> threads = result.getData();
            return DatabaseResult.success(
                    threads.isEmpty() ? Optional.empty() : Optional.of(threads.get(0))
            );
        } else {
            return DatabaseResult.failure(result.getError());
        }
    }

    /**
     * 获取用户的所有对话线程
     */
    public DatabaseResult<List<ThreadInfo>> findUserThreads(String userId, int limit) {
        String sql = "SELECT * FROM conversation_threads WHERE user_id = ? " +
                "ORDER BY last_interaction DESC LIMIT ?";

        return executeQuery(sql, this::mapToThreadInfo, userId, limit);
    }

    /**
     * 获取群组中的活跃对话线程
     */
    public DatabaseResult<List<ThreadInfo>> findActiveThreads(String groupId, int minutes) {
        String sql = "SELECT * FROM conversation_threads WHERE group_id = ? " +
                "AND last_interaction >= DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
                "ORDER BY last_interaction DESC";

        return executeQuery(sql, this::mapToThreadInfo, groupId, minutes);
    }

    /**
     * 清理过期的对话线程
     */
    public DatabaseResult<Integer> cleanupExpiredThreads(int days) {
        String sql = "DELETE FROM conversation_threads WHERE " +
                "last_interaction < DATE_SUB(NOW(), INTERVAL ? DAY)";

        return executeUpdate(sql, days);
    }

    /**
     * 获取线程的最后交互时间
     */
    public DatabaseResult<LocalDateTime> getLastInteractionTime(String threadKey) {
        String sql = "SELECT last_interaction FROM conversation_threads WHERE thread_key = ?";

        return executeQuerySingle(sql, rs -> {
            Timestamp timestamp = null;
            try {
                timestamp = rs.getTimestamp("last_interaction");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return timestamp != null ? timestamp.toLocalDateTime() : null;
        }, threadKey);
    }

    private ThreadInfo mapToThreadInfo(ResultSet rs)  {
        try {
            ThreadInfo thread = new ThreadInfo();
        thread.setId(rs.getLong("id"));
        thread.setThreadKey(rs.getString("thread_key"));
        thread.setGroupId(rs.getString("group_id"));
        thread.setUserId(rs.getString("user_id"));
        thread.setLastBotReply(rs.getString("last_bot_reply"));
        thread.setInteractionCount(rs.getInt("interaction_count"));

        Timestamp lastInteraction = rs.getTimestamp("last_interaction");
        if (lastInteraction != null) thread.setLastInteraction(lastInteraction.toLocalDateTime());

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) thread.setCreatedAt(createdAt.toLocalDateTime());

        return thread;}catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static class ThreadInfo {
        private Long id;
        private String threadKey;
        private String groupId;
        private String userId;
        private String lastBotReply;
        private LocalDateTime lastInteraction;
        private Integer interactionCount;
        private LocalDateTime createdAt;

        // getter和setter
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getThreadKey() { return threadKey; }
        public void setThreadKey(String threadKey) { this.threadKey = threadKey; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getLastBotReply() { return lastBotReply; }
        public void setLastBotReply(String lastBotReply) { this.lastBotReply = lastBotReply; }
        public LocalDateTime getLastInteraction() { return lastInteraction; }
        public void setLastInteraction(LocalDateTime lastInteraction) { this.lastInteraction = lastInteraction; }
        public Integer getInteractionCount() { return interactionCount; }
        public void setInteractionCount(Integer interactionCount) { this.interactionCount = interactionCount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}