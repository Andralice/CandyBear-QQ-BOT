package com.start.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文管理服务
 * 负责：
 * 1. 对话线程追踪
 * 2. 上下文记忆
 * 3. 话题连续性检测
 */
public class ConversationService {

    // 内存中的对话状态（减少数据库查询）
    private final Map<String, ConversationThread> activeThreads = new ConcurrentHashMap<>();
    private final Map<String, List<String>> groupTopics = new ConcurrentHashMap<>();

    public static class ConversationThread {
        String threadId;          // group_123_456
        String groupId;
        String userId;
        String currentTopic;
        LocalDateTime startTime;
        LocalDateTime lastActive;
        int turnCount;
        List<String> messageHistory = new ArrayList<>();
        boolean robotInvolved;
    }

    /**
     * 检测消息是否属于现有对话线程
     */
    public Optional<String> detectThread(String groupId, String userId, String message) {
        String threadKey = groupId + "_" + userId;
        ConversationThread thread = activeThreads.get(threadKey);

        if (thread != null) {
            // 检查时间连续性（5分钟内）
            if (thread.lastActive.isAfter(LocalDateTime.now().minusMinutes(5))) {
                // 检查话题相关性
                if (isRelatedTopic(message, thread.currentTopic)) {
                    thread.lastActive = LocalDateTime.now();
                    thread.turnCount++;
                    thread.messageHistory.add(message);
                    return Optional.of(thread.threadId);
                }
            }
        }

        // 创建新线程
        return createNewThread(groupId, userId, message);
    }

    /**
     * 为AI构建增强的上下文
     */
    public String buildEnhancedContext(String threadId, String currentMessage) {
        ConversationThread thread = activeThreads.get(threadId);
        if (thread == null) return currentMessage;

        StringBuilder context = new StringBuilder();

        // 添加对话摘要
        if (!thread.messageHistory.isEmpty()) {
            context.append("【对话上下文】\n");
            for (int i = Math.max(0, thread.messageHistory.size() - 3);
                 i < thread.messageHistory.size(); i++) {
                context.append(thread.messageHistory.get(i)).append("\n");
            }
            context.append("\n");
        }

        context.append("【当前消息】\n").append(currentMessage);

        return context.toString();
    }

    /**
     * 提取和更新群聊话题
     */
    public void updateGroupTopics(String groupId, String message) {
        List<String> topics = extractTopics(message);
        groupTopics.computeIfAbsent(groupId, k -> new ArrayList<>())
                .addAll(topics);

        // 限制话题历史长度
        List<String> history = groupTopics.get(groupId);
        if (history.size() > 20) {
            groupTopics.put(groupId, history.subList(history.size() - 10, history.size()));
        }
    }

    /**
     * 获取群聊热门话题
     */
    public List<String> getHotTopics(String groupId) {
        List<String> topics = groupTopics.getOrDefault(groupId, new ArrayList<>());
        Map<String, Integer> frequency = new HashMap<>();

        for (String topic : topics) {
            frequency.put(topic, frequency.getOrDefault(topic, 0) + 1);
        }

        return frequency.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    // 私有辅助方法
    private Optional<String> createNewThread(String groupId, String userId, String message) {
        String threadId = "thread_" + System.currentTimeMillis() + "_" +
                groupId + "_" + userId;

        ConversationThread thread = new ConversationThread();
        thread.threadId = threadId;
        thread.groupId = groupId;
        thread.userId = userId;
        thread.currentTopic = extractMainTopic(message);
        thread.startTime = LocalDateTime.now();
        thread.lastActive = LocalDateTime.now();
        thread.turnCount = 1;
        thread.messageHistory.add(message);

        activeThreads.put(threadId, thread);
        return Optional.of(threadId);
    }

    private boolean isRelatedTopic(String newMessage, String currentTopic) {
        // 简单的关键词匹配
        Set<String> newTopics = new HashSet<>(extractTopics(newMessage));
        return newTopics.contains(currentTopic) ||
                currentTopic.contains(newMessage.substring(0, Math.min(5, newMessage.length())));
    }

    private List<String> extractTopics(String message) {
        // 实现话题提取逻辑
        return Arrays.asList(message.split(" "));
    }

    private String extractMainTopic(String message) {
        List<String> topics = extractTopics(message);
        return topics.isEmpty() ? "其他" : topics.get(0);
    }
}