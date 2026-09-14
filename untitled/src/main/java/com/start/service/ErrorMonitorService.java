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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动异常监控服务 —— 定时扫描日志 ERROR，用便宜 API 做分类，
 * 确认是真实问题后交给主 AI 自修。API 余额不足时告警归儿。
 *
 * 流程：
 *   扫日志 → 发现 ERROR → 调审计 API（便宜模型）总结归类
 *   → 确认需要修 → 调主 AI 汇总（不注入工具）
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
    private static final long AUDIT_QUOTA_COOLDOWN_MS = 30 * 60_000L;
    private static final int MAX_DEFERRED_AUDIT_ERRORS = 100;

    // logback.xml pattern: %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n
    // 严格要求两个 pattern 保持一致，否则判定会失效。
    // 三个捕获组：1=时间戳 2=线程名 3=日志级别
    private static final Pattern LOG_HEADER_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^\\]]+)\\] (TRACE|DEBUG|INFO|WARN|ERROR|FATAL) "
    );
    // 自身线程产生的日志永远不算错误（自激）
    private static final String SELF_THREAD = "ErrorMonitor-Thread";

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
    private long auditQuotaCooldownUntil = 0L;
    private boolean quotaAlertSent = false;
    private final Deque<String> deferredAuditErrors = new ArrayDeque<>();

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
        List<String> rawLines = readNewRawLines(logFile, fileSize);
        List<String> blocks = parseErrorBlocks(rawLines);
        if (blocks.size() > MAX_ERRORS_PER_SCAN) {
            return new ArrayList<>(blocks.subList(0, MAX_ERRORS_PER_SCAN));
        }
        return blocks;
    }

    private List<String> readNewRawLines(Path logFile, long fileSize) throws IOException {
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            raf.seek(lastFilePos);
            String line;
            while ((line = raf.readLine()) != null) {
                lines.add(new String(line.getBytes("ISO-8859-1"), "UTF-8"));
            }
            lastFilePos = raf.getFilePointer();
        }
        return lines;
    }

    /**
     * 从原始日志行中提取"错误块"：以 ERROR/FATAL 级别日志行开头，紧随其后的
     * 堆栈行（无时间戳前缀）一并归入同一块。判定严格基于 logback 格式前缀，
     * 任何仅因业务文本里出现 "Exception" / "ERROR" / "FATAL" 字样而触发的
     * 误判（例如线程名 ErrorMonitor-Thread、变量名包含 Exception 等）均不会
     * 再命中。自身线程 ErrorMonitor-Thread 的输出永远忽略，避免自激。
     */
    static List<String> parseErrorBlocks(List<String> rawLines) {
        List<String> errors = new ArrayList<>();
        List<String> currentBlock = null;
        for (String line : rawLines) {
            Matcher m = LOG_HEADER_PATTERN.matcher(line);
            if (m.find()) {
                // 新的一行"日志行"——先 flush 上一块
                if (currentBlock != null) {
                    errors.add(String.join("\n", currentBlock));
                    currentBlock = null;
                }
                String thread = m.group(2);
                String level = m.group(3);
                if (("ERROR".equals(level) || "FATAL".equals(level))
                    && !SELF_THREAD.equals(thread)) {
                    currentBlock = new ArrayList<>();
                    currentBlock.add(line);
                }
            } else if (currentBlock != null) {
                // 堆栈行/非日志行——归到当前错误块
                currentBlock.add(line);
            }
        }
        if (currentBlock != null) {
            errors.add(String.join("\n", currentBlock));
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

    private String errorSignature(String errorBlock) {
        // errorBlock 形如 "yyyy-MM-dd ... [Thread] ERROR logger - message\n\tat xxx\n\tat yyy"
        // 用"logger + message"作去重键，剥离时间戳/线程名/堆栈行号，相同根因的消息稳定匹配
        Matcher m = LOG_HEADER_PATTERN.matcher(errorBlock);
        if (m.find()) {
            String body = errorBlock.substring(m.end());
            int newline = body.indexOf('\n');
            if (newline > 0) body = body.substring(0, newline);
            return body.replaceAll("\\d", "0").trim();
        }
        return errorBlock.substring(0, Math.min(errorBlock.length(), 80))
            .replaceAll("\\d", "0").trim();
    }

    // ==================== 审计 API 调用（便宜模型） ====================

    private void callAuditApi(List<String> errors) {
        List<String> auditErrors = takeDeferredAuditErrors();
        auditErrors.addAll(errors);
        long now = System.currentTimeMillis();
        if (now < auditQuotaCooldownUntil) {
            deferAuditErrors(auditErrors);
            logger.debug("审计 API 处于配额冷却期，跳过本轮调用（剩余约 {} 分钟）",
                    Math.max(1, (auditQuotaCooldownUntil - now + 59_999L) / 60_000L));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个日志分析器。以下是服务器日志中发现的 ").append(auditErrors.size()).append(" 条异常。\n\n");
        sb.append("```\n");
        for (int i = 0; i < Math.min(auditErrors.size(), 10); i++) {
            String e = auditErrors.get(i);
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
                    deferAuditErrors(auditErrors);
                    consecutiveQuotaErrors++;
                    auditQuotaCooldownUntil = System.currentTimeMillis() + AUDIT_QUOTA_COOLDOWN_MS;
                    logger.error("审计 API 连续 {} 次配额错误（累计 {} 次），HTTP {}",
                            AUDIT_MAX_RETRIES, consecutiveQuotaErrors, resp.statusCode());
                    if (!quotaAlertSent && consecutiveQuotaErrors >= ALERT_CONSECUTIVE_THRESHOLD) {
                        sendAlert("[审计API余额告警] mytokenland 疑似欠费或配额耗尽（已重试多次），HTTP " + resp.statusCode());
                        quotaAlertSent = true;
                    }
                    return;
                }

                // 调用成功，重置计数
                consecutiveQuotaErrors = 0;
                auditQuotaCooldownUntil = 0L;
                quotaAlertSent = false;

                if (resp.statusCode() != 200) {
                    deferAuditErrors(auditErrors);
                    logger.warn("审计 API 返回非200: {}", resp.statusCode());
                    return;
                }

                JsonNode json = MAPPER.readTree(resp.body());
                String content = extractAuditConclusion(json);
                logger.info("📊 审计API: 结论={}", content.substring(0, Math.min(content.length(), 100)));

                // 旧 bug：审计结论为空时 needsRepair("") 直接返回 false → ERROR 被静默跳过
                // 修复：空结论发告警归儿，由人工判断，不要静默吞掉
                if (content == null || content.trim().isEmpty()) {
                    logger.warn("⚠️ 审计 API 未产出结论（content + reasoning_content 都空）");
                    sendAlert("[审计API异常] 调用成功但未产出结论（reasoning 模型可能异常或平台故障），"
                            + "已发现 " + auditErrors.size() + " 条 ERROR 未分类，请人工检查。");
                    return;
                }

                if (needsRepair(content)) {
                    logger.warn("🔧 审计 API 判定需要修复，触发主 AI 总结");
                    triggerMainAiSummary(auditErrors, content);
                } else {
                    logger.info("✅ 审计 API 判定无需修复，跳过");
                }
                return;

            } catch (IOException | InterruptedException e) {
                logger.warn("审计 API 调用失败 (attempt {}/{}): {}", attempt + 1, AUDIT_MAX_RETRIES, e.getMessage());
                if (attempt < AUDIT_MAX_RETRIES - 1) {
                    try { Thread.sleep(AUDIT_RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    deferAuditErrors(auditErrors);
                    logger.error("审计 API 重试耗尽: {}", e.getMessage());
                }
            }
        }
    }

    private List<String> takeDeferredAuditErrors() {
        List<String> deferred = new ArrayList<>(deferredAuditErrors);
        deferredAuditErrors.clear();
        return deferred;
    }

    private void deferAuditErrors(List<String> errors) {
        for (String error : errors) {
            if (error != null && !deferredAuditErrors.contains(error)) {
                deferredAuditErrors.addLast(error);
            }
        }
        while (deferredAuditErrors.size() > MAX_DEFERRED_AUDIT_ERRORS) {
            deferredAuditErrors.removeFirst();
        }
    }

    private String buildRequestBody(String userMessage) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", BotConfig.getAuditModel());
        ArrayNode msgs = body.putArray("messages");
        // system prompt：给 reasoning 模型明确格式约束，避免 content 为空
        // 旧 bug：只发 user message，reasoning 模型把结论全放进 reasoning_content，content 永远空
        ObjectNode sysMsg = msgs.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你是日志分析器。只输出最终结论，不要分析过程。\n"
                + "严格按格式回复（中文一行）：[严重程度] 一句话结论。需要修复：是/否。原因：...\n"
                + "严重程度只能是：严重 / 一般 / 可忽略。");
        ObjectNode msg = msgs.addObject();
        msg.put("role", "user");
        msg.put("content", userMessage);
        // reasoning 模型（MiniMax-M2.7 等）的 reasoning_content 经常消耗 1000+ tokens
        // 旧 max_tokens=2000 经常截断 final answer，提到 4000 给 final answer 留够空间
        body.put("max_tokens", 4000);
        body.put("temperature", 0.1);
        return body.toString();
    }

    /**
     * 从审计 API 响应 JSON 中提取结论文本。
     * 优先用 OpenAI 标准字段 content；为空时 fallback 到 reasoning_content
     * （mytokenland 等聚合平台使用 MiniMax-M2.7 reasoning 模型，输出走 reasoning_content）。
     * 两端都空时返回空串——调用方按"无需修复"处理。
     */
    static String extractAuditConclusion(JsonNode json) {
        JsonNode msg = json.path("choices");
        if (!msg.isArray() || msg.isEmpty()) return "";
        JsonNode message = msg.get(0).path("message");
        String content = message.path("content").asText("");
        if (content != null && !content.isEmpty()) return content;
        // fallback: reasoning_content（reasoning 模型输出位置）
        String reasoning = message.path("reasoning_content").asText("");
        return reasoning == null ? "" : reasoning;
    }

    /**
     * 判定审计结论是否需要触发主 AI 汇总。
     *
     * 修复前 bug：把"严重程度"标签当成"是否需要修"的开关，导致
     * [一般] + 需要修复：是 永远被忽略——审计说"要修"被代码判"无需修"。
     *
     * 修复后：以"需要修复：是/否"为权威判定，严重程度只在无明确指令时兜底。
     *   - 明确"需要修复：否/不需要/无需修复" → false
     *   - 明确"需要修复：是" → true（不管严重程度）
     *   - 无明确指令 + [严重] → true（保守：严重则默认要修）
     *   - 其他情况（含 [一般]/[可忽略]） → false
     */
    static boolean needsRepair(String auditConclusion) {
        if (auditConclusion == null || auditConclusion.isEmpty()) return false;
        String lower = auditConclusion.toLowerCase();

        // 1. 明确"不要修"系列——优先级最高
        if (lower.contains("需要修复：否") || lower.contains("需要修复:否")) return false;
        if (lower.contains("需要修复：不需要") || lower.contains("需要修复:不需要")) return false;
        if (lower.contains("无需修复") || lower.contains("不需要修复")) return false;

        // 2. 明确"要修"——不管严重程度
        if (lower.contains("需要修复：是") || lower.contains("需要修复:是")) return true;
        if (lower.contains("需要立即修复") || lower.contains("需立即修复")) return true;

        // 3. 严重程度兜底：只有 [严重] 才默认要修
        if (lower.contains("[严重]")) return true;

        // 4. 保守默认：不修（[一般]/[可忽略] 或解析失败都走这里）
        return false;
    }

    // ==================== 主 AI 总结（贵模型） ====================

    private void triggerMainAiSummary(List<String> errors, String auditSummary) {
        try {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            long adminQQ = BotConfig.getAdminQq();
            String sessionId = "auto_summary_" + System.currentTimeMillis();

            // 主 AI 只总结审计结论和原始错误；这次使用无工具的纯文本入口，避免再次深入排查。
            StringBuilder prompt = new StringBuilder();
            prompt.append("【自动巡检 - ").append(time).append("】\n\n");
            prompt.append("审计 API 初步判定：").append(auditSummary).append("\n\n");
            prompt.append("原始错误（已去重，最多 5 条）：\n```\n");
            for (int i = 0; i < Math.min(errors.size(), 5); i++) {
                String e = errors.get(i);
                prompt.append(e.length() > 300 ? e.substring(0, 300) + "..." : e).append("\n");
            }
            prompt.append("```\n\n");
            prompt.append("请只根据上面的审计结论和原始错误做总结，不要继续排查，不要调用任何工具。\n");
            prompt.append("最后只输出**一个 JSON 对象**（用 ```json 包裹），不要任何其他文字：\n");
            prompt.append("```json\n");
            prompt.append("{\n");
            prompt.append("  \"severity\": \"严重/一般/可忽略\",\n");
            prompt.append("  \"summary\": \"一句话结论（必填）\",\n");
            prompt.append("  \"location\": \"出问题的文件:方法（可空）\",\n");
            prompt.append("  \"exceptionType\": \"异常类型（可空）\",\n");
            prompt.append("  \"suggestions\": [\"建议1\", \"建议2\"],\n");
            prompt.append("  \"needsFix\": true或false\n");
            prompt.append("}\n");
            prompt.append("```\n");
            prompt.append("⚠️ 行为约束：\n");
            prompt.append("- 只输出 JSON 对象，不要解释/寒暄/分析过程\n");
            prompt.append("- 如果仅凭现有材料无法定位明确问题，severity=可忽略，summary=无法定位根因，请人工查看");

            logger.info("🤖 触发主 AI 总结: sessionId={}", sessionId);
            String result = aiService.generateRaw(prompt.toString());

            if (botInstance == null) {
                logger.warn("botInstance 未设置，无法转发报告");
                return;
            }

            // 解析主 AI 输出的 JSON 报告，翻译后转发
            // 旧 bug：直接把 LLM reply 截断 300 字符 → 残缺报告
            // 修复：先尝试解析 JSON 结构化报告，失败兜底发原始文本（不截断）
            com.start.model.AuditReport report = AuditReportBuilder.parse(result);
            String message;
            if (report != null) {
                message = AuditReportBuilder.render(report);
                logger.info("📋 主 AI 输出已解析为结构化报告，severity={}, needsFix={}",
                        report.getSeverity(), report.isNeedsFix());
            } else if (result != null && !result.trim().isEmpty()) {
                // JSON 解析失败——主 AI 没按格式输出
                // 兜底：发原始文本，但保留全量（QQ 私聊支持长消息），加上提示头
                logger.warn("⚠️ 主 AI 输出未含 JSON 结构，回退到原始文本转发（{} chars）", result.length());
                message = "【巡检报告（主 AI 未按结构化输出）】\n" + result.trim();
            } else {
                // 主 AI 完全没输出（沉默/错误）——给归儿一个明确反馈
                logger.warn("⚠️ 主 AI 未产出任何回复");
                message = "【巡检报告（主 AI 未回复）】\n"
                        + "审计 API 判定需要修复，但主 AI 排查时未产出回复。\n"
                        + "审计结论：" + auditSummary + "\n"
                        + "请人工检查。";
            }

            // 安全截断：5000 字符（NapCat 单消息上限保护），不再 300 截断
            final int MAX_REPORT_CHARS = 5000;
            if (message.length() > MAX_REPORT_CHARS) {
                message = message.substring(0, MAX_REPORT_CHARS) + "\n...[报告过长已截断]";
            }
            botInstance.sendPrivateReply(adminQQ, message);
        } catch (Exception e) {
            logger.error("主 AI 总结触发失败", e);
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
        // stdout 重定向目标优先（nohup / 自定义部署），其次兼容老路径
        String[] candidates = {
            "/opt/qq-bot/qq-bot.stdout.log",
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
