package com.start.agent;

import com.start.Main;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final long RECENT_STATUS_WINDOW_MS = 10_000L;
    private static final Map<String, Long> recentStatuses = new ConcurrentHashMap<>();

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
        return "发一条状态消息告诉当前会话的用户你在做什么，在需要花时间的操作前调用。" +
               "自动发到当前会话（群聊发群、私聊发私聊）。消息结合当前操作自行生成，简短口语化（20字以内），避免重复固定句式，不必解释内部实现。" +
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
            boolean sent;
            if (contextGroupId != null) {
                sent = botInstance.sendGroupReply(Long.parseLong(contextGroupId), message.trim());
            } else if (contextUserId != null) {
                sent = botInstance.sendPrivateReply(Long.parseLong(contextUserId), message.trim());
            } else {
                return "无法确定发送目标";
            }
            if (sent) recentStatuses.put(statusKey(), System.currentTimeMillis());
            return "ok";
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    /** Prevents a media tool from repeating a status the model just sent. */
    public static boolean consumeRecent(String groupId, String userId) {
        String key = key(groupId, userId);
        Long sentAt = recentStatuses.remove(key);
        return sentAt != null && System.currentTimeMillis() - sentAt <= RECENT_STATUS_WINDOW_MS;
    }

    private String statusKey() {
        return key(contextGroupId, contextUserId);
    }

    private static String key(String groupId, String userId) {
        return (groupId == null ? "private" : groupId) + ":" + (userId == null ? "" : userId);
    }
}
