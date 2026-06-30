package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 文件查询工具 — 主 AI 通过此工具查询用户发送的文件。
 * 文件内容由副 AI（audit 模型）处理，不直接进主 AI 上下文。
 */
public class QueryFileTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(QueryFileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int MAX_TEXT_CHARS = 20000; // 发给副 AI 的最大文本长度
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024; // 最大下载 2MB

    private final Main bot;
    private final BaiLianService aiService;
    private final String userId;
    private final String sessionId;

    public QueryFileTool(Main bot, BaiLianService aiService, String userId, String sessionId) {
        this.bot = bot;
        this.aiService = aiService;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override public String getName() { return "query_file"; }

    @Override
    public String getDescription() {
        return "查询用户发送的文件。文件内容由副AI处理，不占用主AI上下文。\n" +
               "参数:\n" +
               "  action=list — 列出当前会话中用户刚发的文件（含文件名、大小、file_id）\n" +
               "  action=summarize — 让副AI阅读文件并总结关键信息（默认选这个）\n" +
               "  action=extract — 从文件中精确提取原文片段，副AI不会总结或编造，只返回原文\n" +
               "  file_id(必填，list除外) — 要操作的文件ID（从list获取）\n" +
               "  query(extract时必填) — 要提取什么内容，如\"提取第3段\"、\"找出关于XX的原文\"\n" +
               "⚠️ 收到文件后先用 list 看有哪些文件，再用 summarize 了解内容，" +
               "需要原文细节时才用 extract。不要假设文件内容，没有文件时不要调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string",
                "description", "操作类型: list(列出文件), summarize(副AI总结), extract(副AI提取原文)"));
        properties.put("file_id", Map.of("type", "string",
                "description", "文件ID，从 list 结果中获取。list 操作不需要此参数"));
        properties.put("query", Map.of("type", "string",
                "description", "extract 操作时的查询，如\"提取第3段\"、\"找出关于XX的原文\""));
        return Map.of("type", "object",
                "properties", properties,
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) return "请指定 action: list, summarize, extract";

        String sessionKey = sessionId != null ? sessionId : "private_" + userId;

        return switch (action) {
            case "list" -> listFiles(aiService.getPendingFiles(sessionKey));
            case "summarize" -> processFile(args, sessionKey, false);
            case "extract" -> processFile(args, sessionKey, true);
            default -> "未知 action: " + action + "。支持: list, summarize, extract";
        };
    }

    private String listFiles(List<Map<String, String>> files) {
        if (files.isEmpty()) return "当前会话没有待处理的文件。";
        StringBuilder sb = new StringBuilder("当前会话的文件：\n");
        for (int i = 0; i < files.size(); i++) {
            Map<String, String> f = files.get(i);
            sb.append(i + 1).append(". ");
            sb.append(f.getOrDefault("file_name", "未知文件"));
            String size = f.get("file_size");
            if (size != null && !size.isEmpty()) {
                sb.append("（").append(formatSize(size)).append("）");
            }
            sb.append("\n   file_id: ").append(f.get("file_id"));
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String processFile(Map<String, Object> args, String sessionKey, boolean extractMode) {
        String fileId = (String) args.get("file_id");
        if (fileId == null || fileId.isBlank()) return "请指定 file_id（先用 action=list 查看）";

        // 从缓存匹配文件元数据——必须先匹配到才允许下载，防止随意传入 file_id
        List<Map<String, String>> files = aiService.getPendingFiles(sessionKey);
        Map<String, String> meta = null;
        for (Map<String, String> f : files) {
            if (fileId.equals(f.get("file_id"))) { meta = f; break; }
        }
        if (meta == null) {
            return "未在待处理文件中找到 file_id=" + fileId + "。请先用 action=list 查看当前会话的文件列表。";
        }
        String fileName = meta.getOrDefault("file_name", "未知文件");

        // Step 1: 下载文件
        String text;
        try {
            text = downloadAndExtractText(fileId);
        } catch (Exception e) {
            logger.error("下载文件失败: file_id={}", fileId, e);
            return "下载文件失败: " + e.getMessage();
        }

        if (text == null || text.isBlank()) {
            aiService.removePendingFile(sessionKey, fileId);
            return "文件「" + fileName + "」为空或无法提取文本内容（可能是图片/二进制文件）。";
        }

        // Step 2: 调用副 AI
        String result = callAuditModel(fileName, text, extractMode ? (String) args.get("query") : null, extractMode);

        // 处理成功则清除该文件（不是清除所有文件）
        if (!result.startsWith("副AI")) {
            aiService.removePendingFile(sessionKey, fileId);
        }

        return result;
    }

    /** 通过 OneBot get_file 获取文件并提取文本 */
    private String downloadAndExtractText(String fileId) throws Exception {
        var params = MAPPER.createObjectNode();
        params.put("file_id", fileId);

        logger.info("获取文件: file_id={}", fileId);
        var future = bot.callOneBotApi("get_file", params);
        var resp = future.get(15, TimeUnit.SECONDS);

        if (resp == null || !"ok".equals(resp.path("status").asText())) {
            String err = resp != null ? resp.path("wording").asText("") : "无响应";
            throw new RuntimeException("OneBot get_file 失败: " + err);
        }

        JsonNode data = resp.path("data");
        String base64 = data.path("base64").asText("");
        String url = data.path("url").asText("");

        byte[] bytes;
        if (!base64.isEmpty()) {
            bytes = Base64.getDecoder().decode(base64);
        } else if (!url.isEmpty()) {
            bytes = downloadFromUrl(url);
        } else {
            throw new RuntimeException("get_file 未返回 base64 或 url");
        }

        if (bytes.length == 0) throw new RuntimeException("文件大小为0");
        logger.info("文件下载完成: {} bytes", bytes.length);

        return bytesToText(bytes);
    }

    private byte[] downloadFromUrl(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("下载文件 HTTP " + response.statusCode());
        }
        try (InputStream is = response.body(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                if (total + n > MAX_FILE_BYTES) {
                    bos.write(buf, 0, (int)(MAX_FILE_BYTES - total));
                    break;
                }
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        }
    }

    /** 尝试多种编码提取文本 */
    private String bytesToText(byte[] bytes) {
        // 跳过 BOM
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            offset = 3;
        }

        // 尝试 UTF-8
        String text = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        if (isReadableText(text)) return truncate(text);

        // 尝试 GBK
        text = new String(bytes, offset, bytes.length - offset, Charset.forName("GBK"));
        if (isReadableText(text)) return truncate(text);

        // 都失败了，可能是二进制文件
        return null;
    }

    private boolean isReadableText(String text) {
        if (text.length() < 4) return text.length() > 0;
        int printable = 0;
        for (int i = 0; i < Math.min(text.length(), 200); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F || c >= 0x4E00 || c == '\n' || c == '\r' || c == '\t') printable++;
        }
        return printable > Math.min(text.length(), 200) * 0.7;
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS) + "\n\n[... 文件过长，已截断，剩余部分未显示 ...]";
    }

    /** 调用副 AI（audit 模型）处理文件内容 */
    private String callAuditModel(String fileName, String text, String extractQuery, boolean extractMode) {
        String apiKey = BotConfig.getAuditApiKey();
        String url = BotConfig.getAuditBaseUrl();
        String model = BotConfig.getAuditModel();
        int timeoutMs = BotConfig.getAuditTimeoutMs();

        if (apiKey == null || apiKey.isBlank()) {
            return "副AI未配置（audit.api-key），无法处理文件。" +
                   (extractMode ? "" : "文件「" + fileName + "」已收到但无法自动总结。");
        }

        String systemPrompt;
        String userPrompt;

        if (extractMode) {
            systemPrompt = "你是一个精确的文本提取工具。你的任务是从文件内容中找出与用户查询相关的原文。\n" +
                           "【铁律 — 违反即为失败】\n" +
                           "1. 只返回原文中实际存在的文本片段，一字不改\n" +
                           "2. 绝对不要总结、概括、改写、翻译或补充说明\n" +
                           "3. 绝对不要编造、推测或添加原文中没有的内容\n" +
                           "4. 如果原文中没有相关信息，直接说\"未找到相关内容\"，不要解释为什么\n" +
                           "5. 不要加任何前缀（如\"原文如下：\"），直接把原文贴出来\n" +
                           "6. 不要评价原文内容，不要给建议";
            String q = (extractQuery != null && !extractQuery.isBlank()) ? extractQuery : "提取关键内容";
            userPrompt = "文件名：" + fileName + "\n用户查询：" + q + "\n\n请从以下文件内容中提取与查询相关的原文：\n\n" + text;
        } else {
            systemPrompt = "你是一个文件内容摘要助手。请阅读以下文件内容，用3-5句话总结关键信息。\n" +
                           "【铁律】只基于提供的文本进行总结，绝对不要编造、推测或添加原文中没有的内容。" +
                           "如果文件内容无法理解或看起来是乱码，如实告知。";
            userPrompt = "文件名：" + fileName + "\n\n请总结以下文件内容的关键信息：\n\n" + text;
        }

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", extractMode ? 1024 : 512);
            body.put("temperature", 0.0);

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs + 10000))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                logger.warn("query_file 副AI返回 {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return "副AI服务暂不可用。文件「" + fileName + "」已收到，请稍后再试或告知用户。";
            }

            JsonNode root = MAPPER.readTree(response.body());
            String result = root.path("choices").path(0).path("message").path("content").asText();

            if (result == null || result.isBlank()) {
                return "副AI未返回内容，文件「" + fileName + "」可能无法解析。";
            }
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...[截断]";
            }

            logger.info("📄 query_file {}: {}→{} chars, {}ms, mode={}",
                    extractMode ? "extract" : "summarize", text.length(), result.length(), elapsed,
                    extractMode && extractQuery != null ? extractQuery.substring(0, Math.min(30, extractQuery.length())) : "");

            return result;
        } catch (Exception e) {
            logger.error("query_file 副AI调用失败", e);
            return "副AI调用异常: " + e.getMessage() + "。文件「" + fileName + "」已收到但无法处理。";
        }
    }

    private static String formatSize(String sizeStr) {
        try {
            long bytes = Long.parseLong(sizeStr);
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        } catch (NumberFormatException e) {
            return sizeStr + "B";
        }
    }
}
