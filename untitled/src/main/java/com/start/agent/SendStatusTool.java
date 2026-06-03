package com.start.agent;

import com.start.Main;

import java.util.*;

/**
 * AI 发送进度状态消息。让糖果熊在使用工具时可以实时告诉用户在做什么，
 * 而不是沉默地干完所有事才一次性回复。
 * <p>
 * 根据会话上下文自动选择发送方式：
 * - 群聊会话 → 发到当前群（或指定的 group_id）
 * - 私聊会话 → 发给当前用户（或指定的 user_id）
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

    @Override public String getDescription() {
        return "发一条状态消息告诉用户你在做什么，在需要花时间的操作前调用。" +
               "语气要自然像真人，不要用让我开头。好的例子：稍等我看一下、嗯等下、我翻翻、诶你等等——、唔我想想。坏的例子：让我查一下、让我搜索。" +
               "参数：message(状态消息，口语化，20字以内)。可选参数：user_id(私聊目标QQ) 或 group_id(群号)——不传则自动发到当前会话";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "状态消息，简短口语化，20字以内"));
        properties.put("group_id", Map.of("type", "string", "description", "目标群号（可选，默认当前群）"));
        properties.put("user_id", Map.of("type", "string", "description", "目标用户QQ（可选，默认当前用户）"));
        return Map.of("type", "object",
                "properties", properties,
                "required", List.of("message"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String message = (String) args.get("message");
        if (message == null || message.isBlank()) return "缺少 message";

        String explicitGroupId = (String) args.get("group_id");
        String explicitUserId = (String) args.get("user_id");

        try {
            // 优先使用显式参数，其次使用会话上下文
            if (explicitGroupId != null && !explicitGroupId.isEmpty()) {
                botInstance.sendGroupReply(Long.parseLong(explicitGroupId), message.trim());
            } else if (explicitUserId != null && !explicitUserId.isEmpty()) {
                botInstance.sendPrivateReply(Long.parseLong(explicitUserId), message.trim());
            } else if (contextGroupId != null) {
                // 群聊上下文 → 发到当前群
                botInstance.sendGroupReply(Long.parseLong(contextGroupId), message.trim());
            } else if (contextUserId != null) {
                // 私聊上下文 → 发给当前用户
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
