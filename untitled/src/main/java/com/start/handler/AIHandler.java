package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.service.GroupSerialExecutor;
import com.start.service.LinkPreviewService;
import com.start.util.MessageUtil;
import com.start.vision.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static com.start.util.MessageUtil.extractAts;

/**
 * AIHandler  ai模块入口
 */
public class AIHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AIHandler.class);
    private static final long MAX_QUEUE_MS = 30_000; // 排队超过30秒则丢弃

    private final BaiLianService aiService;
    private final GroupSerialExecutor groupExecutor;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Long> lastReactionTime = new ConcurrentHashMap<>();
    private static final long USER_REACTION_COOLDOWN_MS = 2000;
    private final ConcurrentHashMap<String, Long> lastGroupReplyTime = new ConcurrentHashMap<>();
    private static final long GROUP_REPLY_COOLDOWN_MS = 12_000;

    public AIHandler(BaiLianService aiService, GroupSerialExecutor groupExecutor) {
        this.aiService = aiService;
        this.groupExecutor = groupExecutor;
    }

    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        if ("private".equals(messageType)) {
            String raw = msg.path("raw_message").asText().trim();
            if (raw.isEmpty()) return false;
            if (raw.startsWith("!") &&
                    !raw.startsWith("!ai ") &&
                    !raw.startsWith("！ai ") &&
                    !raw.startsWith("#ai ")) {
                return false;
            }
            return true;
        } else if ("group".equals(messageType)) {
            return true;
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long selfId = msg.path("self_id").asLong();
        long userId = msg.path("user_id").asLong();
        String messageType = msg.path("message_type").asText();
        long groupId = msg.path("group_id").asLong();
        JsonNode messageArray = msg.path("message");
        List<Long> ats = extractAts(messageArray);
        String nickname = msg.path("sender").path("nickname").asText();
        if (userId == selfId) return;

        String plainText = MessageUtil.extractPlainText(msg.path("message")).trim();
        String rawMessage = msg.path("raw_message").asText();
        String senderNick = msg.path("sender").path("card").asText();
        if (senderNick.isEmpty()) {
            senderNick = msg.path("sender").path("nickname").asText();
        }

        // 提取图片信息（只在 WebSocket 线程提取 URL，下载在 executor 内完成）
        List<Map<String, String>> imageInfos = MessageUtil.extractImages(msg.path("message"));

        // 提取链接（分享卡片 + 纯文本 URL）
        List<String> linksToFetch = new ArrayList<>();
        for (Map<String, String> share : MessageUtil.extractShares(msg.path("message"))) {
            String url = share.get("url");
            if (url != null && !url.isEmpty()) {
                linksToFetch.add(url);
            }
        }
        linksToFetch.addAll(MessageUtil.extractUrls(rawMessage));

        // 私聊
        if ("private".equals(messageType)) {
            handlePrivateMessage(bot, msg, userId, rawMessage, plainText, nickname, imageInfos, linksToFetch);
            return;
        }

        // 群聊：先记录原始消息到上下文（WebSocket 线程，无竞争）
        aiService.recordPublicGroupMessage(
                String.valueOf(groupId),
                String.valueOf(userId),
                senderNick,
                plainText
        );

        String gid = String.valueOf(groupId);

        // 明确触发（#ai / !ai / @）
        if (isExplicitTrigger(msg, rawMessage)) {
            aiService.cancelPendingAwait(gid, String.valueOf(userId));
            handleExplicitAIRequest(bot, msg, userId, groupId, rawMessage, plainText, nickname, imageInfos, linksToFetch);
            return;
        }

        // 主动插话判断（WebSocket 线程，无竞争）
        Optional<BaiLianService.Reaction> reaction = aiService.shouldReactToGroupMessage(
                gid,
                String.valueOf(userId),
                senderNick,
                plainText,
                ats
        );

        // 纯图片消息的追问处理（移动端QQ无法同时发送文字+图片，用户可能在"发图吧"之后单独发图）
        boolean imageFollowUp = !imageInfos.isEmpty() && reaction.isEmpty()
                && aiService.isWithinFollowUpWindow(gid, String.valueOf(userId));

        if (reaction.isPresent() || imageFollowUp) {
            long now = System.currentTimeMillis();

            // 群级冷却：上次回复后12秒内不触发新回复，避免频繁切人导致语言碎片化
            Long lastGroupReply = lastGroupReplyTime.get(gid);
            if (lastGroupReply != null && now - lastGroupReply < GROUP_REPLY_COOLDOWN_MS) {
                return;
            }

            // 同一用户2秒内冷却，避免连续短消息触发多次回复
            String userKey = gid + "_" + userId;
            Long last = lastReactionTime.get(userKey);
            if (last != null && now - last < USER_REACTION_COOLDOWN_MS) {
                return;
            }
            lastReactionTime.put(userKey, now);
            lastGroupReplyTime.put(gid, now);

            boolean needsAI = imageFollowUp || reaction.map(r -> r.needsAI).orElse(false);
            String prompt = imageFollowUp ? "看一下这张图片" : reaction.get().prompt;
            if (needsAI) {
                groupExecutor.execute(gid, () -> {
                    List<String> imageDataUris = downloadImages(imageInfos);
                    String imageDesc = aiService.describeImages(imageDataUris);
                    String linkContext = buildLinkContext(linksToFetch);
                    String fullPrompt = prompt;
                    if (!imageDesc.isEmpty()) fullPrompt = fullPrompt + "\n\n" + imageDesc;
                    if (!linkContext.isEmpty()) fullPrompt = fullPrompt + "\n\n" + linkContext;
                    String reply = aiService.generate("group_" + groupId + "_" + userId, String.valueOf(userId), fullPrompt, gid, String.valueOf(nickname), ats);
                    if (!reply.trim().isEmpty() && !reply.equals("抱歉，刚才走神了...") && !reply.equals("嗯...再问一次吧")) {
                        sendSplitGroupReplies(bot, groupId, reply);
                        aiService.recordUserInteraction(gid, String.valueOf(userId), reply);
                        aiService.recordGroupContext(gid, String.valueOf(userId), "糖果熊", reply, "ai_reply");
                    } else {
                        bot.sendGroupReply(groupId, "刚刚走神了，再说一遍？");
                    }
                });
            } else {
                sendSplitGroupReplies(bot, groupId, reaction.get().text);
            }
        }
    }

    private String buildReplyContext(JsonNode msg, Main bot) {
        Long replyId = MessageUtil.extractReplyId(msg.path("message"));
        if (replyId == null) return "";
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyId);
            var future = bot.callOneBotApi("get_msg", params);
            var resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && resp.has("data")) {
                String repliedText = resp.path("data").path("raw_message").asText();
                if (!repliedText.isEmpty()) {
                    return "（对方正在回复这条消息：\"" + repliedText + "\"）";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void handlePrivateMessage(Main bot, JsonNode msg, long userId, String rawMessage, String plainText, String nickname, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        String prompt = buildReplyContext(msg, bot) + extractPrompt(rawMessage, plainText);
        String sessionId = "private_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty() && imageInfos.isEmpty()) {
            bot.sendReply(msg, "想聊什么？直接说就好～");
            return;
        }
        if (prompt.isEmpty()) prompt = "看一下这张图片";

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, null, nickname, Collections.emptyList(), imageInfos, linksToFetch);
    }

    private void handleExplicitAIRequest(Main bot, JsonNode msg, long userId, long groupId, String rawMessage, String plainText, String nickname, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        String replyCtx = buildReplyContext(msg, bot);
        String prompt = replyCtx.isEmpty() ? extractPrompt(rawMessage, plainText) : replyCtx + extractPrompt(rawMessage, plainText);
        String sessionId = "group_" + groupId + "_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty() && imageInfos.isEmpty()) {
            bot.sendReply(msg, "问点什么吧～");
            return;
        }
        if (prompt.isEmpty()) prompt = "看一下这张图片";

        List<Long> ats = MessageUtil.extractAts(msg.path("message"));
        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, String.valueOf(groupId), nickname, ats, imageInfos, linksToFetch);
    }

    private boolean isExplicitTrigger(JsonNode msg, String rawMessage) {
        return rawMessage.startsWith("#ai ") ||
                rawMessage.startsWith("!ai ") ||
                rawMessage.startsWith("！ai ") ||
                MessageUtil.isAt(msg.path("message"), BotConfig.getBotQq());
    }

    private String extractPrompt(String rawMessage, String plainText) {
        if (rawMessage.startsWith("#ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("!ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("！ai ")) return rawMessage.substring(5).trim();
        return plainText;
    }

    private boolean isClearCommand(String prompt) {
        return "#clear".equals(prompt) || "!clear".equals(prompt) || "！clear".equals(prompt);
    }


    private void replyWithAI(Main bot, JsonNode originalMsg, String sessionId, String userId, String prompt, String groupId, String nickname, List<Long> atUserIds, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        groupExecutor.execute(groupId, () -> {
            List<String> imageDataUris = downloadImages(imageInfos);
            String imageDesc = aiService.describeImages(imageDataUris);
            String linkContext = buildLinkContext(linksToFetch);
            String fullPrompt = prompt;
            if (!imageDesc.isEmpty()) fullPrompt = fullPrompt + "\n\n" + imageDesc;
            if (!linkContext.isEmpty()) fullPrompt = fullPrompt + "\n\n" + linkContext;

            // 存储图片数据到 DB
            if (!imageDesc.isEmpty()) {
                aiService.setPendingImageData(buildImageDataJson(imageInfos, imageDesc));
            }
            String reply = aiService.generate(sessionId, userId, fullPrompt, groupId, nickname, atUserIds);

            if (reply == null || reply.trim().isEmpty()) {
                bot.sendReply(originalMsg, "稍等一下，我在走神...");
                return;
            }

            if (groupId != null) {
                long gId = Long.parseLong(groupId);
                sendSplitGroupReplies(bot, gId, reply);

                String senderNick = originalMsg.path("sender").path("card").asText();
                if (senderNick.isEmpty()) senderNick = originalMsg.path("sender").path("nickname").asText();
                aiService.recordUserInteraction(groupId, userId, reply);
                aiService.recordGroupContext(groupId, userId, senderNick, reply, "ai_reply");
            } else {
                sendSplitPrivateReplies(bot, originalMsg, reply);
            }
        });
    }

    /**
     * 将 AI 回复拆分为多条短消息，并逐条发送（带打字延迟）
     */
    private void sendSplitGroupReplies(Main bot, long groupId, String fullReply) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendGroupReply(groupId, msg);
        }
    }

    /** 获取链接预览上下文，失败则返回空 */
    private String buildLinkContext(List<String> linksToFetch) {
        if (linksToFetch == null || linksToFetch.isEmpty()) return "";
        LinkPreviewService lps = new LinkPreviewService();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(linksToFetch.size(), 3);
        for (int i = 0; i < limit; i++) {
            String preview = lps.fetchPreview(linksToFetch.get(i));
            if (preview != null && !preview.isEmpty()) {
                sb.append(preview).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 下载图片并转为 base64 data URI，失败则跳过 */
    private List<String> downloadImages(List<Map<String, String>> imageInfos) {
        List<String> uris = new ArrayList<>();
        if (imageInfos == null || imageInfos.isEmpty()) return uris;
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            String url = imageInfos.get(i).get("url");
            if (url == null || url.isEmpty()) continue;
            String dataUri = ImageUtils.downloadImageAsBase64DataUri(url);
            if (dataUri != null) uris.add(dataUri);
        }
        return uris;
    }

    private String buildImageDataJson(List<Map<String, String>> imageInfos, String imageDesc) {
        if (imageInfos == null || imageInfos.isEmpty() || imageDesc.isEmpty()) return null;
        // 解析 vision 描述中的每条 "图片N内容：xxx"，与 imageInfos 的 URL 配对
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            String url = imageInfos.get(i).get("url");
            if (url == null) url = "";
            // 从 imageDesc 提取对应描述
            String prefix = "图片" + (i + 1) + "内容：";
            int idx = imageDesc.indexOf(prefix);
            String desc = "";
            if (idx >= 0) {
                int start = idx + prefix.length();
                int end = imageDesc.indexOf("\n", start);
                if (end < 0) end = imageDesc.length();
                desc = imageDesc.substring(start, end).trim().replace("\\", "\\\\").replace("\"", "\\\"");
            }
            if (i > 0) sb.append(",");
            sb.append("{\"url\":\"").append(url.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
              .append(",\"desc\":\"").append(desc).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 私聊同样拆分，避免一大段砸过去 */
    private void sendSplitPrivateReplies(Main bot, JsonNode originalMsg, String fullReply) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendReply(originalMsg, msg);
        }
    }

}
