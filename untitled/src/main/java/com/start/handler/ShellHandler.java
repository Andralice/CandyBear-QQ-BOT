package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.ServerAdminService;
import com.start.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 处理运维命令（> 前缀），仅管理员可用，直接执行不走 AI。
 * 用法：> ps aux | grep java
 * 确认：> 确认执行 12345
 */
public class ShellHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(ShellHandler.class);
    private static final Pattern CONFIRM_PATTERN = Pattern.compile("确认执行\\s+(\\d+)");

    private final ServerAdminService shellService;

    public ShellHandler(ServerAdminService shellService) {
        this.shellService = shellService;
    }

    public ShellHandler() {
        this.shellService = new ServerAdminService();
    }

    @Override
    public boolean match(JsonNode msg) {
        // 只在私聊中响应，群聊不走运维命令
        String messageType = msg.path("message_type").asText();
        if (!"private".equals(messageType)) return false;

        long userId = msg.path("user_id").asLong();
        if (userId != BotConfig.getAdminQq()) return false;

        String text = MessageUtil.extractPlainText(msg.path("message"));
        if (text == null) return false;

        text = text.trim();
        return text.startsWith(">") || CONFIRM_PATTERN.matcher(text).matches();
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long userId = msg.path("user_id").asLong();
        String text = MessageUtil.extractPlainText(msg.path("message")).trim();

        // 检查是否是确认执行
        Matcher confirmMatcher = CONFIRM_PATTERN.matcher(text);
        if (confirmMatcher.matches()) {
            String token = confirmMatcher.group(1);
            String result = shellService.confirmExecute(token, userId);
            bot.sendReply(msg, result);
            return;
        }

        // > 前缀命令
        String command = text.substring(1).trim(); // 去掉 >
        if (command.isEmpty()) {
            bot.sendReply(msg, "用法: > 命令\n例如: > df -h");
            return;
        }

        logger.info("🔧 [ShellHandler] 管理员 {} 执行: {}", userId, command);
        String result = shellService.execute(command, userId);
        bot.sendReply(msg, result);
    }
}
