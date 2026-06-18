package com.start.agent.social;

import com.start.agent.Tool;

import com.start.Main;

import java.util.*;

/**
 * 戳一戳工具。偶尔戳聊天的人增加互动，不能滥用。
 */
public class PokeTool implements Tool {
    private final Main bot;
    private final Map<String, Long> lastPokeTime = new HashMap<>();
    private static final long COOLDOWN_MS = 300_000; // 5分钟冷却，防止滥用
    public PokeTool(Main bot) { this.bot = bot; }

    @Override public String getName() { return "send_poke"; }

    @Override
    public String getDescription() { return "戳一戳群友。偶尔用来叫醒潜水的人或打招呼，不要频繁使用。"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "被戳的用户 QQ"),
                        "group_id", Map.of("type", "string", "description", "群号")
                ),
                "required", Arrays.asList("user_id", "group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        if (userId == null || groupId == null) return "缺少 user_id 或 group_id";

        String key = userId + ":" + groupId;
        long now = System.currentTimeMillis();
        Long last = lastPokeTime.get(key);
        if (last != null && now - last < COOLDOWN_MS) {
            return "冷却中，稍后再戳（" + ((COOLDOWN_MS - (now - last)) / 1000) + "秒后）";
        }
        lastPokeTime.put(key, now);

        try {
            var action = new com.fasterxml.jackson.databind.node.ObjectNode(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            action.put("action", "send_poke");
            var params = action.putObject("params");
            params.put("user_id", Long.parseLong(userId));
            params.put("group_id", Long.parseLong(groupId));
            bot.send(action.toString());
            return "已戳 " + userId;
        } catch (Exception e) {
            return "戳失败: " + e.getMessage();
        }
    }
}
