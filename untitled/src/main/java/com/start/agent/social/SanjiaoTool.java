package com.start.agent.social;

import com.start.Main;
import com.start.agent.Tool;
import com.start.model.KkrbGameData;
import com.start.model.KkrbGameData.SwatProduct;
import com.start.model.KkrbGameData.DoorPassword;
import com.start.service.KkrbScraperService;
import com.start.service.WebScreenshotService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 三角洲行动游戏数据查询工具，供 AI 调用。
 * 截图直接发送到群聊（不经 LLM 上下文），返回文本摘要供 AI 口述。
 */
public class SanjiaoTool implements Tool {

    private final WebScreenshotService screenshotService = new WebScreenshotService();
    private final KkrbScraperService scraperService = new KkrbScraperService();
    private final Main bot;
    private final String groupId;

    public SanjiaoTool() { this(null, "0"); }

    public SanjiaoTool(Main bot, String groupId) {
        this.bot = bot;
        this.groupId = groupId;
    }

    @Override public String getName() { return "delta_force_query"; }

    @Override public String getDescription() {
        return "查询三角洲行动游戏实时信息。action取值：" +
               "特勤处(各工作台时薪最高产品), " +
               "脑机(脑机可扫描物品), " +
               "密码(五个地图密码门今日密码)。" +
               "截图会自动发送到群聊，你只需根据文本摘要口述回复。";
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

        long gid = parseGroupId();

        try {
            // 截图和文本提取并行跑
            CompletableFuture<String> screenshotFuture = screenshotService.takeScreenshot(taskName);
            CompletableFuture<KkrbGameData> dataFuture = scraperService.fetch(taskName);

            // 等截图完成 → 直发群聊
            String imagePath = screenshotFuture.get(30, TimeUnit.SECONDS);
            if (imagePath != null && bot != null && gid > 0) {
                byte[] imageBytes = screenshotService.readAndCleanupImage(imagePath);
                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                bot.sendGroupReply(gid, "[CQ:image,file=base64://" + base64 + "]");
            }

            // 等文本提取完成 → 返回给 AI 口述
            KkrbGameData data = dataFuture.get(25, TimeUnit.SECONDS);
            if (data == null || data.hasError()) {
                return "截图已发送。" + action + "数据暂不可用。";
            }
            if (data.isEmpty()) {
                return "截图已发送。" + action + "当前无数据。";
            }
            return formatSummary(data);

        } catch (Exception e) {
            return "❌ " + action + "查询失败：" + e.getMessage();
        }
    }

    private String formatSummary(KkrbGameData data) {
        if (data.products != null && !data.products.isEmpty()) {
            StringBuilder sb = new StringBuilder("截图已发送。");
            sb.append(data.timestamp != null ? data.timestamp : "").append("：\n");
            for (SwatProduct p : data.products) {
                sb.append("· ").append(p.workbench).append(" — ").append(p.product)
                  .append("，时薪").append(fmtPrice(p.profit));
                if (p.sellTime != null && !p.sellTime.isEmpty())
                    sb.append("，").append(p.sellTime).append("卖");
                sb.append("\n");
            }
            return sb.toString().trim();
        }

        if (data.passwords != null && !data.passwords.isEmpty()) {
            StringBuilder sb = new StringBuilder("截图已发送。密码：");
            for (DoorPassword dp : data.passwords) {
                sb.append(dp.map).append(" ").append(dp.password).append("，");
            }
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        return "截图已发送。";
    }

    private long parseGroupId() {
        try { return Long.parseLong(groupId); } catch (NumberFormatException e) { return 0; }
    }

    private String fmtPrice(int n) {
        if (n >= 10000) {
            double wan = n / 10000.0;
            return wan == (int) wan ? (int) wan + "万" : String.format("%.1f万", wan);
        }
        if (n >= 1000) return (n / 1000) + "," + (n % 1000);
        return String.valueOf(n);
    }
}
