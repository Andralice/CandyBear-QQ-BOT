
// service/AIDatabaseService.java - 专门补充BaiLianService
// service/AIDatabaseService.java
package com.start.service;

import com.start.Main;
import com.start.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据库服务类
 */
public class AIDatabaseService {

    private final UserRepository userRepo = new UserRepository();
    private final MessageRepository messageRepo = new MessageRepository();
    private final ConversationThreadRepository threadRepo = new ConversationThreadRepository();
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    /**
     * 记录用户消息
     */
    public void recordUserMessage(String sessionId, String userId, String prompt, String groupId,Long isagent) {
        try {
            // 1. 更新用户信息
            userRepo.createOrUpdateUser(userId, "");
            userRepo.incrementMessageCount(userId);

            // 2. 保存消息
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("sessionId", sessionId);
            messageData.put("userId", userId);
            messageData.put("content", prompt);
            messageData.put("isRobotReply", false);
            messageData.put("isPrivate", groupId == null);
            messageData.put ("isAgent", isagent);

            if (groupId != null) {
                messageData.put("groupId", groupId);
            }

            // 提取简单话题
            String topics = extractTopics(prompt);
            if (!topics.isEmpty()) {
                messageData.put("topics", topics);
            }

            messageRepo.saveMessage(messageData);
            logger.debug("记录用户消息成功");

        } catch (Exception e) {
            logger.warn("记录用户消息异常: {}", e.getMessage());
        }
    }

    /**
     * 记录AI回复
     */
    public void recordAIReply(String sessionId, String userId, String fullReply,
                              String finalReply, String groupId, Long userMessageId) {
        try {
            // 1. 保存AI回复消息
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("sessionId", sessionId);
            messageData.put("userId", "candybear");
            messageData.put("content", finalReply);
            messageData.put("isRobotReply", true);
            messageData.put("isPrivate", groupId == null);
            messageData.put("replyToId", userMessageId);

            if (groupId != null) {
                messageData.put("groupId", groupId);
            }

            messageRepo.saveMessage(messageData);

            // 2. 更新对话线程
            if (groupId != null) {
                String threadKey = "group_" + groupId + "_" + userId;
                threadRepo.createOrUpdateThread(threadKey, groupId, userId, fullReply);
            }

        } catch (Exception e) {
            System.err.println("记录AI回复失败: " + e.getMessage());
        }
    }

    /**
     * 获取对话历史
     */
    public List<Map<String, Object>> getConversationHistory(String sessionId, int limit) {
        try {
            var result = messageRepo.findBySessionId(sessionId, limit);
            if (result.isSuccess()) {
                return result.getData();
            } else {
                System.err.println("获取对话历史失败: " + result.getError());
                logger.debug("获取对话历史失败: " + result.getError());
            }
        } catch (Exception e) {
            System.err.println("获取对话历史异常: " + e.getMessage());
            logger.debug("获取对话历史异常: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 获取用户偏好话题
     */
    public List<String> getUserFavoriteTopics(String userId) {
        try {
            var result = messageRepo.findUserRecentMessages(userId, 50);
            if (result.isSuccess()) {
                return analyzeTopics(result.getData());
            }
        } catch (Exception e) {
            System.err.println("获取用户话题偏好失败: " + e.getMessage());
            logger.debug("获取用户话题偏好失败: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 记录主动回复决策
     */
    public void logActiveReplyDecision(String groupId, String userId, String message,
                                       String decision, String reason, String reply) {
        try {
            Map<String, Object> logData = new HashMap<>();
            logData.put("groupId", groupId);
            logData.put("userId", userId);
            logData.put("messageContent", message);
            logData.put("decision", decision);
            logData.put("decisionReason", reason);
            logData.put("repliedContent", reply);
            logData.put("confidence", calculateConfidence(message, decision));

            messageRepo.saveActiveReplyLog(logData);

        } catch (Exception e) {
            System.err.println("记录主动回复决策失败: " + e.getMessage());
            logger.debug("记录主动回复决策失败: " + e.getMessage());
        }
    }

    /**
     * 糖果熊的性格数据
     */
    public Map<String, Object> getCandyBearPersonality() {
        Map<String, Object> personality = new HashMap<>();
        personality.put("name", "糖果熊");
        personality.put("traits", Arrays.asList("安静", "文艺", "内向", "思考型"));
        personality.put("interests", Arrays.asList("文学", "音乐", "艺术", "自然", "哲学"));

        Map<String, Object> speechStyle = new HashMap<>();
        speechStyle.put("maxLength", 25);
        speechStyle.put("minLength", 5);
        speechStyle.put("useEmoji", 0.3);
        speechStyle.put("useEllipsis", 0.4);
        speechStyle.put("replyDelayMs", 1500);
        personality.put("speechStyle", speechStyle);

        Map<String, Object> activeReply = new HashMap<>();
        activeReply.put("baseProbability", 0.8);
        activeReply.put("interestMultiplier", 1.5);
        activeReply.put("maxPerMinute", 3);
        activeReply.put("coolDownSeconds", 30);
        personality.put("activeReply", activeReply);

        return personality;
    }

    /**
     * 检查糖果熊是否应该主动参与话题
     */
    public boolean shouldJoinTopic(String message, String groupId) {
        Set<String> interestTopics = Set.of("文学", "诗歌", "音乐", "艺术", "哲学", "思考","游戏");
        String topics = extractTopics(message);
        logger.debug("candyBear: {}, groupId: {}", topics, interestTopics);        for (String interest : interestTopics) {
            if (topics.contains(interest)) {
                return Math.random() < 0.5;
            }
        }
        return false;
    }

    // ===== 私有辅助方法 =====

    private String extractTopics(String text) {
        Set<String> topics = new HashSet<>();
        if (text.contains("诗") || text.contains("文学") || text.contains("书")) topics.add("文学");
        if (text.contains("音乐") || text.contains("歌") || text.contains("曲")) topics.add("音乐");
        if (text.contains("艺术") || text.contains("画") || text.contains("美术")) topics.add("艺术");
        if (text.contains("哲学") || text.contains("思考") || text.contains("人生")) topics.add("哲学");
        if (text.contains("自然") || text.contains("风景") || text.contains("天空")) topics.add("自然");
        if (text.contains("游戏") || text.contains("运动") || text.contains("板绘")) topics.add("游戏");
        return String.join(",", topics);
    }

    private List<String> analyzeTopics(List<String> messages) {
        Map<String, Integer> topicCount = new HashMap<>();
        for (String msg : messages) {
            String topics = extractTopics(msg);
            if (!topics.isEmpty()) {
                for (String topic : topics.split(",")) {
                    topicCount.put(topic, topicCount.getOrDefault(topic, 0) + 1);
                }
            }
        }
        return topicCount.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Double calculateConfidence(String message, String decision) {
        double confidence = 0.5;
        if (message.contains("?") || message.contains("？")) confidence += 0.2;
        if (message.contains("@糖果熊") || message.contains("@机器人")) confidence += 0.3;
        return Math.min(confidence, 1.0);
    }



}