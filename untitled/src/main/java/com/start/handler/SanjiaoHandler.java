package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.service.WebScreenshotService;
import com.start.util.MessageUtil;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * 处理三角洲行动相关查询的 Handler。
 * 触发关键词「特勤处」「脑机」「密码」→ Playwright 截图 → 异步直发到群。
 * 含 @ 提及也能命中，不走慢速的 AI Tool 路径。
 */
public class SanjiaoHandler implements MessageHandler {

    private static final String[] KEYWORDS = {"特勤处", "脑机", "密码"};

    private final WebScreenshotService screenshotService = new WebScreenshotService();

    @Override
    public boolean match(JsonNode message) {
        return detectKeyword(message) != null;
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        long groupId = message.get("group_id").asLong();
        String keyword = detectKeyword(message);
        if (keyword == null) return;

        String taskName = switch (keyword) {
            case "特勤处" -> "kkrb-overview";
            case "脑机"   -> "kkrb-overview-2";
            case "密码"   -> "kkrb-overview-3";
            default       -> null;
        };
        if (taskName == null) return;

        final String finalLabel = keyword;
        screenshotService.takeScreenshot(taskName)
            .thenCompose(imagePath -> {
                try {
                    byte[] imageBytes = screenshotService.readAndCleanupImage(imagePath);
                    String base64 = Base64.getEncoder().encodeToString(imageBytes);
                    bot.sendGroupReply(groupId, "[CQ:image,file=base64://" + base64 + "]");
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    throw new RuntimeException("读取截图文件失败", e);
                }
            }).exceptionally(ex -> {
                String errMsg = "❌ " + finalLabel + "截图失败";
                Throwable cause = ex.getCause();
                if (cause != null && cause.getMessage() != null) {
                    String msg = cause.getMessage();
                    if (msg.length() > 100) msg = msg.substring(0, 100) + "...";
                    errMsg += "：" + msg;
                }
                bot.sendGroupReply(groupId, errMsg);
                return null;
            });
    }

    /** 仅当消息不含 @ 提及，且纯文本精确等于关键词时匹配 */
    private String detectKeyword(JsonNode message) {
        // 有 @ 提及 → 交给 AI 处理，不抢
        if (hasAtMention(message)) return null;

        String plainText = MessageUtil.extractPlainText(message.path("message"));
        if (plainText == null) return null;
        plainText = plainText.trim();
        if (plainText.isEmpty()) return null;

        for (String kw : KEYWORDS) {
            if (plainText.equals(kw)) return kw;
        }
        return null;
    }

    private boolean hasAtMention(JsonNode message) {
        try {
            JsonNode msgArray = message.path("message");
            if (msgArray.isArray()) {
                for (JsonNode seg : msgArray) {
                    if ("at".equals(seg.path("type").asText())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
