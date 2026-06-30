package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.service.ConversationManager;
import com.start.service.ConversationState;
import com.start.service.GroupSerialExecutor;
import com.start.service.LinkPreviewService;
import com.start.util.MessageUtil;
import com.start.vision.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.start.util.MessageUtil.extractAts;

/**
 * AIHandler  ai模块入口
 */
public class AIHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AIHandler.class);
    private static final long MAX_QUEUE_MS = 30_000; // 排队超过30秒则丢弃

    private final BaiLianService aiService;
    private final GroupSerialExecutor groupExecutor;
    private final ConversationManager conversationManager;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Long> lastReactionTime = new ConcurrentHashMap<>();
    private static final long USER_REACTION_COOLDOWN_MS = 2000;
    private final ConcurrentHashMap<String, Long> lastGroupReplyTime = new ConcurrentHashMap<>();
    private static final long GROUP_REPLY_COOLDOWN_MS = 12_000;

    public AIHandler(BaiLianService aiService, GroupSerialExecutor groupExecutor, ConversationManager conversationManager) {
        this.aiService = aiService;
        this.groupExecutor = groupExecutor;
        this.conversationManager = conversationManager;
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

        // 提取文件信息 → 存入缓存，由副 AI 处理，主 AI 通过 query_file 工具主动获取
        List<Map<String, String>> fileInfos = MessageUtil.extractFiles(msg.path("message"));
        if (!fileInfos.isEmpty()) {
            String fileKey = "group".equals(messageType)
                    ? "group_" + groupId + "_" + userId
                    : "private_" + userId;
            aiService.addPendingFiles(fileKey, fileInfos);
        }

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
        String uid = String.valueOf(userId);

        // 缓冲消息到 ConversationState（WebSocket 线程）
        ConversationState conv = conversationManager.getOrCreate(gid, uid);
        conv.addMessage(plainText);
        if (!imageInfos.isEmpty()) {
            for (Map<String, String> img : imageInfos) {
                conv.addImageInfo(img.get("url"), img.get("file"));
            }
        }
        for (String link : linksToFetch) {
            conv.addLink(link);
        }
        Long replyId = MessageUtil.extractReplyId(msg.path("message"));
        if (replyId != null) conv.setReplyToMessageId(replyId);
        conv.incrementRevision();

        // 明确触发（#ai / !ai / @）
        if (isExplicitTrigger(msg, rawMessage)) {
            aiService.cancelPendingAwait(gid, uid);
            String strippedPrompt = extractPrompt(rawMessage, plainText);
            if (isClearCommand(strippedPrompt)) {
                aiService.clearContext("group_" + groupId + "_" + uid);
                bot.sendReply(msg, "已清除我们的聊天记忆！");
                conversationManager.remove(gid, uid);
                return;
            }
            if (strippedPrompt.isEmpty() && imageInfos.isEmpty()) {
                bot.sendReply(msg, "问点什么吧～");
                conversationManager.remove(gid, uid);
                return;
            }
            runGroupConversation(bot, groupId, gid, uid, nickname, ats);
            return;
        }

        // 主动插话判断（WebSocket 线程，无竞争）
        Optional<BaiLianService.Reaction> reaction = aiService.shouldReactToGroupMessage(
                gid,
                uid,
                senderNick,
                plainText,
                ats
        );

        // 纯图片消息的追问处理（移动端QQ无法同时发送文字+图片，用户可能在"发图吧"之后单独发图）
        boolean imageFollowUp = !imageInfos.isEmpty() && reaction.isEmpty()
                && aiService.isWithinFollowUpWindow(gid, uid);

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
            if (needsAI) {
                runGroupConversation(bot, groupId, gid, uid, nickname, ats);
            } else {
                sendSplitGroupReplies(bot, groupId, reaction.get().text);
                conversationManager.remove(gid, uid);
            }
        }
    }

    private static final int MAX_REGENERATE = 2;
    private static final HttpClient auditHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    /** 从 ConversationState 读取累积消息，生成回复并发送。在 executor 线程中执行。 */
    private void runGroupConversation(Main bot, long groupId, String gid, String userId, String nickname, List<Long> ats) {
        groupExecutor.execute(gid, () -> {
            ConversationState state = conversationManager.get(gid, userId);
            if (state == null || !state.hasContent()) return;

            long startRevision = state.getMessageRevision();
            int startMsgCount = state.getPendingMessages().size();
            state.incrementGeneration();
            String reply = null;

            // 图片 + 链接上下文在一次对话中不变，提到循环外避免重复下载/描述
            String replyContext = "";
            Long replyToMsgId = state.getReplyToMessageId();
            if (replyToMsgId != null) {
                replyContext = fetchReplyContext(replyToMsgId, bot);
            }
            List<Map<String, String>> imageInfoMaps = new ArrayList<>();
            for (ConversationState.ImageInfo img : state.getImageInfos()) {
                Map<String, String> m = new HashMap<>();
                m.put("url", img.url());
                m.put("file", img.file());
                imageInfoMaps.add(m);
            }
            List<String> imageDataUris = downloadImages(imageInfoMaps);
            String imageDesc = aiService.describeImages(imageDataUris);
            String linkContext = buildLinkContext(state.getLinksToFetch());

            for (int attempt = 0; attempt <= MAX_REGENERATE; attempt++) {
                if (attempt > 0) {
                    startRevision = state.getMessageRevision();
                    startMsgCount = state.getPendingMessages().size();
                    state.incrementGeneration();
                    state.incrementRegenerateCount();
                    logger.debug("Conversation regenerate gen={} rev={} count={} gid={} uid={}",
                            state.getGeneration(), state.getMessageRevision(), state.getRegenerateCount(), gid, userId);
                }

                // 从 buffer 读取累积的消息文本（每次循环可能变化）
                String mergedText = state.getMergedText();
                String prompt = replyContext.isEmpty() ? mergedText : replyContext + mergedText;
                if (prompt.isEmpty() && !state.getImageInfos().isEmpty()) {
                    prompt = "看一下这张图片";
                }
                if (!imageDesc.isEmpty()) prompt = prompt + "\n\n" + imageDesc;
                if (!linkContext.isEmpty()) prompt = prompt + "\n\n" + linkContext;

                String sessionId = "group_" + groupId + "_" + userId;
                aiService.setSuppressSessionWrite(true);
                try {
                    reply = aiService.generate(sessionId, userId, prompt, gid, nickname, ats);
                } finally {
                    aiService.setSuppressSessionWrite(false);
                }

                // 检查 LLM 调用期间是否有新消息到达
                if (state.getMessageRevision() == startRevision) {
                    break; // 无变化，回复可用
                }
                if (attempt >= MAX_REGENERATE) {
                    logger.debug("Max regenerate reached, sending anyway gid={} uid={}", gid, userId);
                    break;
                }

                // 有新消息 → audit 模型判断是否合并再生
                String oldText = getMergedTextRange(state.getPendingMessages(), 0, startMsgCount);
                String newText = getMergedTextRange(state.getPendingMessages(), startMsgCount, state.getPendingMessages().size());
                String auditResult = classifyMessageRelation(oldText, newText, reply);
                logger.debug("Audit classify: {} old=[{}] new=[{}] gid={} uid={}", auditResult, oldText, newText, gid, userId);

                if ("C".equals(auditResult)) {
                    continue; // 补充/修正 → 合并再生
                }
                // S（重复）或 N（独立话题）→ 发送原回复
                break;
            }

            if (reply != null && !reply.trim().isEmpty() && !reply.equals("抱歉，刚才走神了...") && !reply.equals("嗯...再问一次吧")) {
                sendSplitGroupReplies(bot, groupId, reply);
                aiService.commitGeneration("group_" + groupId + "_" + userId, userId,
                        state.getMergedText(), reply, gid);
            } else {
                bot.sendGroupReply(groupId, "刚刚走神了，再说一遍？");
            }

            conversationManager.remove(gid, userId);
        });
    }

    private static String getMergedTextRange(List<ConversationState.MessageEntry> msgs, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(msgs.size(), end);
        if (from >= to) return "";
        return msgs.subList(from, to).stream()
                .map(ConversationState.MessageEntry::text)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /** 调用 audit 模型（便宜）判断后半部分消息的性质。返回 S / C / N */
    private String classifyMessageRelation(String oldText, String newText, String generatedReply) {
        if (newText.isEmpty()) return "S";
        try {
            String prompt = "用户连续发了消息，AI已为前半部分生成了回复，但后半部分在回复生成后才到达。\n"
                    + "\n【前半部分消息】\n" + (oldText.isEmpty() ? "（空）" : oldText)
                    + "\n\n【后半部分新增消息】\n" + newText
                    + "\n\n【AI已生成的回复】\n" + generatedReply
                    + "\n\n请判断后半部分消息的性质，只回复一个大写字母：\n"
                    + "S - 后半部分是前半部分的重复/同义/废话，不需要额外回复\n"
                    + "C - 后半部分是前半部分的补充/修正/延续，应该合并后重新生成回复\n"
                    + "N - 后半部分是新的独立话题，与前半部分无关\n\n只回复一个字母：";

            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("model", BotConfig.getAuditModel());
            ArrayNode msgs = body.putArray("messages");
            ObjectNode msg = msgs.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            body.put("max_tokens", 10);
            body.put("temperature", 0.0);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BotConfig.getAuditBaseUrl()))
                    .header("Authorization", "Bearer " + BotConfig.getAuditApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(BotConfig.getAuditTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = auditHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                String content = json.path("choices").get(0).path("message").path("content").asText("S").trim();
                if (content.startsWith("S")) return "S";
                if (content.startsWith("C")) return "C";
                if (content.startsWith("N")) return "N";
                return "S"; // 默认不再生
            }
            logger.warn("Audit classify HTTP error: {}", resp.statusCode());
        } catch (Exception e) {
            logger.warn("Audit classify failed: {}", e.getMessage());
        }
        return "S"; // 出错时保守处理：不重新生成
    }

    /** 获取引用消息的文本上下文 */
    private String fetchReplyContext(Long replyMsgId, Main bot) {
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyMsgId);
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
