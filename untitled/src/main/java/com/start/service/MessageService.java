package com.start.service;

import com.start.model.ChatMessage;
import com.start.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.*;

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
    private final MessageRepository messageRepo;

    public MessageService() {
        this.messageRepo = new MessageRepository();
    }

    /**
     * 保存用户消息（从AIHandler调用）
     */
    public void saveUserMessage(String sessionId, String userId, String groupId,
                                String content, boolean isPrivate) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", userId);
        data.put("content", content);
        data.put("isRobotReply", false);
        data.put("isPrivate", isPrivate);

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);

            // 提取话题
            String topics = extractTopics(content);
            if (!topics.isEmpty()) {
                data.put("topics", topics);
            }
        }

        messageRepo.saveMessage(data);
    }

    /**
     * 保存AI回复
     */
    public void saveAIReply(String sessionId, String groupId, String content,
                            Long replyToId, boolean isPrivate) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", "candybear");
        data.put("content", content);
        data.put("isRobotReply", true);
        data.put("isPrivate", isPrivate);
        data.put("replyToId", replyToId);

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);
        }

        messageRepo.saveMessage(data);
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

    private String extractTopics(String content) {
        Set<String> topics = new HashSet<>();
        // ... 话题提取逻辑
        return String.join(",", topics);
    }
}