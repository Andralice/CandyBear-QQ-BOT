package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hankcs.hanlp.HanLP;
import com.start.model.ChatMessage;
import com.start.model.MessagePart;
import com.start.repository.MessageRepository;
import com.start.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息服务类
 * <p>
 * 负责处理QQ机器人消息的持久化存储、上下文检索以及群聊活跃度统计。
 * 主要功能包括：
 * 1. 保存用户发送的消息及AI生成的回复到数据库。
 * 2. 提取并存储消息中的话题标签（针对群聊非私密消息）。
 * 3. 获取指定会话的历史对话上下文，用于构建AI提示词。
 * 4. 统计指定群聊在特定时间窗口内的活跃程度。
 * </p>
 */
public class MessageService {
    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    private final MessageRepository messageRepo;
    private final UserRepository userRepo;

    public MessageService() {
        this.messageRepo = new MessageRepository();
        this.userRepo = new UserRepository();
    }

    /**
     * 保存用户消息（从AIHandler调用）
     */
    public void saveUserMessage(String sessionId, String userId, String groupId,
                                String content, boolean isPrivate) {
        saveInboundUserMessage(sessionId, userId, groupId, content, content, isPrivate, null);
    }

    /**
     * 持久化一条 OneBot 入站消息。content 是检索使用的规范文本，rawContent 保留协议原文；
     * sourceEventKey 使重放事件幂等，避免 AI 提交阶段再产生第二条用户记录。
     */
    public void saveInboundUserMessage(String sessionId, String userId, String groupId,
                                       String rawContent, String content, boolean isPrivate,
                                       String sourceEventKey) {
        saveInboundUserMessage(sessionId, userId, groupId, rawContent, content, isPrivate,
                sourceEventKey, null, null, null, null);
    }

    public void saveInboundUserMessage(String sessionId, String userId, String groupId,
                                       String rawContent, String content, boolean isPrivate,
                                       String sourceEventKey, String providerMessageId,
                                       String rawEventJson, JsonNode rawSegments,
                                       String replyProviderMessageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", userId);
        data.put("content", content == null ? "" : content);
        if (rawContent != null && !rawContent.equals(content)) data.put("rawContent", rawContent);
        if (sourceEventKey != null && !sourceEventKey.isBlank()) data.put("sourceEventKey", sourceEventKey);
        if (providerMessageId != null && !providerMessageId.isBlank()) data.put("providerMessageId", providerMessageId);
        if (rawEventJson != null && !rawEventJson.isBlank()) data.put("rawEventJson", rawEventJson);
        if (rawSegments != null && !rawSegments.isMissingNode() && !rawSegments.isNull()) {
            data.put("rawSegmentsJson", rawSegments.toString());
        }
        if (replyProviderMessageId != null && !replyProviderMessageId.isBlank()) {
            data.put("replyProviderMessageId", replyProviderMessageId);
        }
        data.put("senderType", "USER");
        data.put("availabilityStatus", "AVAILABLE");
        data.put("isRobotReply", false);
        data.put("isPrivate", isPrivate);

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);

            // 提取话题
            String topics = extractTopics(content == null ? "" : content);
            if (!topics.isEmpty()) {
                data.put("topics", topics);
            }
        }

        userRepo.createOrUpdateUser(userId, "");
        var insertResult = messageRepo.insertMessageEnvelopeIfAbsent(data, toEvidenceParts(rawSegments));
        if (insertResult.isSuccess() && insertResult.getData() != null && insertResult.getData() == 1) {
            userRepo.incrementMessageCount(userId);
        }
    }

    static List<MessagePart> toEvidenceParts(JsonNode segments) {
        if (segments == null || !segments.isArray()) return List.of();
        List<MessagePart> parts = new ArrayList<>();
        int index = 0;
        for (JsonNode segment : segments) {
            String type = segment.path("type").asText("unknown");
            JsonNode partData = segment.path("data");
            String text = valueOrNull(partData, "text");
            String url = valueOrNull(partData, "url");
            String fileId = firstNonBlank(valueOrNull(partData, "file_id"), valueOrNull(partData, "file"));
            String targetId = switch (type) {
                case "at" -> valueOrNull(partData, "qq");
                case "reply" -> valueOrNull(partData, "id");
                default -> null;
            };
            Long byteSize = parseLongOrNull(valueOrNull(partData, "file_size"));
            String availability = switch (type) {
                case "image", "record", "video", "file", "share", "forward" ->
                        url != null || fileId != null ? "PENDING" : "UNAVAILABLE_REFERENCE";
                default -> "AVAILABLE";
            };
            parts.add(new MessagePart(index++, type, segment.toString(), text, url, fileId,
                    targetId, valueOrNull(partData, "mime"), byteSize, availability));
        }
        return List.copyOf(parts);
    }

    private static String valueOrNull(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public void attachImageData(String sourceEventKey, String imageData) {
        messageRepo.updateImageDataBySourceEventKey(sourceEventKey, imageData);
    }

    /** Returns null for events without a stable OneBot message id, preserving legacy compatibility. */
    public static String sourceEventKey(String messageType, String groupId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        String scope = "group".equals(messageType) ? "group:" + groupId : "private";
        return scope + ":" + userId + ":" + messageId;
    }

    /**
     * 保存AI回复
     */
    public void saveAIReply(String sessionId, String groupId, String content,
                            Long replyToId, boolean isPrivate) {
        saveAIReply(sessionId, groupId, content, replyToId, isPrivate, null, null, null);
    }

    public void saveAIReply(String sessionId, String groupId, String content,
                            Long replyToId, boolean isPrivate, String providerMessageId,
                            String rawReceiptJson, String replyProviderMessageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", "candybear");
        data.put("content", content);
        data.put("isRobotReply", true);
        data.put("isPrivate", isPrivate);
        data.put("replyToId", replyToId);
        data.put("senderType", "BOT");
        data.put("availabilityStatus", "AVAILABLE");
        if (providerMessageId != null && !providerMessageId.isBlank()) {
            data.put("providerMessageId", providerMessageId);
            String scope = isPrivate ? "private" : "group:" + groupId;
            data.put("sourceEventKey", "outbound:" + scope + ":" + providerMessageId);
        }
        if (rawReceiptJson != null && !rawReceiptJson.isBlank()) data.put("rawEventJson", rawReceiptJson);
        if (replyProviderMessageId != null && !replyProviderMessageId.isBlank()) {
            data.put("replyProviderMessageId", replyProviderMessageId);
        }

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);
        }

        ArrayNode segments = JsonNodeFactory.instance.arrayNode();
        segments.addObject().put("type", "text").putObject("data").put("text", content);
        data.put("rawSegmentsJson", segments.toString());
        messageRepo.insertMessageEnvelopeIfAbsent(data, toEvidenceParts(segments));
    }

    /**
     * 获取对话上下文（用于AI提示词）
     */
    public String getConversationContext(String sessionId, int limit) {
        var result = messageRepo.findBySessionId(sessionId, limit);
        if (result.isSuccess()) {
            List<Map<String, Object>> messages = result.getData();
            StringBuilder context = new StringBuilder();

            for (Map<String, Object> msg : messages) {
                String role = Boolean.TRUE.equals(msg.get("is_robot_reply"))
                        ? "助手" : "用户";
                context.append(role).append(": ")
                        .append(msg.get("content")).append("\n");
            }
            return context.toString();
        }
        return "";
    }

    /**
     * 获取群聊最近活跃度
     */
    public int getGroupActivityLevel(String groupId, int minutes) {
        var result = messageRepo.findConversationContext(groupId, minutes, 50);
        if (result.isSuccess()) {
            return result.getData().size();
        }
        return 0;
    }

    /**
     * 提取消息话题标签：HanLP 关键词提取，最多 3 个；失败时回退到截断前 20 字符。
     * 模式参考 ThreadManager.extractTopic。
     */
    private String extractTopics(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            String clean = content.replaceAll("[\\p{Punct}\\s]+", " ").trim();
            if (clean.isEmpty()) return "";
            List<String> kw = HanLP.extractKeyword(clean, 3);
            if (kw != null && !kw.isEmpty()) {
                return kw.stream()
                        .filter(k -> k != null && !k.isBlank())
                        .limit(3)
                        .collect(Collectors.joining(","));
            }
        } catch (Exception e) {
            logger.debug("HanLP 话题提取失败: {}", e.getMessage());
        }
        // 回退：截断前 20 字符
        String trimmed = content.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }
}
