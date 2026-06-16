package com.start.agent;

import com.start.Main;

import java.util.*;

/**
 * AI 发送进度状态消息。让糖果熊在使用工具时可以实时告诉用户在做什么，
 * 而不是沉默地干完所有事才一次性回复。
 * <p>
 * 根据会话上下文自动选择发送方式：
 * - 群聊会话 → 发到当前群
 * - 私聊会话 → 发给当前用户
 * <p>
 * 不接受显式 group_id/user_id 参数，只发到当前会话，避免串频道。
 */
public class SendStatusTool implements Tool {
    private final Main botInstance;
    private final String contextGroupId;
    private final String contextUserId;

    /**
     * @param botInstance    bot 实例
     * @param contextGroupId 当前会话的群号（私聊时为 null）
     * @param contextUserId  当前会话的用户 QQ
     */
    public SendStatusTool(Main botInstance, String contextGroupId, String contextUserId) {
        this.botInstance = botInstance;
        this.contextGroupId = (contextGroupId != null && !contextGroupId.isEmpty()) ? contextGroupId : null;
        this.contextUserId = contextUserId;
    }

    @Override public String getName() { return "send_status"; }

    @Override
    public String getDescription() {
        return "发一条状态消息告诉当前会话的用户你在做什么，在需要花时间的操作前调用。自动发到当前会话（群聊发群、私聊发私聊）。" +
               "语气要自然像真人，不要用让我开头。好的例子：稍等我看一下、嗯等下、我翻翻、诶你等等——、唔我想想。坏的例子：让我查一下、让我搜索。" +
               "参数：message(状态消息，口语化，20字以内)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "状态消息，简短口语化，20字以内"));
        return Map.of("type", "object",
                "properties", properties,
                "required", List.of("message"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String message = (String) args.get("message");
        if (message == null || message.isBlank()) return "缺少 message";

        try {
            if (contextGroupId != null) {
                botInstance.sendGroupReply(Long.parseLong(contextGroupId), message.trim());
            } else if (contextUserId != null) {
                botInstance.sendPrivateReply(Long.parseLong(contextUserId), message.trim());
            } else {
                return "无法确定发送目标";
            }
            return "ok";
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
