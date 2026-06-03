package com.start.agent;

import com.start.Main;

import java.util.*;

/**
 * AI 发送进度状态消息。让糖果熊在使用工具时可以实时告诉用户在做什么，
 * 而不是沉默地干完所有事才一次性回复。
 */
public class SendStatusTool implements Tool {
    private final Main botInstance;

    public SendStatusTool(Main botInstance) {
        this.botInstance = botInstance;
    }

    @Override public String getName() { return "send_status"; }

    @Override public String getDescription() {
        return "发送一条状态消息到群里，在需要花时间的操作前告诉用户你在干什么。" +
               "语气要自然像真人，不要用让我开头。好的例子：稍等我看一下、嗯等下、我翻翻、诶你等等——、唔我想想。坏的例子：让我查一下、让我搜索。" +
               "参数：group_id(群号), message(状态消息，口语化、有糖果熊的风格，20字以内)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "message", Map.of("type", "string", "description", "状态消息，简短口语化")
                ),
                "required", Arrays.asList("group_id", "message"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String message = (String) args.get("message");
        if (groupId == null || message == null || message.isBlank()) return "缺少参数";

        try {
            botInstance.sendGroupReply(Long.parseLong(groupId), message.trim());
            return "ok";
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
