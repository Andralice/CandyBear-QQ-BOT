package com.start.agent;

import com.start.Main;
import com.start.service.BaiLianService;

import java.util.*;

/**
 * 异步等待回复工具。AI 向某人提出问题后，注册一个异步等待，
 * 当对方在群里回复时自动触发 AI 继续对话。
 */
public class AwaitReplyTool implements Tool {
    private final Main botInstance;
    private final BaiLianService baiLianService;
    private final String contextGroupId;
    private final String contextUserId;
    private final String contextSessionId;

    public AwaitReplyTool(Main botInstance, BaiLianService baiLianService,
                          String contextGroupId, String contextUserId, String contextSessionId) {
        this.botInstance = botInstance;
        this.baiLianService = baiLianService;
        this.contextGroupId = contextGroupId;
        this.contextUserId = contextUserId;
        this.contextSessionId = contextSessionId;
    }

    @Override public String getName() { return "await_reply"; }

    @Override public String getDescription() {
        return "向某人提问并等待TA的回复。当你想进一步了解某件事、需要对方澄清、或者想追问细节时调用。" +
               "也用于用户说「识图」「看图」「看看这个」但没发图片时——调用此工具让TA发图，context写明你想看什么。" +
               "调用后糖果熊会在群里@对方提问，然后安静等待。对方回复后，糖果熊会自动根据回复内容继续对话。" +
               "参数：target_user_id(问谁), target_nickname(对方昵称), question(发到群里的问题，要自然口语化), context(你内心想知道什么，不会发出去，用于决定收到回复后怎么回应), timeout_seconds(等待秒数，默认120)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "target_user_id", Map.of("type", "string", "description", "要问的用户的QQ号"),
                        "target_nickname", Map.of("type", "string", "description", "对方的昵称"),
                        "question", Map.of("type", "string", "description", "发到群里的问题，自然口语化，不要像审问"),
                        "context", Map.of("type", "string", "description", "你内心想了解什么（不发出去），用于收到回复后决定如何回应"),
                        "timeout_seconds", Map.of("type", "string", "description", "等待超时秒数，默认120")
                ),
                "required", Arrays.asList("target_user_id", "target_nickname", "question", "context"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String targetUserId = (String) args.get("target_user_id");
        String targetNickname = (String) args.get("target_nickname");
        String question = (String) args.get("question");
        String context = (String) args.get("context");
        int timeoutSec = parseIntSafe((String) args.get("timeout_seconds"), 120);

        if (targetUserId == null || question == null) return "缺少 target_user_id 或 question";
        if (contextGroupId == null) return "仅在群聊中可用";

        // 在群里 @ 对方提问
        String fullMessage = "[CQ:at,qq=" + targetUserId + "] " + question.trim();
        try {
            botInstance.sendGroupReply(Long.parseLong(contextGroupId), fullMessage);
        } catch (Exception e) {
            return "发送问题失败: " + e.getMessage();
        }

        // 注册异步等待
        baiLianService.registerAwait(contextGroupId, targetUserId, targetNickname,
                question.trim(), context != null ? context.trim() : "", contextSessionId,
                timeoutSec * 1000L);

        return "已向 " + targetNickname + "(" + targetUserId + ") 提问：" + question
                + "，等待TA回复（超时=" + timeoutSec + "秒）。你现在可以先不回复，等TA回了再说。";
    }

    private int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
