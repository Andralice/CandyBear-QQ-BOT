package com.start.agent;

import com.start.Main;
import com.start.service.ToolAuthorizationService;

import java.util.*;

/**
 * 发群消息工具。用于在私聊中让糖果熊替自己往群里传话。
 *
 * 第三阶段 3.1 改造：白名单/黑名单/限流检查改由 ToolAuthorizationService 集中处理。
 */
public class SendGroupTool implements Tool {
    private final Main bot;
    private final String realUserId;
    private final String contextGroupId;

    public SendGroupTool(Main bot, String realUserId) {
        this(bot, realUserId, null);
    }

    public SendGroupTool(Main bot, String realUserId, String contextGroupId) {
        this.bot = bot;
        this.realUserId = realUserId;
        this.contextGroupId = contextGroupId;
    }

    @Override public String getName() { return "send_group_msg"; }

    @Override
    public String getDescription() {
        return "向指定群发送消息。当有人在私聊里说'帮我在群里说XX''帮我@XX一下'时调用。" +
               "只用于跨会话传话，不能用来回复当前群聊；当前群聊直接输出回复。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "目标群号"),
                        "message", Map.of("type", "string",
                                "description", "要发送的内容。"
                                        + "@某人用 [CQ:at,qq=QQ号]（QQ号见 prompt 里的【群里的人】列表）。"
                                        + "@ 群员是日常交流的常见方式：用户让你传话、你想回应当前聊天的人、重要通知（活动/截止/事件）都可以 @。"
                                        + "但别无意义地 @，会骚扰群友。")
                ),
                "required", Arrays.asList("group_id", "message"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String message = (String) args.get("message");
        if (groupId == null || message == null) return "缺少 group_id 或 message";
        if (contextGroupId != null && contextGroupId.equals(groupId)) {
            return "拒绝发送：不能用 send_group_msg 重复回复当前群聊，请直接输出回复";
        }

        // 集中授权检查（白名单 + 黑名单 + 限流）
        ToolAuthorizationService.AuthorizationResult auth =
                ToolAuthorizationService.getInstance().checkGroupSend(groupId, message, realUserId);
        if (!auth.allowed) {
            return "拒绝发送：" + auth.reason;
        }

        try {
            long targetGroup = Long.parseLong(groupId);
            bot.sendGroupReply(targetGroup, message);
            return "已发送到群 " + groupId;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
