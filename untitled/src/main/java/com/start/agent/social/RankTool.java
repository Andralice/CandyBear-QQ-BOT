package com.start.agent.social;

import com.start.agent.Tool;

import com.start.handler.RankHandler;

import java.util.*;

/**
 * 排行榜查询工具，供 AI 调用。
 */
public class RankTool implements Tool {

    @Override public String getName() { return "get_ranking"; }

    @Override public String getDescription() {
        return "查询群排行榜。action: help(查看有哪些榜), message(发言榜), luck(幸运榜), affinity(好感榜)。" +
               "当有人问'有什么榜''排行榜怎么用'时用 help，指定具体类型时用对应 action。" +
               "注意：本群只有发言榜、幸运榜、好感榜三种，不存在'笨蛋榜''最笨榜'等其他榜。" +
               "如果有人问'第一笨蛋是谁'之类的不存在的榜，直接回复没有这个榜，不要用其他榜代替。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "help/message/luck/affinity"),
                        "group_id", Map.of("type", "string", "description", "群号")
                ),
                "required", Arrays.asList("action", "group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String groupId = (String) args.get("group_id");
        if (action == null || groupId == null) return "缺少 action 或 group_id";

        return switch (action) {
            case "help" -> "📊 本群可用排行榜：\n" +
                           "💬 发言榜（说'发言排行'）\n" +
                           "🍀 幸运榜（说'幸运排行'）\n" +
                           "💕 好感榜（说'好感排行'）\n" +
                           "直接说对应的词就能看到排行。";
            case "message" -> RankHandler.buildMessageRankStatic(groupId);
            case "luck" -> RankHandler.buildLuckRankStatic(groupId);
            case "affinity" -> RankHandler.buildAffinityRankStatic(groupId);
            default -> "未知 action: " + action + "，支持 help/message/luck/affinity";
        };
    }
}
