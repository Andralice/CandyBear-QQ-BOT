package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 搜索+摘要一体工具 — 主 AI 只拿到精简结论，永远看不到原始搜索结果。
 *
 * 流程: web_search 搜 → 便宜模型总结 → 仅返回摘要给主 AI
 * 原始搜索结果全程不进主 AI 上下文，省大量 token。
 */
public class SearchDigestTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SearchDigestTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_RESULT_CHARS = 4000;
    private static final int MAX_OUTPUT_CHARS = 1200;

    private final WebSearchTool webSearch;

    public SearchDigestTool() {
        this.webSearch = new WebSearchTool();
    }

    @Override public String getName() { return "search_digest"; }

    @Override
    public String getDescription() {
        return "搜索并直接总结，主AI看不到原始结果，省token。适合需要了解事实但不需要原始链接的场景。\n" +
               "参数: query(搜索词), instruction(总结方向，如\"用中文3句话概括\"、\"只找出和XX相关的\")。\n" +
               "注意: 如果用户明确要求看原始链接或详细内容，请用 web_search 而非本工具。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string",
                                "description", "搜索关键词"),
                        "instruction", Map.of("type", "string",
                                "description", "总结方向。如: 提炼关键信息、用中文回答、只找2026年的、判断是否有负面新闻。默认简洁总结。")
                ),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return "请指定搜索词";

        String instruction = (String) args.get("instruction");

        String apiKey = BotConfig.getAuditApiKey();
        String url = BotConfig.getAuditBaseUrl();
        String model = BotConfig.getAuditModel();
        int timeoutMs = BotConfig.getAuditTimeoutMs();

        if (apiKey == null || apiKey.isBlank()) {
            // 回退：直接搜，主 AI 自己看
            logger.warn("audit.api-key 未配置，search_digest 回退为普通搜索");
            return webSearch.execute(Map.of("query", query));
        }

        try {
            // Step 1: 搜索
            String rawResults = webSearch.execute(Map.of("query", query));
            if (rawResults.length() > MAX_RESULT_CHARS) {
                rawResults = rawResults.substring(0, MAX_RESULT_CHARS) + "\n...[截断]";
            }
            logger.debug("search_digest 搜索结果 {} chars", rawResults.length());

            // Step 2: 便宜模型总结
            String summary = summarize(rawResults, instruction, query, apiKey, url, model, timeoutMs);
            logger.info("📋 search_digest: query={}, raw={}→summary={} chars",
                    query, rawResults.length(), summary.length());
            return summary;

        } catch (Exception e) {
            logger.error("search_digest 异常", e);
            return "搜索摘要服务异常: " + e.getMessage();
        }
    }

    private String summarize(String rawText, String instruction, String query,
                             String apiKey, String url, String model, int timeoutMs) {
        try {
            String systemPrompt = "你是一个高效的信息助理。阅读搜索结果，提炼出最相关、最准确的信息。不编造，不废话。";
            String userPrompt = "关于「" + query + "」的搜索结果：\n\n" + rawText + "\n\n" +
                    (instruction != null
                            ? "请按以下要求总结: " + instruction
                            : "请用3~5句话概括关键信息，忽略广告和不相关的内容。");

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 512);
            body.put("temperature", 0.0);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs + 5000))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // 回退：直接返回原始搜索结果
                return "[便宜模型不可用，以下为原始搜索结果]\n\n" + rawText;
            }

            JsonNode root = MAPPER.readTree(response.body());
            String result = root.path("choices").path(0).path("message").path("content").asText();

            if (result == null || result.isBlank()) {
                return "[便宜模型未返回内容]\n\n" + rawText;
            }
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...[截断]";
            }

            return result;

        } catch (Exception e) {
            logger.warn("search_digest 便宜模型调用失败，回退原始结果: {}", e.getMessage());
            return "[便宜模型不可用，以下为原始搜索结果]\n\n" + rawText;
        }
    }
}
