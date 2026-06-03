package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.service.GroupSerialExecutor;
import com.start.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.start.util.MessageUtil.extractAts;

/**
 * AIHandler  ai模块入口
 */
public class AIHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AIHandler.class);
    private static final long MAX_QUEUE_MS = 30_000; // 排队超过30秒则丢弃

    private final BaiLianService aiService;
    private final GroupSerialExecutor groupExecutor;
    private final Random random = new Random();

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

        // 私聊
        if ("private".equals(messageType)) {
            handlePrivateMessage(bot, msg, userId, rawMessage, plainText, nickname);
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
            handleExplicitAIRequest(bot, msg, userId, groupId, rawMessage, plainText, nickname);
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

        if (reaction.isPresent()) {
            BaiLianService.Reaction r = reaction.get();
            if (r.needsAI) {
                groupExecutor.execute(gid, () -> {
                    String reply = aiService.generate("group_" + groupId + "_" + userId, String.valueOf(userId), r.prompt, gid, String.valueOf(nickname), ats);
                    if (!reply.trim().isEmpty() && !reply.equals("抱歉，刚才走神了...") && !reply.equals("嗯...再问一次吧")) {
                        sendSplitGroupReplies(bot, groupId, reply);
                        aiService.recordUserInteraction(gid, String.valueOf(userId), reply);
                        aiService.recordGroupContext(gid, String.valueOf(userId), "糖果熊", reply, "ai_reply");
                    } else {
                        bot.sendGroupReply(groupId, "刚刚走神了，再说一遍？");
                    }
                });
            } else {
                sendSplitGroupReplies(bot, groupId, r.text);
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

    private void handlePrivateMessage(Main bot, JsonNode msg, long userId, String rawMessage, String plainText, String nickname) {
        String prompt = buildReplyContext(msg, bot) + extractPrompt(rawMessage, plainText);
        String sessionId = "private_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty()) {
            bot.sendReply(msg, "想聊什么？直接说就好～");
            return;
        }

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, null, nickname, Collections.emptyList());
    }

    private void handleExplicitAIRequest(Main bot, JsonNode msg, long userId, long groupId, String rawMessage, String plainText, String nickname) {
        String replyCtx = buildReplyContext(msg, bot);
        String prompt = replyCtx.isEmpty() ? extractPrompt(rawMessage, plainText) : replyCtx + extractPrompt(rawMessage, plainText);
        String sessionId = "group_" + groupId + "_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty()) {
            bot.sendReply(msg, "问点什么吧～");
            return;
        }

        List<Long> ats = MessageUtil.extractAts(msg.path("message"));
        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, String.valueOf(groupId), nickname, ats);
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

    private void replyWithAI(Main bot, JsonNode originalMsg, String sessionId, String userId, String prompt, String groupId, String nickname, List<Long> atUserIds) {
        groupExecutor.execute(groupId, () -> {
            String reply = aiService.generate(sessionId, userId, prompt, groupId, nickname, atUserIds);

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
                bot.sendReply(originalMsg, reply);
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

}
