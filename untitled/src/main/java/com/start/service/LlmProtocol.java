package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Protocol-specific request and tool-call translations for the main chat model. */
final class LlmProtocol {

    enum Type {
        OPENAI,
        ANTHROPIC;

        static Type parse(String value) {
            if (value == null) return OPENAI;
            return switch (value.trim().toLowerCase()) {
                case "anthropic", "claude", "claude-native" -> ANTHROPIC;
                case "openai", "openai-compatible" -> OPENAI;
                default -> OPENAI;
            };
        }

        boolean isAnthropic() {
            return this == ANTHROPIC;
        }
    }

    private LlmProtocol() {}

    /** Keep old OpenAI URLs untouched; native Claude can be configured with a /v1 base URL. */
    static String endpoint(String configuredUrl, Type type) {
        if (configuredUrl == null || configuredUrl.isBlank()) return configuredUrl;
        String url = configuredUrl.trim().replaceFirst("/+$", "");
        if (type == Type.OPENAI) {
            return url.endsWith("/v1") ? url + "/chat/completions" : url;
        }
        if (url.endsWith("/messages")) return url;
        if (url.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length()) + "/messages";
        }
        return url + "/messages";
    }

    static String responsesEndpoint(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) return configuredUrl;
        String url = configuredUrl.trim().replaceFirst("/+$", "");
        if (url.endsWith("/responses")) return url;
        if (url.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length()) + "/responses";
        }
        return url + "/responses";
    }

    static HttpRequest.Builder addAuthHeaders(HttpRequest.Builder builder, Type type, String apiKey) {
        String key = apiKey == null ? "" : apiKey;
        if (type == Type.ANTHROPIC) {
            return builder.header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01");
        }
        return builder.header("Authorization", "Bearer " + key);
    }

    /** Convert each Tool independently so Tool.getFunctionSpec() remains the OpenAI contract. */
    static List<Map<String, Object>> buildToolSpecs(Type type, List<Tool> tools,
                                                      Function<String, String> descriptionOverride) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Tool tool : tools) {
            String description = tool.getDescription();
            if (descriptionOverride != null) {
                String override = descriptionOverride.apply(tool.getName());
                if (override != null && !override.isBlank()) description = override;
            }

            if (type == Type.ANTHROPIC) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("name", tool.getName());
                spec.put("description", description);
                spec.put("input_schema", tool.getParameters());
                specs.add(spec);
                continue;
            }

            // Preserve the existing nested OpenAI function schema, including any future fields.
            Map<String, Object> spec = new LinkedHashMap<>(tool.getFunctionSpec());
            Map<String, Object> function = new LinkedHashMap<>();
            Object originalFunction = spec.get("function");
            if (originalFunction instanceof Map<?, ?> original) {
                for (Map.Entry<?, ?> entry : original.entrySet()) {
                    if (entry.getKey() != null) function.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            function.put("name", tool.getName());
            function.put("description", description);
            function.put("parameters", tool.getParameters());
            spec.put("function", function);
            specs.add(spec);
        }
        return specs;
    }

    /** Replace only the primary OpenAI system message; tool call/result ordering stays intact. */
    static void updateSystemPrompt(Type type, List<Map<String, Object>> messages, String prompt) {
        if (type.isAnthropic()) return; // Anthropic uses the top-level system field.
        if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
            messages.set(0, Map.of("role", "system", "content", prompt));
        } else {
            messages.add(0, Map.of("role", "system", "content", prompt));
        }
    }

    static Map<String, Object> buildRequestBody(Type type, String model, String systemPrompt,
                                                  List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> toolSpecs,
                                                  int maxTokens, String reasoningEffort) {
        return buildRequestBody(type, model, systemPrompt, messages, toolSpecs,
                maxTokens, reasoningEffort, false);
    }

    static Map<String, Object> buildRequestBody(Type type, String model, String systemPrompt,
                                                  List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> toolSpecs,
                                                  int maxTokens, String reasoningEffort,
                                                  boolean disableThinking) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (type == Type.ANTHROPIC) {
            body.put("max_tokens", maxTokens);
            if (systemPrompt != null && !systemPrompt.isBlank()) body.put("system", systemPrompt);
            body.put("messages", messages);
            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                body.put("tools", toolSpecs);
                body.put("tool_choice", Map.of("type", "auto"));
            }
            return body;
        }

        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        if (disableThinking) {
            body.put("thinking", Map.of("type", "disabled"));
        } else if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort.trim());
        }
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            body.put("tools", toolSpecs);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    static JsonNode normalizeAssistantResponse(ObjectMapper mapper, JsonNode root, Type type) {
        if (type == Type.OPENAI) {
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalArgumentException("AI 未返回有效 choices");
            }
            JsonNode message = choices.get(0).path("message");
            if (!message.isObject()) throw new IllegalArgumentException("AI 回复缺少 message");
            return message;
        }

        JsonNode content = root.path("content");
        if (!content.isArray()) throw new IllegalArgumentException("Anthropic 回复缺少 content");

        ObjectNode normalized = mapper.createObjectNode();
        String text = extractTextBlocks(content);
        if (text.isEmpty()) normalized.putNull("content");
        else normalized.put("content", text);

        ArrayNode toolCalls = mapper.createArrayNode();
        for (JsonNode block : content) {
            if (!"tool_use".equals(block.path("type").asText())) continue;
            ObjectNode toolCall = mapper.createObjectNode();
            toolCall.put("id", block.path("id").asText());
            ObjectNode function = mapper.createObjectNode();
            function.put("name", block.path("name").asText());
            JsonNode input = block.get("input");
            function.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
            toolCall.set("function", function);
            toolCalls.add(toolCall);
        }
        if (!toolCalls.isEmpty()) normalized.set("tool_calls", toolCalls);
        normalized.set("_anthropic_content", content.deepCopy());
        return normalized;
    }

    /** Providers expose equivalent usage fields under protocol-specific names. */
    static TokenUsage extractUsage(JsonNode root, Type type) {
        JsonNode usage = root == null ? null : root.path("usage");
        if (usage == null || !usage.isObject()) return new TokenUsage(0, 0);
        if (type == Type.ANTHROPIC) {
            return new TokenUsage(
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0));
        }
        return new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    record TokenUsage(int inputTokens, int outputTokens) {}

    static String extractText(JsonNode message) {
        JsonNode content = message == null ? null : message.get("content");
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        return content.isArray() ? extractTextBlocks(content) : content.asText("");
    }

    private static String extractTextBlocks(JsonNode contentBlocks) {
        StringBuilder result = new StringBuilder();
        for (JsonNode block : contentBlocks) {
            if (!"text".equals(block.path("type").asText())) continue;
            String text = block.path("text").asText("");
            if (text.isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(text);
        }
        return result.toString();
    }

    static boolean hasToolCalls(JsonNode message) {
        return message != null && message.path("tool_calls").isArray()
                && !message.path("tool_calls").isEmpty();
    }

    static void appendAssistantToolMessage(ObjectMapper mapper, List<Map<String, Object>> messages,
                                            JsonNode message, Type type) {
        if (type == Type.ANTHROPIC) {
            ObjectNode assistant = mapper.createObjectNode();
            assistant.put("role", "assistant");
            JsonNode nativeContent = message.get("_anthropic_content");
            if (nativeContent != null && nativeContent.isArray()) {
                assistant.set("content", nativeContent.deepCopy());
            } else {
                ArrayNode blocks = mapper.createArrayNode();
                String text = extractText(message);
                if (!text.isEmpty()) {
                    ObjectNode textBlock = mapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                    blocks.add(textBlock);
                }
                for (JsonNode toolCall : message.path("tool_calls")) {
                    ObjectNode use = mapper.createObjectNode();
                    use.put("type", "tool_use");
                    use.put("id", toolCall.path("id").asText());
                    use.put("name", toolCall.path("function").path("name").asText());
                    try {
                        use.set("input", mapper.readTree(
                                toolCall.path("function").path("arguments").asText("{}")));
                    } catch (Exception ignored) {
                        use.set("input", mapper.createObjectNode());
                    }
                    blocks.add(use);
                }
                assistant.set("content", blocks);
            }
            messages.add(mapper.convertValue(assistant, Map.class));
            return;
        }

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) assistant.putNull("content");
        else assistant.set("content", content.deepCopy());
        JsonNode reasoningContent = message.get("reasoning_content");
        if (reasoningContent != null && !reasoningContent.isNull()) {
            assistant.set("reasoning_content", reasoningContent.deepCopy());
        }
        assistant.set("tool_calls", message.get("tool_calls").deepCopy());
        messages.add(mapper.convertValue(assistant, Map.class));
    }

    static Map<String, Object> buildAnthropicToolResult(String callId, String content) {
        return buildAnthropicToolResult(callId, content, List.of());
    }

    static Map<String, Object> buildAnthropicToolResult(String callId, String content,
                                                         List<Tool.ImageAttachment> images) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", callId);
        if (images == null || images.isEmpty()) {
            block.put("content", content);
            return block;
        }
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            contentBlocks.add(Map.of("type", "text", "text", content));
        }
        for (Tool.ImageAttachment image : images) {
            if (image == null || image.dataUri() == null || image.dataUri().isBlank()) continue;
            if (image.label() != null && !image.label().isBlank()) {
                contentBlocks.add(Map.of("type", "text", "text", "image_id=" + image.label()));
            }
            contentBlocks.add(buildImageContent(image.dataUri(), Type.ANTHROPIC));
        }
        block.put("content", contentBlocks);
        return block;
    }

    static Map<String, Object> buildImageContent(String uri, Type protocol) {
        if (protocol != Type.ANTHROPIC || uri == null || !uri.startsWith("data:")) {
            return Map.of("type", "image_url", "image_url", Map.of("url", uri == null ? "" : uri));
        }
        int comma = uri.indexOf(',');
        int semicolon = uri.indexOf(';', 5);
        if (comma <= 5 || semicolon <= 5 || !uri.substring(semicolon + 1, comma).contains("base64")) {
            return Map.of("type", "image_url", "image_url", Map.of("url", uri));
        }
        String mediaType = uri.substring(5, semicolon);
        String data = uri.substring(comma + 1);
        return Map.of("type", "image", "source", Map.of(
                "type", "base64", "media_type", mediaType, "data", data));
    }
}
