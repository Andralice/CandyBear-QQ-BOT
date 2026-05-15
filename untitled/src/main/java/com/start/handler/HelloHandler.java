package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.util.MessageUtil;


/**
 * 示例：处理「你好」
 */
public class HelloHandler implements MessageHandler {
    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        if ("private".equals(messageType)) {
            String text = MessageUtil.extractPlainText(msg.path("message"));
            // 私聊忽略@，必须严格等于"你好"（去除首尾空白后）
            return "你好".equals(text.trim());
        } else if ("group".equals(messageType)) {
            String text = MessageUtil.extractPlainText(msg.path("message"));
            long botQq = BotConfig.getBotQq();
            // 群聊必须同时满足：1. 被 @；2. 文本严格等于"你好"（去除首尾空白后）
            return MessageUtil.isAt(msg.path("message"), botQq) &&
                    "你好".equals(text.trim());
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        bot.sendReply(msg, "你好！我是糖果熊~");
    }
}