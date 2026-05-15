package com.start.agent;

import com.start.Main;

import java.util.*;

/**
 * AI 语音工具。糖果熊在群里"说话"，萌感拉满。
 */
public class VoiceTool implements Tool {
    private final Main bot;
    private final Map<String, Long> lastVoiceTime = new HashMap<>();
    private static final long COOLDOWN_MS = 60_000; // 1分钟冷却

    public VoiceTool(Main bot) { this.bot = bot; }

    @Override public String getName() { return "send_voice"; }

    @Override
    public String getDescription() { return "在群里发送AI语音消息。用于重要通知、游戏喊人、特别时刻。别频繁用。"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "text", Map.of("type", "string", "description", "要说的话（会转成语音），10-30字最合适")
                ),
                "required", Arrays.asList("group_id", "text"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String text = (String) args.get("text");
        if (groupId == null || text == null) return "缺少 group_id 或 text";

        long now = System.currentTimeMillis();
        Long last = lastVoiceTime.get(groupId);
        if (last != null && now - last < COOLDOWN_MS) {
            return "语音冷却中，稍后再发";
        }
        lastVoiceTime.put(groupId, now);

        if (text.length() > 100) text = text.substring(0, 100);

        try {
            var action = new com.fasterxml.jackson.databind.node.ObjectNode(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            action.put("action", "send_group_ai_record");
            var params = action.putObject("params");
            params.put("group_id", Long.parseLong(groupId));
            params.put("text", text);
            bot.send(action.toString());
            return "已发送语音: " + text;
        } catch (Exception e) {
            return "语音发送失败: " + e.getMessage();
        }
    }
}
