package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.service.JokeService;
import com.start.util.RateLimiter;
import com.start.Main; // 确保导入 Main

/**
 * 笑话处理类
 */
public class JokeHandler implements MessageHandler {

    @Override
    public boolean match(JsonNode message) {
        String rawMessage = message.path("raw_message").asText();
        return "讲个笑话".equals(rawMessage) ||
                "来个笑话".equals(rawMessage) ||
                "/joke".equals(rawMessage);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        long userId = message.path("user_id").asLong();
        long groupId = message.path("group_id").asLong(); // 群聊时有值，私聊为0
        boolean isGroup = groupId != 0;


        // 📦 获取笑话
        String joke = JokeService.fetchRandomJoke();

        // 📤 发送
        if (isGroup) {
            bot.sendGroupReply(groupId, joke);
        } else {
            bot.sendPrivateReply(userId, joke);
        }
    }
}