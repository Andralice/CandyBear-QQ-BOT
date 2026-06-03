package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 远行商人查询处理器（异步非阻塞版本）
 * 职责：监听关键词，跨群查询商人信息并转发
 */
public class TravelingMerchantHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(TravelingMerchantHandler.class);

    private static final String TRIGGER_KEYWORD = "远行商人";
    private static final long TARGET_GROUP_ID = 960982876L;
    private static final long TARGET_QQ = 2854203313L;

    // 等待响应的超时时间（秒）
    private static final int TIMEOUT_SECONDS = 60;

    // 用于存储待处理的查询请求 <消息ID, PendingQuery>
    private final ConcurrentHashMap<String, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    /**
     * 待处理查询的内部类
     */
    private static class PendingQuery {
        final CompletableFuture<JsonNode> future;  // 改为保存完整 JSON
        final long timestamp;
        final long sourceGroupId;
        final long sourceUserId;

        PendingQuery(long sourceGroupId, long sourceUserId) {
            this.future = new CompletableFuture<>();
            this.timestamp = System.currentTimeMillis();
            this.sourceGroupId = sourceGroupId;
            this.sourceUserId = sourceUserId;
        }

        boolean isExpired(int timeoutSeconds) {
            return System.currentTimeMillis() - timestamp > timeoutSeconds * 1000L;
        }
    }

    public TravelingMerchantHandler() {
        logger.info("✅ 远行商人处理器已初始化（异步非阻塞模式）");
    }

    @Override
    public boolean match(JsonNode message) {
        String text = extractText(message);
        if (text == null) return false;

        // 精确匹配：去除首尾空格后完全等于"远行商人"
        String trimmed = text.trim();
        return "远行商人".equals(trimmed);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String text = extractText(message);
        if (text == null) return;

        logger.info("🔍 检测到远行商人查询: {}", text);

        // 获取原始消息信息
        long sourceGroupId = extractGroupId(message);
        long sourceUserId = extractUserId(message);
        String messageId = extractMessageId(message);

        if (sourceGroupId == 0 && sourceUserId == 0) {
            logger.error("⚠️ 无法提取来源信息，跳过处理");
            return;
        }

        // 创建待处理查询
        PendingQuery query = new PendingQuery(sourceGroupId, sourceUserId);
        pendingQueries.put(messageId, query);

        logger.debug("📝 创建查询任务: {}, 当前待处理: {}", messageId, pendingQueries.size());

        try {
            // 1. 向目标群发送查询消息
            String queryMessage = "[CQ:at,qq=" + TARGET_QQ + "] 远行商人";
            bot.sendGroupReply(TARGET_GROUP_ID, queryMessage);

            logger.info("📤 已发送查询消息到群 {}", TARGET_GROUP_ID);

            // 2. 异步等待响应（不阻塞当前线程）
            query.future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenAccept(responseJson -> {
                    // 成功收到响应（完整的 JSON）
                    String textContent = extractText(responseJson);
                    String imageUrl = extractImageUrlFromMessage(responseJson);
                    
                    if (textContent != null && !textContent.isEmpty()) {
                        forwardMerchantInfo(bot, query.sourceGroupId, query.sourceUserId, textContent, imageUrl);
                    } else {
                        sendTimeoutMessage(bot, query.sourceGroupId, query.sourceUserId);
                    }
                    pendingQueries.remove(messageId);
                })
                .exceptionally(throwable -> {
                    // 超时或异常
                    logger.error("⏰ 查询超时或失败: {}", messageId);
                    sendTimeoutMessage(bot, query.sourceGroupId, query.sourceUserId);
                    pendingQueries.remove(messageId);
                    return null;
                });

        } catch (Exception e) {
            logger.error("❌ 处理查询失败", e);
            sendErrorMessage(bot, sourceGroupId, sourceUserId, e.getMessage());
            pendingQueries.remove(messageId);
        }
    }

    /**
     * 转发商人信息到原群/私聊（转换为纯文本+图片）
     */
    private void forwardMerchantInfo(Main bot, long groupId, long userId, String text, String imageUrl) {
        // 格式化文本内容
        String formattedText = formatMerchantMessage(text);
        
        StringBuilder message = new StringBuilder();
        message.append("🏪 远行商人信息\n");
        message.append("━━━━━━━━━━━━━━━\n");
        
        // 如果有图片，先发送图片 CQ 码
        if (imageUrl != null && !imageUrl.isEmpty()) {
            message.append("[CQ:image,file=").append(imageUrl).append("]\n");
        }
        
        message.append(formattedText);
        
        if (groupId != 0) {
            bot.sendGroupReply(groupId, message.toString());
            logger.info("✅ 已转发商人信息到群 {} (包含图片: {})", groupId, imageUrl != null);
        } else {
            bot.sendPrivateReply(userId, message.toString());
            logger.info("✅ 已转发商人信息到用户 {} (包含图片: {})", userId, imageUrl != null);
        }
    }

    /**
     * 格式化商人消息内容
     */
    private String formatMerchantMessage(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // 跳过空行和分隔线
            if (trimmed.isEmpty() || trimmed.matches("^[-=]{3,}$")) {
                continue;
            }
            
            // 处理标题行
            if (trimmed.contains("【远行商人】")) {
                continue; // 跳过标题，因为已经在消息头添加了
            }
            
            // 处理引用行 (>xxx)
            if (trimmed.startsWith(">")) {
                String content = trimmed.substring(1).trim();
                sb.append("✨ ").append(content).append("\n");
            }
            // 处理普通文本
            else if (!trimmed.isEmpty()) {
                sb.append("📍 ").append(trimmed).append("\n");
            }
        }
        
        return sb.toString().trim();
    }

    /**
     * 从完整的 JSON 消息中提取图片 URL
     */
    private String extractImageUrlFromMessage(JsonNode message) {
        try {
            if (message.has("message")) {
                JsonNode messageArray = message.get("message");
                if (messageArray.isArray()) {
                    for (JsonNode node : messageArray) {
                        String type = node.path("type").asText();
                        
                        // 从 markdown 类型中提取图片
                        if ("markdown".equals(type)) {
                            if (node.has("data") && node.get("data").has("content")) {
                                String content = node.get("data").get("content").asText();
                                return extractImageUrl(content);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("❌ 提取图片 URL 失败", e);
        }
        return null;
    }

    /**
     * 从 Markdown 内容中提取图片 URL
     * @param markdown Markdown 内容
     * @return 图片 URL，如果没有则返回 null
     */
    private String extractImageUrl(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return null;
        }
        
        // 匹配 ![img ...](url) 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[img[^\\]]*\\]\\(([^)]+)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(markdown);
        
        if (matcher.find()) {
            String url = matcher.group(1);
            logger.debug("🖼️ 提取到图片 URL: {}", url.substring(0, Math.min(50, url.length())) + "...");
            return url;
        }
        
        logger.debug("⚠️ 未找到图片 URL");
        return null;
    }

    /**
     * 将 Markdown 格式转换为纯文本（优化版 - 去重）
     * @param markdown Markdown 内容
     * @return 纯文本
     */
    private String convertMarkdownToPlainText(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        String text = markdown;
        
        // 1. 移除 HTML 实体编码（优先处理）
        text = text.replaceAll("&gt;", ">");
        text = text.replaceAll("&lt;", "<");
        text = text.replaceAll("&amp;", "&");
        text = text.replaceAll("&quot;", "\"");
        text = text.replaceAll("&#39;", "'");
        
        // 2. 移除 Markdown 链接: [text](url) -> text
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        
        // 3. 移除图片标记: ![img ...](url) -> （稍后用 CQ 码发送）
        text = text.replaceAll("!\\[img[^\\]]*\\]\\([^)]+\\)", "");
        
        // 4. 移除加粗/斜体标记: **text** -> text
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        text = text.replaceAll("\\*([^*]+)\\*", "$1");
        
        // 5. 移除标题标记: ### text -> text
        text = text.replaceAll("^#{1,6}\\s+", "");
        
        // 6. 处理引用标记: > text -> ✨ text（逐行处理）
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(">")) {
                sb.append("✨ ").append(trimmed.substring(1).trim()).append("\n");
            } else if (!trimmed.isEmpty()) {
                sb.append(line).append("\n");
            }
        }
        text = sb.toString();
        
        // 7. 清理分隔线: --- 或 ===
        text = text.replaceAll("^[\\-=]{3,}$", "─────────────");
        text = text.replaceAll("^={3,}$", "═════════════");
        
        // 8. 去重：移除完全相同的行
        String[] cleanLines = text.split("\n");
        Set<String> seenLines = new LinkedHashSet<>();
        sb = new StringBuilder();
        for (String line : cleanLines) {
            String trimmed = line.trim();
            // 跳过空行和重复行
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!seenLines.contains(trimmed)) {
                seenLines.add(trimmed);
                sb.append(trimmed).append("\n");
            }
        }
        text = sb.toString();
        
        // 9. 清理多余的空行（保留最多1个空行）
        text = text.replaceAll("\n{3,}", "\n\n");
        
        // 10. 移除开头的空行
        text = text.trim();
        
        logger.debug("📝 转换后的文本行数: {}", text.split("\n").length);
        
        return text;
    }

    /**
     * 发送超时消息
     */
    private void sendTimeoutMessage(Main bot, long groupId, long userId) {
        String msg = "⏰ 查询超时，请稍后重试";
        if (groupId != 0) {
            bot.sendGroupReply(groupId, msg);
        } else {
            bot.sendPrivateReply(userId, msg);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Main bot, long groupId, long userId, String error) {
        String msg = "❌ 查询失败: " + error;
        if (groupId != 0) {
            bot.sendGroupReply(groupId, msg);
        } else {
            bot.sendPrivateReply(userId, msg);
        }
    }

    /**
     * 供 Tool 调用的同步查询接口。
     * 发送查询消息到目标群，返回 CompletableFuture（tool 会阻塞等待结果）。
     */
    public CompletableFuture<String> queryMerchantSync(Main bot) {
        String key = "tool_" + System.currentTimeMillis();
        PendingQuery query = new PendingQuery(0, 0);
        pendingQueries.put(key, query);

        String queryMessage = "[CQ:at,qq=" + TARGET_QQ + "] 远行商人";
        bot.sendGroupReply(TARGET_GROUP_ID, queryMessage);
        logger.info("📤 [Tool] 已发送远行商人查询消息到群 {}", TARGET_GROUP_ID);

        return query.future
                .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(responseJson -> {
                    pendingQueries.remove(key);
                    String text = extractText(responseJson);
                    if (text == null || text.isEmpty()) return "🏪 远行商人暂无信息。";
                    return "🏪 远行商人信息\n━━━━━━━━━━━━━━━\n" + convertMarkdownToPlainText(text);
                })
                .exceptionally(throwable -> {
                    pendingQueries.remove(key);
                    return "⏰ 远行商人查询超时，请稍后重试（直接发「远行商人」也可查询）。";
                });
    }

    /**
     * 处理来自目标群的响应消息
     * @param message 收到的消息
     * @return 是否成功匹配到待处理的查询
     */
    public boolean handleResponse(JsonNode message) {
        long groupId = extractGroupId(message);

        // 只处理目标群的回复
        if (groupId != TARGET_GROUP_ID) {
            return false;
        }

        // 检查是否来自目标QQ
        long fromUserId = extractUserId(message);
        if (fromUserId != TARGET_QQ) {
            logger.debug("⚠️ 消息来自 {}，不是目标 QQ {}", fromUserId, TARGET_QQ);
            return false;
        }

        String text = extractText(message);
        if (text == null || text.isEmpty()) {
            logger.debug("⚠️ 提取到的文本为空");
            return false;
        }

        // 清理过期的查询
        cleanupExpiredQueries();

        // 调试日志
        logger.debug("🔍 当前待处理查询数量: {}", pendingQueries.size());

        // 找到最早的待处理查询并完成任务（按时间戳排序）
        PendingQuery earliestQuery = null;
        String earliestKey = null;

        for (ConcurrentHashMap.Entry<String, PendingQuery> entry : pendingQueries.entrySet()) {
            PendingQuery query = entry.getValue();
            if (!query.future.isDone() && !query.isExpired(TIMEOUT_SECONDS)) {
                if (earliestQuery == null || query.timestamp < earliestQuery.timestamp) {
                    earliestQuery = query;
                    earliestKey = entry.getKey();
                }
            }
        }

        if (earliestQuery != null) {
            earliestQuery.future.complete(message);  // 传递完整的 JSON
            long elapsed = System.currentTimeMillis() - earliestQuery.timestamp;
            logger.info("✅ 收到商人信息响应，耗时: {}ms", elapsed);
            return true;
        }

        logger.debug("⚠️ 收到响应但没有待处理的查询");
        return false;
    }

    /**
     * 清理过期的查询
     */
    private void cleanupExpiredQueries() {
        pendingQueries.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired(TIMEOUT_SECONDS)) {
                if (!entry.getValue().future.isDone()) {
                    entry.getValue().future.cancel(true);
                }
                logger.debug("🗑️ 清理过期查询: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // 辅助方法：提取文本（只提取纯文本部分）
    private String extractText(JsonNode message) {
        try {
            if (message.has("message")) {
                JsonNode messageArray = message.get("message");
                if (messageArray.isArray()) {
                    StringBuilder sb = new StringBuilder();

                    for (JsonNode node : messageArray) {
                        String type = node.path("type").asText();

                        // 只提取 text 类型的内容
                        if ("text".equals(type)) {
                            if (node.has("data") && node.get("data").has("text")) {
                                String text = node.get("data").get("text").asText();
                                if (!text.trim().isEmpty()) {
                                    sb.append(text).append("\n");
                                }
                            } else if (node.has("text")) {
                                String text = node.get("text").asText();
                                if (!text.trim().isEmpty()) {
                                    sb.append(text).append("\n");
                                }
                            }
                        }
                        // 兼容旧格式 "Plain"
                        else if ("Plain".equals(type)) {
                            String text = node.get("text").asText();
                            if (!text.trim().isEmpty()) {
                                sb.append(text).append("\n");
                            }
                        }
                    }

                    String result = sb.toString().trim();
                    return result.isEmpty() ? null : result;
                }
            }

            // 兼容 messageChain 格式
            if (message.has("messageChain")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode node : message.get("messageChain")) {
                    if ("Plain".equals(node.get("type").asText())) {
                        sb.append(node.get("text").asText()).append("\n");
                    }
                }
                String result = sb.toString().trim();
                return result.isEmpty() ? null : result;
            }
        } catch (Exception e) {
            logger.error("❌ 提取消息文本失败", e);
        }
        return null;
    }

    // 辅助方法：提取群号
    private long extractGroupId(JsonNode message) {
        try {
            if (message.has("group_id")) {
                return message.get("group_id").asLong();
            }
            if (message.has("sender") && message.get("sender").has("group_id")) {
                return message.get("sender").get("group_id").asLong();
            }
        } catch (Exception e) {
            logger.error("❌ 提取群号失败", e);
        }
        return 0;
    }

    // 辅助方法：提取用户ID
    private long extractUserId(JsonNode message) {
        try {
            if (message.has("user_id")) {
                return message.get("user_id").asLong();
            }
            if (message.has("sender") && message.get("sender").has("user_id")) {
                return message.get("sender").get("user_id").asLong();
            }
        } catch (Exception e) {
            logger.error("❌ 提取用户ID失败", e);
        }
        return 0;
    }

    // 辅助方法：提取消息ID
    private String extractMessageId(JsonNode message) {
        try {
            if (message.has("message_id")) {
                return message.get("message_id").asText();
            }
            return "msg_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        } catch (Exception e) {
            return "msg_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        }
    }
}
