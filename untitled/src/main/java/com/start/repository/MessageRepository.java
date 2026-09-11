package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.ChatMessage;
import com.start.model.MessagePart;
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
            String sql = "INSERT INTO messages (session_id, user_id, content, raw_content, source_event_key, " +
                    "is_robot_reply, is_private, group_id, reply_to_id, topics, image_data) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE session_id = VALUES(session_id), content = VALUES(content), " +
                    "raw_content = COALESCE(VALUES(raw_content), raw_content), topics = VALUES(topics), " +
                    "image_data = COALESCE(VALUES(image_data), image_data)";

            // 提取参数，处理null值
            String sessionId = getStringValue(data, "sessionId", "");
            String userId = getStringValue(data, "userId", "");
            String content = getStringValue(data, "content", "");
            String rawContent = getStringValue(data, "rawContent", null);
            String sourceEventKey = getStringValue(data, "sourceEventKey", null);
            boolean isRobotReply = getBooleanValue(data, "isRobotReply", false);
            boolean isPrivate = getBooleanValue(data, "isPrivate", false);
            String groupId = getStringValue(data, "groupId", null);
            Long replyToId = getLongValue(data, "replyToId", null);
            String topics = getStringValue(data, "topics", null);
            String imageData = getStringValue(data, "imageData", null);

            return executeInsert(sql,
                    sessionId, userId, content, rawContent, sourceEventKey, isRobotReply, isPrivate,
                    groupId, replyToId, topics, imageData
            ).getData();
        });
    }

    /**
     * Inserts a source-keyed OneBot inbound message exactly once. A redelivered
     * event must not create another row or be counted as another user message.
     */
    public DatabaseResult<Integer> insertInboundMessageIfAbsent(Map<String, Object> data) {
        return insertMessageEnvelopeIfAbsent(data, List.of());
    }

    /** Stores the immutable OneBot envelope and all ordered parts in one transaction. */
    public DatabaseResult<Integer> insertMessageEnvelopeIfAbsent(Map<String, Object> data,
                                                                 List<MessagePart> parts) {
        return safeExecute(() -> {
            String messageSql = "INSERT INTO messages (session_id, user_id, content, raw_content, " +
                    "source_event_key, is_robot_reply, is_private, group_id, reply_to_id, topics, image_data, " +
                    "provider_message_id, raw_event_json, raw_segments_json, reply_provider_message_id, " +
                    "sender_type, availability_status, received_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))";
            String partSql = "INSERT INTO message_parts (message_id, part_index, part_type, raw_part_json, " +
                    "text_value, source_url, provider_file_id, target_id, mime_type, byte_size, availability_status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            String taskSql = "INSERT INTO model_analysis_tasks (task_key, task_type, scope_group_id, " +
                    "scope_user_id, input_refs, max_attempts) VALUES (?, ?, ?, ?, ?, ?)";

            try (Connection conn = DatabaseConfig.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement message = conn.prepareStatement(messageSql, Statement.RETURN_GENERATED_KEYS)) {
                    message.setString(1, getStringValue(data, "sessionId", ""));
                    message.setString(2, getStringValue(data, "userId", ""));
                    message.setString(3, getStringValue(data, "content", ""));
                    message.setString(4, getStringValue(data, "rawContent", null));
                    message.setString(5, getStringValue(data, "sourceEventKey", null));
                    message.setBoolean(6, getBooleanValue(data, "isRobotReply", false));
                    message.setBoolean(7, getBooleanValue(data, "isPrivate", false));
                    message.setString(8, getStringValue(data, "groupId", null));
                    message.setObject(9, getLongValue(data, "replyToId", null));
                    message.setString(10, getStringValue(data, "topics", null));
                    message.setString(11, getStringValue(data, "imageData", null));
                    message.setString(12, getStringValue(data, "providerMessageId", null));
                    message.setString(13, getStringValue(data, "rawEventJson", null));
                    message.setString(14, getStringValue(data, "rawSegmentsJson", null));
                    message.setString(15, getStringValue(data, "replyProviderMessageId", null));
                    message.setString(16, getStringValue(data, "senderType", "USER"));
                    message.setString(17, getStringValue(data, "availabilityStatus", "AVAILABLE"));

                    message.executeUpdate();

                    long messageId;
                    try (ResultSet keys = message.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("消息已插入但未返回主键");
                        messageId = keys.getLong(1);
                    }

                    if (parts != null && !parts.isEmpty()) {
                        try (PreparedStatement part = conn.prepareStatement(partSql, Statement.RETURN_GENERATED_KEYS);
                             PreparedStatement task = conn.prepareStatement(taskSql)) {
                            for (MessagePart value : parts) {
                                part.setLong(1, messageId);
                                part.setInt(2, value.partIndex());
                                part.setString(3, value.partType());
                                part.setString(4, value.rawPartJson());
                                part.setString(5, value.textValue());
                                part.setString(6, value.sourceUrl());
                                part.setString(7, value.providerFileId());
                                part.setString(8, value.targetId());
                                part.setString(9, value.mimeType());
                                part.setObject(10, value.byteSize());
                                part.setString(11, value.availabilityStatus());
                                part.executeUpdate();
                                long partId;
                                try (ResultSet partKeys = part.getGeneratedKeys()) {
                                    if (!partKeys.next()) throw new SQLException("消息部件已插入但未返回主键");
                                    partId = partKeys.getLong(1);
                                }
                                String taskType = taskTypeFor(value);
                                if (taskType != null) {
                                    task.setString(1, "part:" + partId + ":" + taskType + ":v1");
                                    task.setString(2, taskType);
                                    task.setString(3, getStringValue(data, "groupId", null));
                                    task.setString(4, getStringValue(data, "userId", null));
                                    task.setString(5, "{\"message_id\":" + messageId + ",\"part_id\":" + partId
                                            + ",\"part_index\":" + value.partIndex() + "}");
                                    task.setInt(6, 3);
                                    task.addBatch();
                                }
                            }
                            task.executeBatch();
                        }
                    }
                    if (!getBooleanValue(data, "isRobotReply", false)
                            && (!getStringValue(data, "content", "").isBlank()
                            || (parts != null && !parts.isEmpty()))) {
                        String profileTaskSql = "INSERT INTO model_analysis_tasks (task_key, task_type, priority, " +
                                "scope_group_id, scope_user_id, input_refs, max_attempts) VALUES (?, ?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement profileTask = conn.prepareStatement(profileTaskSql)) {
                            profileTask.setString(1, "message:" + messageId + ":PROFILE_FACTS:v1");
                            profileTask.setString(2, "EXTRACT_PROFILE_FACTS");
                            profileTask.setInt(3, 10);
                            profileTask.setString(4, getStringValue(data, "groupId", null));
                            profileTask.setString(5, getStringValue(data, "userId", null));
                            profileTask.setString(6, "{\"message_id\":" + messageId + "}");
                            profileTask.setInt(7, 3);
                            profileTask.executeUpdate();
                        }
                    }
                    String groupId = getStringValue(data, "groupId", null);
                    if (!getBooleanValue(data, "isRobotReply", false) && groupId != null && !groupId.isBlank()) {
                        String atmosphereTaskSql = "INSERT INTO model_analysis_tasks (task_key, task_type, priority, scope_group_id, input_refs, max_attempts) " +
                                "VALUES (?, 'ANALYZE_GROUP_ATMOSPHERE', 1, ?, ?, 3) ON DUPLICATE KEY UPDATE input_refs=VALUES(input_refs), updated_at=CURRENT_TIMESTAMP(3)";
                        long bucket = System.currentTimeMillis() / 300_000L;
                        try (PreparedStatement atmosphereTask = conn.prepareStatement(atmosphereTaskSql)) {
                            atmosphereTask.setString(1, "group:" + groupId + ":ATMOSPHERE:" + bucket);
                            atmosphereTask.setString(2, groupId);
                            atmosphereTask.setString(3, "{\"message_id\":" + messageId + "}");
                            atmosphereTask.executeUpdate();
                        }
                    }
                    conn.commit();
                    return 1;
                } catch (SQLIntegrityConstraintViolationException e) {
                    conn.rollback();
                    if (e.getErrorCode() == 1062 && getStringValue(data, "sourceEventKey", null) != null) {
                        return 0;
                    }
                    throw e;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        });
    }

    private static String taskTypeFor(MessagePart part) {
        return switch (part.partType()) {
            case "image" -> "PARSE_IMAGE";
            case "record" -> "TRANSCRIBE_AUDIO";
            case "video" -> "PARSE_VIDEO";
            case "file" -> "PARSE_FILE";
            case "share" -> "FETCH_LINK";
            case "forward" -> "EXPAND_FORWARD";
            case "text" -> part.textValue() != null
                    && (part.textValue().contains("http://") || part.textValue().contains("https://"))
                    ? "EXTRACT_LINKS" : null;
            default -> null;
        };
    }

    /** Returns a bounded, chronological context window for evidence analysis. */
    public DatabaseResult<List<Map<String, Object>>> findMessagesForAnalysis(long messageId,
                                                                               String groupId,
                                                                               String sessionId,
                                                                               int limit) {
        int bounded = Math.max(1, Math.min(limit, 24));
        String sql;
        Object scope;
        if (groupId != null && !groupId.isBlank()) {
            sql = "SELECT id, user_id, group_id, session_id, content, raw_content, sender_type, " +
                    "is_robot_reply, is_private, provider_message_id, reply_provider_message_id, " +
                    "reply_to_id, availability_status, created_at FROM messages WHERE group_id=? AND id<=? " +
                    "AND retracted_at IS NULL ORDER BY id DESC LIMIT ?";
            scope = groupId;
        } else {
            sql = "SELECT id, user_id, group_id, session_id, content, raw_content, sender_type, " +
                    "is_robot_reply, is_private, provider_message_id, reply_provider_message_id, " +
                    "reply_to_id, availability_status, created_at FROM messages WHERE session_id=? AND id<=? " +
                    "AND retracted_at IS NULL ORDER BY id DESC LIMIT ?";
            scope = sessionId;
        }
        DatabaseResult<List<Map<String, Object>>> result = executeQuery(sql, this::mapToHashMap,
                scope, messageId, bounded);
        if (!result.isSuccess() || result.getData() == null) return result;
        List<Map<String, Object>> chronological = new ArrayList<>(result.getData());
        Collections.reverse(chronological);
        return DatabaseResult.success(chronological);
    }

    public DatabaseResult<Map<String, Object>> findMessageById(long messageId) {
        return executeQuerySingle("SELECT * FROM messages WHERE id=?", this::mapToHashMap, messageId);
    }

    public DatabaseResult<List<Map<String, Object>>> findMessageParts(long messageId) {
        return executeQuery("SELECT id, part_index, part_type, raw_part_json, availability_status " +
                "FROM message_parts WHERE message_id=? ORDER BY part_index ASC", this::mapToHashMap, messageId);
    }

    public int redactRawEvidenceBefore(LocalDateTime before) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement messages = conn.prepareStatement(
                    "UPDATE messages SET raw_content=NULL, raw_event_json=NULL, raw_segments_json=NULL, " +
                            "redacted_at=CURRENT_TIMESTAMP(3), availability_status='REDACTED_RAW' " +
                            "WHERE created_at < ? AND redacted_at IS NULL");
                 PreparedStatement parts = conn.prepareStatement(
                         "UPDATE message_parts p JOIN messages m ON m.id=p.message_id SET " +
                                 "p.raw_part_json=JSON_OBJECT('redacted', TRUE, 'reason', 'RETENTION_EXPIRED'), " +
                                 "p.source_url=NULL, p.provider_file_id=NULL, p.storage_key=NULL, p.availability_status='EXPIRED' " +
                                 "WHERE m.created_at < ? AND p.availability_status <> 'EXPIRED'");
                 PreparedStatement evidence = conn.prepareStatement(
                         "UPDATE profile_fact_evidence e JOIN messages m ON m.id=e.message_id SET " +
                                 "e.availability_snapshot='EXPIRED', e.quote_or_span=NULL WHERE m.created_at < ?")) {
                Timestamp timestamp = Timestamp.valueOf(before);
                messages.setTimestamp(1, timestamp);
                int affected = messages.executeUpdate();
                parts.setTimestamp(1, timestamp);
                parts.executeUpdate();
                evidence.setTimestamp(1, timestamp);
                evidence.executeUpdate();
                conn.commit();
                return affected;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public DatabaseResult<Integer> handleRetraction(String providerMessageId, String groupId,
                                                     String actorId, String noticeHash) {
        return safeExecute(() -> {
            if (providerMessageId == null || providerMessageId.isBlank()) return 0;
            try (Connection conn = DatabaseConfig.getConnection()) {
                conn.setAutoCommit(false);
                String find = "SELECT id, availability_status FROM messages WHERE provider_message_id=? " +
                        "AND (? IS NULL OR group_id=?) FOR UPDATE";
                int affected = 0;
                try (PreparedStatement ps = conn.prepareStatement(find)) {
                    ps.setString(1, providerMessageId);
                    ps.setString(2, groupId);
                    ps.setString(3, groupId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long messageId = rs.getLong("id");
                            String previous = rs.getString("availability_status");
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE messages SET retracted_at=CURRENT_TIMESTAMP(3), availability_status='RETRACTED' WHERE id=?")) {
                                update.setLong(1, messageId);
                                update.executeUpdate();
                            }
                            try (PreparedStatement state = conn.prepareStatement(
                                    "INSERT INTO message_state_changes (message_id, from_status, to_status, notice_event_hash, reason_code, actor_id) VALUES (?, ?, 'RETRACTED', ?, 'ONEBOT_RECALL', ?)")) {
                                state.setLong(1, messageId); state.setString(2, previous); state.setString(3, noticeHash); state.setString(4, actorId);
                                state.executeUpdate();
                            }
                            affected++;
                        }
                    }
                }
                if (affected > 0) {
                    try (PreparedStatement evidence = conn.prepareStatement(
                            "UPDATE profile_fact_evidence e JOIN messages m ON m.id=e.message_id SET e.evidence_role='RETRACTED', e.availability_snapshot='RETRACTED' WHERE m.provider_message_id=? AND (? IS NULL OR m.group_id=?)")) {
                        evidence.setString(1, providerMessageId); evidence.setString(2, groupId); evidence.setString(3, groupId); evidence.executeUpdate();
                    }
                    try (PreparedStatement candidates = conn.prepareStatement(
                            "UPDATE profile_fact_candidates c SET status='REJECTED', audit_rationale=CONCAT(COALESCE(c.audit_rationale,''), '；原始消息已撤回') " +
                                    "WHERE EXISTS (SELECT 1 FROM profile_fact_evidence e WHERE e.candidate_id=c.id AND e.evidence_role='RETRACTED') " +
                                    "AND NOT EXISTS (SELECT 1 FROM profile_fact_evidence active WHERE active.candidate_id=c.id AND active.evidence_role='SUPPORT')")) {
                        candidates.executeUpdate();
                    }
                    try (PreparedStatement facts = conn.prepareStatement(
                            "UPDATE profile_facts f SET status='EXPIRED', valid_until=CURRENT_TIMESTAMP(3) " +
                                    "WHERE EXISTS (SELECT 1 FROM profile_fact_evidence e WHERE e.fact_id=f.id AND e.evidence_role='RETRACTED') " +
                                    "AND NOT EXISTS (SELECT 1 FROM profile_fact_evidence active WHERE active.fact_id=f.id AND active.evidence_role='SUPPORT')")) {
                        facts.executeUpdate();
                    }
                }
                conn.commit();
                return affected;
            }
        });
    }

    /** 为已入库的 OneBot 原始消息补充异步得到的图片描述。 */
    public DatabaseResult<Integer> updateImageDataBySourceEventKey(String sourceEventKey, String imageData) {
        if (sourceEventKey == null || sourceEventKey.isBlank() || imageData == null || imageData.isBlank()) {
            return DatabaseResult.success(0);
        }
        return executeUpdate("UPDATE messages SET image_data = ? WHERE source_event_key = ?",
                imageData, sourceEventKey);
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

    public DatabaseResult<Boolean> hasUserInGroup(String userId, String groupId) {
        if (userId == null || userId.isBlank() || groupId == null || groupId.isBlank()) {
            return DatabaseResult.success(false);
        }
        return executeQuerySingle(
                "SELECT EXISTS(SELECT 1 FROM messages WHERE user_id = ? AND group_id = ? LIMIT 1)",
                rs -> {
                    try {
                        return rs.getBoolean(1);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, userId, groupId);
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
