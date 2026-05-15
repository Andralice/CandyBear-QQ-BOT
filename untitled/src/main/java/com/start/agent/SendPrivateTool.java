package com.start.agent;

import com.start.Main;

import java.util.Arrays;
import java.util.Map;

/**
 * 发送私聊消息工具。用于游戏分发词语等场景。
 */
public class SendPrivateTool implements Tool {
    private final Main bot;

    public SendPrivateTool(Main bot) {
        this.bot = bot;
    }

    @Override
    public String getName() {
        return "send_private_msg";
    }

    @Override
    public String getDescription() {
        return "向指定用户发送一条私聊消息。谁是被卧底时，用它给每个玩家私发词语。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "接收私聊的用户 QQ"),
                        "message", Map.of("type", "string", "description", "私聊内容"),
                        "group_id", Map.of("type", "string", "description", "来源群号，非好友私聊必须填")
                ),
                "required", Arrays.asList("user_id", "message")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String message = (String) args.get("message");
        String groupId = (String) args.get("group_id");
        if (userId == null || message == null) return "缺少 user_id 或 message";
        try {
            long gid = 0;
            if (groupId != null && !groupId.isEmpty() && !"null".equals(groupId)) {
                gid = Long.parseLong(groupId);
            }
            bot.sendPrivateReply(Long.parseLong(userId), gid, message);
            return "已发送私聊给 " + userId;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
