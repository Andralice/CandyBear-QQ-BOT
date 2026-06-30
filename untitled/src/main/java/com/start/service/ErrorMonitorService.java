package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动异常监控服务 —— 定时扫描日志 ERROR，用便宜 API 做分类，
 * 确认是真实问题后交给主 AI 自修。API 余额不足时告警归儿。
 *
 * 流程：
 *   扫日志 → 发现 ERROR → 调审计 API（便宜模型）总结归类
 *   → 确认需要修 → 调主 AI（audit_logs → read_code → self_evolve）
 *   → 任何 API 返回余额/配额耗尽 → 发 QQ 告警归儿
 */
public class ErrorMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorMonitorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCAN_INTERVAL_MINUTES = 5;
    private static final int MAX_ERRORS_PER_SCAN = 20;
    private static final int DEDUP_WINDOW_SECONDS = 1800;
    private static final int AUDIT_MAX_RETRIES = 3;
    private static final int AUDIT_RETRY_DELAY_MS = 2000;
    private static final int ALERT_CONSECUTIVE_THRESHOLD = 3;

    private final BaiLianService aiService;
    private Main botInstance;
    private static volatile Main staticBotInstance;
    private volatile boolean running = false;
    private Thread monitorThread;

    // 日志扫描状态
    private long lastFilePos = 0;
    private Path lastLogPath = null;
    private final Map<String, Long> notifiedSignatures = new ConcurrentHashMap<>();
    private int consecutiveQuotaErrors = 0;

    private final HttpClient httpClient;

    public ErrorMonitorService(BaiLianService aiService) {
        this.aiService = aiService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setBotInstance(Main bot) {
        this.botInstance = bot;
        staticBotInstance = bot;
    }

    public void start() {
        if (running) return;
        running = true;
        monitorThread = new Thread(this::monitorLoop, "ErrorMonitor-Thread");
        monitorThread.setDaemon(true);
        monitorThread.start();
        logger.info("🔍 异常自动监控已启动（每{}分钟，审计API={}）", SCAN_INTERVAL_MINUTES, BotConfig.getAuditModel());
    }

    public void stop() {
        running = false;
        if (monitorThread != null) monitorThread.interrupt();
    }

    private void monitorLoop() {
        try { Thread.sleep(120_000); } catch (InterruptedException e) { return; }

        while (running) {
            try {
                scanAndAlert();
            } catch (Exception e) {
                logger.error("ErrorMonitor 扫描异常", e);
            }
            try {
                Thread.sleep(SCAN_INTERVAL_MINUTES * 60_000L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ==================== 日志扫描 ====================

    private void scanAndAlert() {
        Path logFile = findLogFile();
        if (logFile == null) return;

        if (lastLogPath == null || !lastLogPath.equals(logFile)) {
            lastLogPath = logFile;
            lastFilePos = 0;
        }
        try {
            long fileSize = Files.size(logFile);
            if (fileSize < lastFilePos) lastFilePos = 0;
            if (fileSize <= lastFilePos) return;

            List<String> newErrors = readNewErrors(logFile, fileSize);
            if (newErrors.isEmpty()) return;

            List<String> freshErrors = deduplicate(newErrors);
            if (freshErrors.isEmpty()) return;

            logger.warn("🔍 发现 {} 条新 ERROR，交给审计 API 分类", freshErrors.size());
            callAuditApi(freshErrors);

        } catch (IOException e) {
            logger.warn("ErrorMonitor 读取日志失败: {}", e.getMessage());
        }
    }

    private List<String> readNewErrors(Path logFile, long fileSize) throws IOException {
        List<String> errors = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            raf.seek(lastFilePos);
            String line;
            while ((line = raf.readLine()) != null && errors.size() < MAX_ERRORS_PER_SCAN) {
                if (line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL")) {
                    errors.add(new String(line.getBytes("ISO-8859-1"), "UTF-8"));
                }
            }
            lastFilePos = raf.getFilePointer();
        }
        return errors;
    }

    private List<String> deduplicate(List<String> errors) {
        long now = System.currentTimeMillis() / 1000;
        List<String> fresh = new ArrayList<>();
        for (String err : errors) {
            String sig = errorSignature(err);
            Long lastNotified = notifiedSignatures.get(sig);
            if (lastNotified == null || (now - lastNotified) > DEDUP_WINDOW_SECONDS) {
                notifiedSignatures.put(sig, now);
                fresh.add(err);
            }
        }
        notifiedSignatures.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_WINDOW_SECONDS * 2);
        return fresh;
    }

    private String errorSignature(String line) {
        String sig;
        int excIdx = line.indexOf("Exception");
        if (excIdx > 0) {
            int start = Math.max(0, excIdx - 30);
            sig = line.substring(start, Math.min(line.length(), excIdx + 20));
        } else if (line.contains("ERROR")) {
            int errIdx = line.indexOf("ERROR");
            int start = Math.max(0, errIdx - 10);
            sig = line.substring(start, Math.min(line.length(), errIdx + 50));
        } else {
            sig = line.substring(0, Math.min(line.length(), 80));
        }
        return sig.trim().replaceAll("\\d", "0");
    }

    // ==================== 审计 API 调用（便宜模型） ====================

    private void callAuditApi(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个日志分析器。以下是服务器日志中发现的 ").append(errors.size()).append(" 条异常。\n\n");
        sb.append("```\n");
        for (int i = 0; i < Math.min(errors.size(), 10); i++) {
            String e = errors.get(i);
            sb.append(e.length() > 200 ? e.substring(0, 200) + "..." : e).append("\n");
        }
        sb.append("```\n\n");
        sb.append("请用2-4句话回复：\n");
        sb.append("1. 这些错误的类型和严重程度（严重/一般/可忽略）\n");
        sb.append("2. 是否有需要立即修复的问题\n");
        sb.append("回复格式：'[严重程度] 结论。需要修复：是/否。原因：...'");

        String requestBody = buildRequestBody(sb.toString());

        for (int attempt = 0; attempt < AUDIT_MAX_RETRIES; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BotConfig.getAuditBaseUrl()))
                        .header("Authorization", "Bearer " + BotConfig.getAuditApiKey())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(BotConfig.getAuditTimeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (isQuotaError(resp.statusCode(), resp.body())) {
                    logger.warn("审计 API 疑似配额问题 (attempt {}/{}): HTTP {} body={}",
                            attempt + 1, AUDIT_MAX_RETRIES, resp.statusCode(),
                            resp.body() != null ? resp.body().substring(0, Math.min(200, resp.body().length())) : "");
                    if (attempt < AUDIT_MAX_RETRIES - 1) {
                        try { Thread.sleep(AUDIT_RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        continue;
                    }
                    consecutiveQuotaErrors++;
                    logger.error("审计 API 连续 {} 次配额错误（累计 {} 次），HTTP {}",
                            AUDIT_MAX_RETRIES, consecutiveQuotaErrors, resp.statusCode());
                    if (consecutiveQuotaErrors >= ALERT_CONSECUTIVE_THRESHOLD) {
                        sendAlert("[审计API余额告警] mytokenland 疑似欠费或配额耗尽（已重试多次），HTTP " + resp.statusCode());
                    }
                    return;
                }

                // 调用成功，重置计数
                consecutiveQuotaErrors = 0;

                if (resp.statusCode() != 200) {
                    logger.warn("审计 API 返回非200: {}", resp.statusCode());
                    return;
                }

                JsonNode json = MAPPER.readTree(resp.body());
                String content = json.path("choices").get(0).path("message").path("content").asText("");
                logger.info("📊 审计API: 结论={}", content.substring(0, Math.min(content.length(), 100)));

                if (needsRepair(content)) {
                    logger.warn("🔧 审计 API 判定需要修复，触发主 AI");
                    triggerMainAiFix(errors, content);
                } else {
                    logger.info("✅ 审计 API 判定无需修复，跳过");
                }
                return;

            } catch (IOException | InterruptedException e) {
                logger.warn("审计 API 调用失败 (attempt {}/{}): {}", attempt + 1, AUDIT_MAX_RETRIES, e.getMessage());
                if (attempt < AUDIT_MAX_RETRIES - 1) {
                    try { Thread.sleep(AUDIT_RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    logger.error("审计 API 重试耗尽: {}", e.getMessage());
                }
            }
        }
    }

    private String buildRequestBody(String userMessage) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", BotConfig.getAuditModel());
        ArrayNode msgs = body.putArray("messages");
        ObjectNode msg = msgs.addObject();
        msg.put("role", "user");
        msg.put("content", userMessage);
        body.put("max_tokens", 300);
        body.put("temperature", 0.1);
        return body.toString();
    }

    private boolean needsRepair(String auditConclusion) {
        String lower = auditConclusion.toLowerCase();
        if (lower.contains("需要修复：否") || lower.contains("需要修复:否")) return false;
        if (lower.contains("需要修复：不需要") || lower.contains("需要修复:不需要")) return false;
        if (lower.contains("无需修复") || lower.contains("不需要修复")) return false;
        if (lower.contains("[一般]") || lower.contains("[可忽略]")) return false;
        if (lower.contains("需要修复：是") || lower.contains("需要修复:是")) return true;
        if (lower.contains("[严重]") && !lower.contains("需要修复：否")) return true;
        return false;
    }

    // ==================== 主 AI 修复（贵模型） ====================

    private void triggerMainAiFix(List<String> errors, String auditSummary) {
        try {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            long adminQQ = BotConfig.getAdminQq();
            String sessionId = "auto_fix_" + System.currentTimeMillis();

            StringBuilder prompt = new StringBuilder();
            prompt.append("【自动巡检 - ").append(time).append("】\n\n");
            prompt.append("日志监控发现异常，审计 API 判定：").append(auditSummary).append("\n\n");
            prompt.append("原始错误（已去重）：\n```\n");
            for (int i = 0; i < Math.min(errors.size(), 5); i++) {
                String e = errors.get(i);
                prompt.append(e.length() > 300 ? e.substring(0, 300) + "..." : e).append("\n");
            }
            prompt.append("```\n\n");
            prompt.append("请执行：\n");
            prompt.append("1. audit_logs action=errors 看详细堆栈\n");
            prompt.append("2. 定位问题代码 → read_code（只读，不要改）\n");
            prompt.append("3. 分析完后用 send_private_msg 告诉归儿：\n");
            prompt.append("   - 异常是什么、严重程度\n");
            prompt.append("   - 问题出在哪个文件的哪个方法\n");
            prompt.append("   - 建议怎么修（给出具体的 old_snippet → new_snippet）\n");
            prompt.append("   - 如果简单的话也说明一下需要几行改动\n");
            prompt.append("⚠️ 不要调用 self_evolve，只分析+报告。归儿会自己决定要不要修。\n");
            prompt.append("如果审计判断有误或无需修，也说一声。");

            logger.info("🤖 触发主 AI 修复: sessionId={}", sessionId);
            String result = aiService.generate(sessionId, String.valueOf(adminQQ), prompt.toString(), null, "系统巡检");

            if (result != null && !result.trim().isEmpty() && botInstance != null) {
                String shortResult = result.length() > 300 ? result.substring(0, 300) + "..." : result;
                botInstance.sendPrivateReply(adminQQ, "[异常报告]\n" + shortResult);
            }
        } catch (Exception e) {
            logger.error("主 AI 修复触发失败", e);
        }
    }

    // ==================== API 余额告警 ====================

    /**
     * 供 BaiLianService 调用：主 AI API 返回 HTTP 错误时检测是否配额耗尽。
     */
    public static void reportMainApiError(int statusCode, String body) {
        if (isQuotaError(statusCode, body)) {
            String detail = body != null && body.length() > 200 ? body.substring(0, 200) : "";
            sendAlert("[主AI余额告警] DeepSeek API 可能欠费或配额耗尽！HTTP " + statusCode + " " + detail);
        }
    }

    private static boolean isQuotaError(int statusCode, String body) {
        if (statusCode == 402) return true;
        if (body == null) return false;
        String lower = body.toLowerCase();
        if (lower.contains("insufficient") || lower.contains("quota") || lower.contains("balance")
            || lower.contains("余额不足") || lower.contains("配额不足") || lower.contains("欠费")
            || lower.contains("rate limit") || lower.contains("billing")) {
            return true;
        }
        return false;
    }

    private static void sendAlert(String msg) {
        if (staticBotInstance != null) {
            try {
                staticBotInstance.sendPrivateReply(BotConfig.getAdminQq(), msg);
                logger.warn("⚠️ {}", msg);
            } catch (Exception e) {
                logger.error("发送告警失败", e);
            }
        }
    }

    // ==================== 工具方法 ====================

    private Path findLogFile() {
        String[] candidates = {
            "/opt/qq-bot/qq-bot.log",
            "/opt/qq-bot/logs/app.log",
            "qq-bot.log",
            "logs/app.log"
        };
        for (String path : candidates) {
            Path p = Paths.get(path);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
