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
               "比如准备查资料时说让我查一下稍等、准备搜聊天记录时说让我翻翻记录、正在回忆时说让我想想。" +
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
            return "状态消息已发送: " + message;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
