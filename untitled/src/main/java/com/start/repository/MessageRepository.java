package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.start.config.DatabaseConfig.getConnection;
public class MessageRepository extends BaseRepository {
    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);

    /**
     * 保存消息
     */
    public DatabaseResult<Long> saveMessage(Map<String, Object> data) {
        return safeExecute(() -> {
            String sql = "INSERT INTO messages (session_id, user_id, content, is_robot_reply, " +
                    "is_private, group_id, reply_to_id, topics, image_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            // 提取参数，处理null值
            String sessionId = getStringValue(data, "sessionId", "");
            String userId = getStringValue(data, "userId", "");
            String content = getStringValue(data, "content", "");
            boolean isRobotReply = getBooleanValue(data, "isRobotReply", false);
            boolean isPrivate = getBooleanValue(data, "isPrivate", false);
            String groupId = getStringValue(data, "groupId", null);
            Long replyToId = getLongValue(data, "replyToId", null);
            String topics = getStringValue(data, "topics", null);
            String imageData = getStringValue(data, "imageData", null);

            return executeInsert(sql,
                    sessionId, userId, content, isRobotReply, isPrivate, groupId, replyToId, topics, imageData
            ).getData();
        });
    }

    /**
     * 根据Session ID查找消息
     */
    public DatabaseResult<List<Map<String, Object>>> findBySessionId(String sessionId, int limit) {
        String sql = "SELECT * FROM messages WHERE session_id = ? " +
                "ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, this::mapToHashMap, sessionId, limit);
    }

    /**
     * 获取用户最近的消息
     */
    public DatabaseResult<List<String>> findUserRecentMessages(String userId, int limit) {
        String sql = "SELECT content FROM messages WHERE user_id = ? " +
                "AND is_robot_reply = FALSE ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, rs -> {
            try {
                return rs.getString("content");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, userId, limit);
    }

    /**
     * 获取群组最近的消息
     */
    public DatabaseResult<List<ChatMessage>> findGroupRecentMessages(String groupId, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, limit);
    }

    /**
     * 获取对话上下文
     */
    public DatabaseResult<List<ChatMessage>> findConversationContext(String groupId, int minutes, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "AND created_at >= DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
                "ORDER BY created_at ASC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, minutes, limit);
    }

    /**
     * 获取未回复的问题
     */
    public DatabaseResult<List<ChatMessage>> findUnrepliedQuestions(String groupId) {
        String sql = "SELECT m1.* FROM messages m1 " +
                "WHERE m1.group_id = ? AND m1.is_robot_reply = FALSE " +
                "AND NOT EXISTS (SELECT 1 FROM messages m2 " +
                "WHERE m2.reply_to_id = m1.id AND m2.is_robot_reply = TRUE) " +
                "ORDER BY m1.created_at DESC LIMIT 5";

        return executeQuery(sql, this::mapToChatMessage, groupId);
    }

    /**
     * 获取用户与机器人的对话历史
     */
    public DatabaseResult<List<ChatMessage>> findUserBotConversation(String groupId, String userId, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "AND (user_id = ? OR user_id = 'candybear') " +
                "ORDER BY created_at ASC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, userId, limit);
    }

    /**
     * 保存主动回复决策日志
     */
    public DatabaseResult<Integer> saveActiveReplyLog(Map<String, Object> data) {
        return safeExecute(() -> {
            String sql = "INSERT INTO active_reply_logs " +
                    "(group_id, user_id, message_content, decision, " +
                    "decision_reason, confidence, replied_content) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

            return executeUpdate(sql,
                    getStringValue(data, "groupId", ""),
                    getStringValue(data, "userId", ""),
                    getStringValue(data, "messageContent", ""),
                    getStringValue(data, "decision", ""),
                    getStringValue(data, "decisionReason", ""),
                    getDoubleValue(data, "confidence", 0.5),
                    getStringValue(data, "repliedContent", "")
            ).getData();
        });
    }

    /**
     * 根据ID获取消息
     */
    public DatabaseResult<ChatMessage> findMessageById(Long messageId) {
        String sql = "SELECT * FROM messages WHERE id = ?";

        DatabaseResult<List<ChatMessage>> result = executeQuery(sql, this::mapToChatMessage, messageId);
        if (result.isSuccess()) {
            List<ChatMessage> messages = result.getData();
            return DatabaseResult.success(
                    messages.isEmpty() ? null : messages.get(0)
            );
        } else {
            return DatabaseResult.failure(result.getError());
        }
    }
    /**
     * 获取指定用户在指定上下文（群或私聊）中，ID 大于 lastMessageId 的消息（最多 limit 条）
     * 用于增量更新用户画像和好感度
     */
    /**
     * 获取指定用户在指定上下文（群或私聊）中，ID 大于 lastMessageId 的消息（最多 limit 条）
     */
    public DatabaseResult<List<ChatMessage>> findMessagesAfterId(String userId, String groupId, long lastMessageId, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM messages WHERE user_id = ? AND is_robot_reply = FALSE AND id > ? ");

        if (groupId == null) {
            sql.append("AND group_id IS NULL ");
        } else {
            sql.append("AND group_id = ? ");
        }

        sql.append("ORDER BY id ASC LIMIT ?");

        return safeExecute(() -> {
            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;

            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql.toString());

                pstmt.setString(1, userId);
                pstmt.setLong(2, lastMessageId);

                if (groupId != null) {
                    pstmt.setString(3, groupId);
                    pstmt.setInt(4, limit);
                } else {
                    pstmt.setInt(3, limit);
                }

                rs = pstmt.executeQuery();
                List<ChatMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    ChatMessage msg = mapToChatMessage(rs);
                    if (msg != null) {
                        messages.add(msg);
                    }
                }

                return messages; // ✅ 返回 List<ChatMessage>，由 safeExecute 包装
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }
    /**
     * 搜索群聊历史消息，支持按关键词、用户、时间范围过滤。
     */
    public DatabaseResult<List<ChatMessage>> searchMessages(String groupId, String keyword, String userId,
                                                            String dateFrom, String dateTo, int limit) {
        return safeExecute(() -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM messages WHERE group_id = ? ");
            List<Object> params = new ArrayList<>();
            params.add(groupId);

            if (keyword != null && !keyword.isBlank()) {
                sql.append("AND content LIKE ? ");
                params.add("%" + keyword + "%");
            }
            if (userId != null && !userId.isBlank()) {
                sql.append("AND user_id = ? ");
                params.add(userId);
            }
            if (dateFrom != null && !dateFrom.isBlank()) {
                sql.append("AND created_at >= ? ");
                params.add(normalizeDateFrom(dateFrom));
            }
            if (dateTo != null && !dateTo.isBlank()) {
                sql.append("AND created_at <= ? ");
                params.add(normalizeDateTo(dateTo));
            }

            sql.append("ORDER BY created_at DESC LIMIT ?");
            params.add(limit);

            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;
            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql.toString());
                for (int i = 0; i < params.size(); i++) {
                    pstmt.setObject(i + 1, params.get(i));
                }
                rs = pstmt.executeQuery();
                List<ChatMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    ChatMessage msg = mapToChatMessage(rs);
                    if (msg != null) messages.add(msg);
                }
                logger.debug("searchMessages: sql={}, params={}, found={}", sql, params, messages.size());
                return messages;
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }

    /** "2026-06-05" → "2026-06-05 00:00:00", "2026-06-05 14:30" → 原样 */
    private String normalizeDateFrom(String s) {
        s = s.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) s += " 00:00:00";
        return s;
    }
    private String normalizeDateTo(String s) {
        s = s.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) s += " 23:59:59";
        return s;
    }

    /**
     * 统计群组消息数量
     */
    public DatabaseResult<Integer> countGroupMessages(String groupId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE group_id = ?";

        return executeQuerySingle(sql, rs -> {
            try {
                return rs.getInt(1);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, groupId);
    }

    /**
     * 搜索历史图片描述（按关键词模糊匹配 content 中的图片描述文本）
     */
    public DatabaseResult<List<ChatMessage>> searchImageDescriptions(String groupId, String keyword, int limit) {
        return safeExecute(() -> {
            boolean hasGroup = groupId != null && !groupId.isBlank();
            String sql = "SELECT * FROM messages WHERE is_robot_reply = FALSE " +
                    (hasGroup ? "AND group_id = ? " : "") +
                    "AND (image_data IS NOT NULL OR content LIKE '%图片%内容%') " +
                    "AND (content LIKE ? OR image_data LIKE ?) " +
                    "ORDER BY created_at DESC LIMIT ?";

            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;
            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql);
                int idx = 1;
                if (hasGroup) pstmt.setString(idx++, groupId);
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setInt(idx, limit);
                rs = pstmt.executeQuery();
                List<ChatMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    ChatMessage msg = mapToChatMessage(rs);
                    if (msg != null) messages.add(msg);
                }
                return messages;
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }

    /**
     * 获取热门话题
     */
    public DatabaseResult<List<String>> findPopularTopics(String groupId, int days) {
        String sql = "SELECT topics, COUNT(*) as count FROM messages " +
                "WHERE group_id = ? AND topics IS NOT NULL " +
                "AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) " +
                "GROUP BY topics ORDER BY count DESC LIMIT 5";

        return executeQuery(sql, rs -> {
            try {
                return rs.getString("topics");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, groupId, days);
    }

    // ===== 辅助方法 =====

    private ChatMessage mapToChatMessage(ResultSet rs)  {
        try {


        ChatMessage message = new ChatMessage();
        message.setId(rs.getLong("id"));
        message.setGroupId(rs.getString("group_id"));
        message.setUserId(rs.getString("user_id"));
        message.setContent(rs.getString("content"));
        message.setIsRobotReply(rs.getBoolean("is_robot_reply"));
        message.setIsPrivate(rs.getBoolean("is_private"));

        Long replyToId = rs.getLong("reply_to_id");
        if (!rs.wasNull()) message.setReplyToId(replyToId);

        message.setTopics(rs.getString("topics"));
        message.setSessionId(rs.getString("session_id"));

        try { message.setImageData(rs.getString("image_data")); } catch (SQLException ignored) {}

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) message.setCreatedAt(createdAt.toLocalDateTime());

        return message;}
        catch (Exception e){
            return null;
        }
    }

    public Map<String, Object> mapToHashMap(ResultSet rs) {
        try{
        Map<String, Object> map = new HashMap<>();
        ResultSetMetaData metaData = rs.getMetaData();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String columnName = metaData.getColumnName(i);
            map.put(columnName, rs.getObject(i));
        }
        return map;}
        catch (Exception e){
            return null;
        }
    }


    public DatabaseResult<List<Map<String, Object>>> queryMessagesWithActiveFlag(String groupId) {
        String sql = """
        SELECT 
            m.id,
            m.content,
            m.topics,
            m.created_at,
            CASE WHEN a.id IS NOT NULL THEN 1 ELSE 0 END AS is_active
        FROM messages m
        LEFT JOIN active_reply_logs a 
            ON m.group_id = a.group_id
            AND m.content = a.replied_content
            AND a.decision = 'reply'
            AND ABS(TIMESTAMPDIFF(SECOND, m.created_at, a.created_at)) <= 10
        WHERE m.group_id = ?
          AND m.is_robot_reply = TRUE
          AND m.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
        ORDER BY m.created_at DESC
        LIMIT 2000
        """;
        return executeQuery(sql, this::mapToHashMap, groupId);
    }

    private String getStringValue(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBooleanValue(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    private Long getLongValue(Map<String, Object> data, String key, Long defaultValue) {
        Object value = data.get(key);
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        return defaultValue;
    }

    private Double getDoubleValue(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return defaultValue;
    }
}
