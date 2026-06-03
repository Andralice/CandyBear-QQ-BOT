package com.start.agent;

import com.start.service.WebScreenshotService;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 三角洲行动游戏截图工具，供 AI 调用。
 * 支持查询：特勤处（最划算项目）、脑机（可扫描物品）、密码（五个地图密码门今日密码）。
 */
public class SanjiaoTool implements Tool {

    private final WebScreenshotService screenshotService = new WebScreenshotService();

    @Override public String getName() { return "delta_force_query"; }

    @Override public String getDescription() {
        return "查询三角洲行动游戏实时信息，返回截图。action取值：" +
               "特勤处(查看特勤处当前做什么最划算), " +
               "脑机(查看脑机当前可扫描什么物品), " +
               "密码(查看五个地图密码门的今日密码)。" +
               "当有人问三角洲相关的问题时根据意图选择对应action。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "查询类型：特勤处/脑机/密码")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "缺少 action，支持：特勤处/脑机/密码";

        String taskName = switch (action.trim()) {
            case "特勤处" -> "kkrb-overview";
            case "脑机" -> "kkrb-overview-2";
            case "密码" -> "kkrb-overview-3";
            default -> null;
        };

        if (taskName == null) return "未知 action: " + action + "，支持：特勤处/脑机/密码";

        try {
            String imagePath = screenshotService.takeScreenshot(taskName)
                    .get(30, TimeUnit.SECONDS);
            byte[] imageBytes = screenshotService.readAndCleanupImage(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "[CQ:image,file=base64://" + base64 + "]";
        } catch (Exception e) {
            return "❌ " + action + "截图失败：" + e.getMessage();
        }
    }
}
