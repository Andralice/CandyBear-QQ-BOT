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
 * 摘要工具 — 把长文本丢给便宜模型（audit.*）总结，省主模型 token。
 * 适用场景：web_search 结果消化、链接预览内容提炼、长段对话压缩。
 */
public class DigestTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(DigestTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_INPUT_CHARS = 15000;
    private static final int MAX_OUTPUT_CHARS = 1500;

    @Override public String getName() { return "digest"; }

    @Override
    public String getDescription() {
        return "把长文本丢给便宜模型总结，省主模型 token。适合消化 web_search 结果、链接预览内容等。\n" +
               "参数: text(要总结的长文本), instruction(总结方向，如\"提炼3个关键信息\"、\"翻译成中文\")。\n" +
               "返回精简摘要。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string",
                                "description", "要总结的长文本（最多15000字）"),
                        "instruction", Map.of("type", "string",
                                "description", "总结方向。如: 提炼关键信息、翻译成中文、找出和XX相关的内容、判断是否包含XX。默认返回3句话以内的摘要。")
                ),
                "required", List.of("text"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String text = (String) args.get("text");
        if (text == null || text.isBlank()) return "请提供要总结的文本";

        String instruction = (String) args.get("instruction");
        if (text.length() > MAX_INPUT_CHARS) {
            text = text.substring(0, MAX_INPUT_CHARS) + "\n...[输入截断]";
        }

        String apiKey = BotConfig.getAuditApiKey();
        String url = BotConfig.getAuditBaseUrl();
        String model = BotConfig.getAuditModel();
        int timeoutMs = BotConfig.getAuditTimeoutMs();

        if (apiKey == null || apiKey.isBlank()) {
            return "audit.api-key 未配置，digest 不可用。";
        }

        try {
            String systemPrompt = "你是一个高效的文本摘要助手。只提取关键信息，不编造，不废话。";
            String userPrompt = "处理以下文本" + (instruction != null ? "，要求: " + instruction : "，用3句话以内总结要点") + "：\n\n" + text;

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 512);
            body.put("temperature", 0.0);

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs + 5000))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                logger.warn("digest API 返回 {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return "摘要服务暂不可用，请主 AI 自行阅读原文。";
            }

            JsonNode root = MAPPER.readTree(response.body());
            String result = root.path("choices").path(0).path("message").path("content").asText();

            if (result == null || result.isBlank()) {
                return "摘要模型未返回内容，请主 AI 自行阅读原文。";
            }
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...[截断]";
            }

            logger.info("📋 digest: {}→{} chars, {}ms, instruction={}",
                    text.length(), result.length(), elapsed,
                    instruction != null ? instruction.substring(0, Math.min(30, instruction.length())) : "摘要");

            return result;
        } catch (Exception e) {
            logger.error("digest 调用失败", e);
            return "摘要服务异常: " + e.getMessage() + "。请主 AI 自行阅读原文。";
        }
    }
}
