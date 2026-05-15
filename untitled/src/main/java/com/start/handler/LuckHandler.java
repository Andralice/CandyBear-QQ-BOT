package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.util.MessageUtil;
import com.start.util.LuckUtil;
import com.start.util.RateLimiter;

/**
 * 幸运值
 */
public class LuckHandler implements MessageHandler {
    // 30秒内同一用户不能重复触发
    private static final RateLimiter rateLimiter = new RateLimiter(5);

    @Override
    public boolean match(JsonNode msg) {
        long botQq = BotConfig.getBotQq();
        String botName = BotConfig.getBotName();
        String plainText = MessageUtil.extractPlainText(msg.path("message"));

        if (plainText == null) {
            plainText = "";
        }
        plainText = plainText.trim();

        boolean isAtMe = MessageUtil.isAt(msg.path("message"), botQq);
        boolean mentionsBot = botName != null && plainText.contains(botName);
        boolean containsKeyword = plainText.contains("幸运值") || plainText.contains("运势");
        boolean isExactKeyword = "幸运值".equals(plainText) || "运势".equals(plainText);

        // 原逻辑：被@或提名字 + 含关键词
        boolean originalCondition = (isAtMe || mentionsBot) && containsKeyword;

        // 新增逻辑：只要整条消息就是关键词，就触发（无需@或提名字）
        boolean exactKeywordOnly = isExactKeyword;

        return originalCondition || exactKeywordOnly;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long userId = msg.path("user_id").asLong();
        long groupId = msg.path("group_id").asLong(); // 群聊才有，私聊可忽略

        // 构建唯一 key：群+用户（如果是群消息），否则只用用户
        String cacheKey;
        if (msg.has("group_id")) {
            cacheKey = "luck:" + groupId + ":" + userId;
        } else {
            cacheKey = "luck:private:" + userId;
        }

        // 防刷：30秒内不重复响应
        if (!rateLimiter.tryAcquire(cacheKey)) {
            // 可选：悄悄忽略，或回复“别急，稍后再试”
            return;
        }

        int luck = LuckUtil.getDailyLuck(userId);
        String Atthis =BotConfig.getAt(userId);
        String reply;
        if (luck >= 90) {
            reply =Atthis +"🌟 欧气爆棚！您今天的幸运值是 " + luck + "！";
        } else if (luck >= 70) {
            reply =Atthis + "😊 运气不错哦～您今天的幸运值是 " + luck + "！";
        } else if (luck >= 40) {
            reply =Atthis +"🙂 平平无奇的一天，幸运值：" + luck + "。";
        } else {
            reply =Atthis + "😞 今天小心点...您的幸运值只有 " + luck + "。";
        }

        bot.sendReply(msg, reply);
    }
}