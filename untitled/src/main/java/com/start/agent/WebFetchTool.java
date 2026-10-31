package com.start.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import com.start.service.AuditModelUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 网页全文获取工具 —— LLM 主动调用，由副 AI（audit 模型）忠实总结网页内容后返回。
 */
public class WebFetchTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int FETCH_TIMEOUT_SECONDS = 10;
    private static final int MAX_HTML_BYTES = 500_000;
    private static final int MAX_TEXT_CHARS = 8000;

    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "127.", "10.", "0.",
            "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "169.254."
    );

    @Override
    public String getName() { return "web_fetch"; }

    @Override
    public String getDescription() {
        return "获取网页全文内容并由副AI忠实总结后返回。当用户问『这个链接里写了什么』『帮我看看这篇文章』『链接内容是什么』等需要深入了解链接具体内容的场景时调用。\n" +
               "参数: url(网页URL), query(可选, 用户想知道的具体问题，如『文章的主要观点是什么』)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "要获取内容的完整URL"),
                        "query", Map.of("type", "string", "description", "可选，用户对页面内容的具体问题。如不指定则返回页面内容摘要")
                ),
                "required", List.of("url"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) return "缺少 URL 参数";
        String query = (String) args.get("query");

        // 1. 获取网页文本
        String pageText = fetchPageText(url);
        if (pageText == null) return "无法获取该链接的内容，可能是链接失效或非网页类型。";
        if (pageText.isBlank()) return "该网页没有可提取的文本内容。";

        // 2. 调用副 AI 总结
        String apiKey = BotConfig.getAuditApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "副 AI（审计模型）未配置，无法总结网页内容。";
        }

        return summarizeWithAuditModel(url, pageText, query, apiKey);
    }

    // ---- 网页获取 ----

    private String fetchPageText(String url) {
        if (isBlockedUrl(url)) {
            logger.debug("SSRF blocked: {}", url);
            return null;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; CandyBearBot/1.0)")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
                return null;
            }

            String html = resp.body();
            if (html == null || html.isBlank()) return null;

            return extractText(html);
        } catch (Exception e) {
            logger.debug("网页获取失败: {} — {}", url, e.getMessage());
            return null;
        }
    }

    // ---- 正文提取 ----

    private String extractText(String html) {
        if (html.length() > MAX_HTML_BYTES) {
            html = html.substring(0, MAX_HTML_BYTES);
        }

        // 移除 <script>, <style>, <noscript>, <head> 块
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        html = html.replaceAll("(?is)<head[^>]*>.*?</head>", " ");

        // 移除 HTML 注释
        html = html.replaceAll("<!--.*?-->", " ");

        // 块级元素和 <br> 转为换行，保留段落结构
        html = html.replaceAll("(?is)<br\\s*/?>", "\n");
        html = html.replaceAll("(?is)</?(?:p|div|h[1-6]|li|tr|article|section|header|footer|main|aside|nav|table|ul|ol|dl|hr|blockquote|pre|figure|figcaption)[^>]*>", "\n");

        // 移除所有剩余 HTML 标签
        html = html.replaceAll("<[^>]+>", " ");

        // 解码常见 HTML 实体
        html = html.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("&#x?[0-9a-fA-F]+;", " ");

        // 按行处理：trim 每行，过滤空行，合并多余空行
        String[] lines = html.split("\\n");
        StringBuilder sb = new StringBuilder();
        int consecutiveEmpty = 0;
        for (String line : lines) {
            String trimmed = line.replaceAll("[ \\t\\r]+", " ").trim();
            if (trimmed.isEmpty()) {
                consecutiveEmpty++;
                if (consecutiveEmpty <= 1) {
                    sb.append("\n");
                }
            } else {
                consecutiveEmpty = 0;
                sb.append(trimmed).append("\n");
            }
        }

        String text = sb.toString().trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n...[内容截断]";
        }
        return text;
    }

    // ---- SSRF 防护 ----

    private boolean isBlockedUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return true;
            if ("localhost".equalsIgnoreCase(host) || host.startsWith("[")) return true;
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                return true;
            }
            for (String prefix : BLOCKED_PREFIXES) {
                if (ip.startsWith(prefix)) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    // ---- 副 AI 调用 ----

    private String summarizeWithAuditModel(String url, String pageText, String query, String apiKey) {
        String baseUrl = BotConfig.getAuditBaseUrl();
        String model = BotConfig.getAuditModel();
        int timeoutMs = BotConfig.getAuditTimeoutMs();

        String systemPrompt = """
                你是一个网页内容忠实总结助手。请基于网页原文准确回答用户问题。

                ## 核心规则
                - **绝对不要添加原文中没有的信息**，不要臆测、不要编造
                - 如果原文不包含相关信息，请明确说明『原文未提及此内容』
                - 如果原文存在明显错误或过时信息，可以礼貌地指出，但不要擅自『纠正』
                - 回答时先给出用户问题的直接答案，再补充关键背景

                ## 输出格式
                用中文回复，简洁清晰。如果用户没有指定具体问题，则给出网页的核心内容摘要（200-400字）。
                """;

        String userPrompt = "网页URL: " + url + "\n";
        if (query != null && !query.isBlank()) {
            userPrompt += "用户问题: " + query + "\n\n";
        } else {
            userPrompt += "\n";
        }
        userPrompt += "网页原文:\n" + pageText;

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            body.put("max_tokens", 1024);
            body.put("temperature", 0.1);

            String json = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs + 5000))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = AuditModelUsageService.send(
                    HTTP, request, "web_fetch", model);

            if (response.statusCode() != 200) {
                logger.warn("WebFetch 副AI 返回 {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return "副 AI 调用失败（HTTP " + response.statusCode() + "），请稍后重试。";
            }

            var root = MAPPER.readTree(response.body());
            var choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "副 AI 返回为空，请重试。";
            }

            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                return "副 AI 未产出总结，请重试。";
            }

            logger.info("WebFetch 总结完成: url={} chars={}", url, content.length());
            return "【网页总结 — " + url + "】\n" + content;

        } catch (Exception e) {
            logger.error("WebFetch 副AI 调用失败", e);
            return "副 AI 调用异常: " + e.getMessage();
        }
    }
}
