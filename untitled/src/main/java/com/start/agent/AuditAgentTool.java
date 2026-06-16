package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

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
 * 排查代理工具 — 把重 token 的日志分析/代码阅读工作交给更便宜的模型。
 * 主 AI 只需调用此工具传入调查目标，子模型自行调用 audit_logs / read_code / shell_exec
 * 进行多轮排查，最后返回精简摘要。
 *
 * 子模型用的 audit.* 配置（默认 claude-sonnet-4-6），token 成本远低于主模型。
 */
public class AuditAgentTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(AuditAgentTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int MAX_ROUNDS = 4;
    private static final int MAX_OUTPUT_CHARS = 3000;

    // 子模型可用的工具
    private final List<Tool> subTools;

    public AuditAgentTool() {
        this.subTools = Arrays.asList(
                new AuditTool(),
                new ReadCodeTool(),
                new ShellTool()
        );
    }

    @Override
    public String getName() { return "investigate"; }

    @Override
    public String getDescription() {
        return "把日志排查/代码阅读等重token工作交给更便宜的AI子模型。子模型会自行调用工具多轮调查，最后返回精简摘要。\n" +
               "参数: query(要调查的问题, 越具体越好, 如\"查最近10条ERROR日志并分析原因\"), " +
               "context(可选, 补充背景, 如\"用户报告私聊功能坏了\")。\n" +
               "注意: 如果子模型返回了需要主AI介入的问题(以[NEED_MAIN]开头), 请单独回应那部分。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string",
                                "description", "要调查的问题。例如: \"查最近20条ERROR日志并分析根因\", \"搜日志中关于NullPointerException的堆栈\", \"读BaiLianService.java第400-500行检查空值处理\""),
                        "context", Map.of("type", "string",
                                "description", "补充背景信息（可选）。如用户报告了什么现象、什么时候开始的等。")
                ),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return "请指定 query（要调查的问题）";

        String context = (String) args.get("context");
        String apiKey = BotConfig.getAuditApiKey();
        String baseUrl = BotConfig.getAuditBaseUrl();
        String model = BotConfig.getAuditModel();
        int timeoutMs = BotConfig.getAuditTimeoutMs();

        if (apiKey == null || apiKey.isBlank()) {
            return "子模型 API Key 未配置（audit.api-key），无法启动排查代理。";
        }

        try {
            // 构建子模型的 system prompt
            String systemPrompt = buildSystemPrompt(context);
            // 构建工具 spec
            List<Map<String, Object>> toolSpecs = subTools.stream()
                    .map(Tool::getFunctionSpec)
                    .collect(java.util.stream.Collectors.toList());

            // 消息列表
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", "调查任务: " + query));

            logger.info("🔍 [AuditAgent] 启动排查: {} (model={})", query, model);

            // 多轮工具调用循环
            String finalAnswer = "";
            for (int round = 0; round < MAX_ROUNDS; round++) {
                String respBody = callApi(baseUrl, apiKey, model, messages, toolSpecs, timeoutMs);
                if (respBody == null) return "子模型 API 调用失败，请稍后重试。";

                JsonNode root = MAPPER.readTree(respBody);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    return "子模型返回为空，请重试。";
                }

                JsonNode message = choices.get(0).path("message");
                boolean hasToolCalls = message.has("tool_calls")
                        && message.get("tool_calls").isArray()
                        && !message.get("tool_calls").isEmpty();

                if (!hasToolCalls) {
                    // 模型给出最终回复
                    String content = message.path("content").asText();
                    if (!content.isBlank()) finalAnswer = content;
                    break;
                }

                // ── 执行工具调用 ──
                // 添加 assistant 消息
                Map<String, Object> asstMsg = new HashMap<>();
                asstMsg.put("role", "assistant");
                String ac = message.path("content").asText();
                asstMsg.put("content", ac.isBlank() || "null".equals(ac) ? null : ac);
                asstMsg.put("tool_calls", MAPPER.convertValue(message.get("tool_calls"), List.class));
                messages.add(asstMsg);

                ArrayNode toolCalls = (ArrayNode) message.get("tool_calls");
                for (JsonNode tc : toolCalls) {
                    String callId = tc.path("id").asText();
                    String toolName = tc.path("function").path("name").asText();
                    String argsJson = tc.path("function").path("arguments").asText();

                    Tool tool = subTools.stream()
                            .filter(t -> t.getName().equals(toolName))
                            .findFirst().orElse(null);

                    String result;
                    if (tool != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tArgs = MAPPER.readValue(argsJson, Map.class);
                            result = tool.execute(tArgs);
                        } catch (Exception e) {
                            result = "参数解析失败: " + e.getMessage();
                        }
                    } else {
                        result = "未知工具: " + toolName;
                    }

                    // 截断过长结果
                    if (result.length() > 4000) {
                        result = result.substring(0, 4000) + "\n...[截断]";
                    }

                    logger.debug("🔧 [AuditAgent] {} → {} chars", toolName, result.length());

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }

                if (round == MAX_ROUNDS - 1) {
                    finalAnswer = "子模型已达最大轮次，未给出最终结论。请主 AI 根据上下文自行判断。";
                }
            }

            // 截断最终输出
            if (finalAnswer.length() > MAX_OUTPUT_CHARS) {
                finalAnswer = finalAnswer.substring(0, MAX_OUTPUT_CHARS) + "\n...[输出截断]";
            }

            logger.info("✅ [AuditAgent] 排查完成，输出 {} chars", finalAnswer.length());
            return finalAnswer.isBlank()
                    ? "子模型排查完毕，但未产出结论。请主 AI 自行判断。"
                    : finalAnswer;

        } catch (Exception e) {
            logger.error("❌ [AuditAgent] 排查异常", e);
            return "排查代理异常: " + e.getMessage();
        }
    }

    private String buildSystemPrompt(String context) {
        return """
                你是一个日志排查助手，负责帮主 AI（糖果熊）调查技术问题。

                ## 可用工具
                - audit_logs: 读取运行日志，支持 errors/warnings/tail/search 四种模式
                - read_code: 读取 Java 源码文件，带行号，支持关键词搜索和行范围
                - shell_exec: 执行只读 shell 命令（cat/head/tail/grep/find/ps/df/free 等）

                ## 工作流程
                1. 先用 audit_logs 查看最近的错误日志
                2. 根据错误定位相关源码（read_code）
                3. 必要时用 shell_exec grep 搜索日志中的特定关键词
                4. 最后给出一份精简的排查摘要

                ## 输出格式
                先用一句话说结论，然后分点列出：
                - 根因: ...
                - 相关文件: ...
                - 建议: ...

                结论尽量 200 字以内。如果发现需要主 AI 介入修改代码的问题，以 [NEED_MAIN] 开头说明。
                """
                + (context != null ? "\n\n调查背景: " + context : "");
    }

    /**
     * 调用 LLM API，返回原始响应体字符串。
     */
    private String callApi(String url, String apiKey, String model,
                           List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools,
                           int timeoutMs) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 1024);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("temperature", 0.1); // 排查需要精确

            String json = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs + 5000))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[AuditAgent] API 返回 {}: {}", response.statusCode(),
                        response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                return null;
            }

            return response.body();
        } catch (Exception e) {
            logger.error("[AuditAgent] API 调用失败", e);
            return null;
        }
    }
}
