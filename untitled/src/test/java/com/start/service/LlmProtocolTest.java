package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.agent.Tool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void preservesOpenAiEndpointHeadersToolsAndAssistantTurn() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(JSON.readTree(exchange.getRequestBody()));
            assertEquals("Bearer openai-test", exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("x-api-key"));
            sendJson(exchange, """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"call-1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"上海\\"}"}}
                    ]}}]}
                    """);
        });

        List<Tool> tools = List.of(new StubTool("get_weather", "查询天气"));
        List<Map<String, Object>> specs = LlmProtocol.buildToolSpecs(LlmProtocol.Type.OPENAI, tools, null);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(BaiLianService.buildMessage("system", "规则", List.of()));
        messages.add(BaiLianService.buildMessage("user", "上海天气", List.of()));
        Map<String, Object> body = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.OPENAI, "openai-model", "规则", messages, specs, 128, "low");

        HttpResponse<String> response = send(LlmProtocol.Type.OPENAI,
                LlmProtocol.endpoint(baseUrl + "/v1", LlmProtocol.Type.OPENAI),
                "openai-test", body);
        assertEquals(200, response.statusCode());

        JsonNode request = received.get();
        assertEquals("openai-model", request.path("model").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals("function", request.path("tools").get(0).path("type").asText());
        assertEquals("get_weather", request.path("tools").get(0).path("function").path("name").asText());
        assertFalse(request.path("tools").get(0).has("input_schema"));
        assertEquals("auto", request.path("tool_choice").asText());
        assertEquals("low", request.path("reasoning_effort").asText());

        JsonNode turn = LlmProtocol.normalizeAssistantResponse(
                JSON, JSON.readTree(response.body()), LlmProtocol.Type.OPENAI);
        assertTrue(LlmProtocol.hasToolCalls(turn));
        assertEquals("get_weather", turn.path("tool_calls").get(0).path("function").path("name").asText());
        List<Map<String, Object>> followUpMessages = new ArrayList<>(messages);
        LlmProtocol.appendAssistantToolMessage(JSON, followUpMessages, turn, LlmProtocol.Type.OPENAI);
        followUpMessages.add(Map.of("role", "tool", "tool_call_id", "call-1", "content", "晴"));
        assertEquals("assistant", followUpMessages.get(2).get("role"));
        assertEquals("tool", followUpMessages.get(3).get("role"));
    }

    @Test
    void preservesOpenAiReasoningContentForToolFollowUp() {
        var assistant = JSON.createObjectNode();
        assistant.put("role", "assistant");
        assistant.putNull("content");
        assistant.put("reasoning_content", "先查询天气再回复。");
        var toolCall = assistant.putArray("tool_calls").addObject();
        toolCall.put("id", "call-reasoning");
        toolCall.put("type", "function");
        toolCall.putObject("function").put("name", "get_weather").put("arguments", "{}");

        List<Map<String, Object>> messages = new ArrayList<>();
        LlmProtocol.appendAssistantToolMessage(JSON, messages, assistant, LlmProtocol.Type.OPENAI);

        assertEquals("先查询天气再回复。", messages.get(0).get("reasoning_content"));
    }

    @Test
    void supportsAnthropicToolUseAndToolResultAcrossTwoHttpRounds() throws Exception {
        AtomicInteger round = new AtomicInteger();
        List<JsonNode> received = new ArrayList<>();
        server.createContext("/v1/messages", exchange -> {
            synchronized (received) {
                received.add(JSON.readTree(exchange.getRequestBody()));
            }
            assertEquals("native-test", exchange.getRequestHeaders().getFirst("x-api-key"));
            assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            if (round.getAndIncrement() == 0) {
                sendJson(exchange, """
                        {"id":"msg-1","type":"message","role":"assistant","content":[
                        {"type":"tool_use","id":"toolu-1","name":"get_weather","input":{"city":"上海"}}
                        ],"stop_reason":"tool_use"}
                        """);
            } else {
                sendJson(exchange, """
                        {"id":"msg-2","type":"message","role":"assistant","content":[
                        {"type":"text","text":"上海今天晴天。"}
                        ],"stop_reason":"end_turn"}
                        """);
            }
        });

        List<Tool> tools = List.of(new StubTool("get_weather", "查询天气"));
        List<Map<String, Object>> specs = LlmProtocol.buildToolSpecs(
                LlmProtocol.Type.ANTHROPIC, tools, name -> "优先查询实时天气");
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(BaiLianService.buildMessage("user", "上海天气", List.of()));
        Map<String, Object> firstBody = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.ANTHROPIC, "claude-model", "规则", messages, specs, 128, "low");

        HttpResponse<String> firstResponse = send(LlmProtocol.Type.ANTHROPIC,
                LlmProtocol.endpoint(baseUrl + "/v1", LlmProtocol.Type.ANTHROPIC),
                "native-test", firstBody);
        assertEquals(200, firstResponse.statusCode());

        JsonNode firstTurn = LlmProtocol.normalizeAssistantResponse(
                JSON, JSON.readTree(firstResponse.body()), LlmProtocol.Type.ANTHROPIC);
        assertTrue(LlmProtocol.hasToolCalls(firstTurn));
        assertEquals("上海", JSON.readTree(firstTurn.path("tool_calls").get(0)
                .path("function").path("arguments").asText()).path("city").asText());

        List<Map<String, Object>> followUpMessages = new ArrayList<>(messages);
        LlmProtocol.appendAssistantToolMessage(JSON, followUpMessages, firstTurn, LlmProtocol.Type.ANTHROPIC);
        followUpMessages.add(Map.of(
                "role", "user",
                "content", List.of(LlmProtocol.buildAnthropicToolResult("toolu-1", "晴，25度"))));
        Map<String, Object> secondBody = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.ANTHROPIC, "claude-model", "规则", followUpMessages, specs, 128, "low");

        HttpResponse<String> secondResponse = send(LlmProtocol.Type.ANTHROPIC,
                LlmProtocol.endpoint(baseUrl + "/v1", LlmProtocol.Type.ANTHROPIC),
                "native-test", secondBody);
        assertEquals(200, secondResponse.statusCode());

        JsonNode firstRequest = received.get(0);
        assertEquals("规则", firstRequest.path("system").asText());
        assertEquals("string", firstRequest.path("tools").get(0).path("input_schema")
                .path("properties").path("city").path("type").asText());
        assertEquals("get_weather", firstRequest.path("tools").get(0).path("name").asText());
        assertEquals("优先查询实时天气", firstRequest.path("tools").get(0).path("description").asText());
        assertFalse(firstRequest.has("reasoning_effort"));
        assertEquals("auto", firstRequest.path("tool_choice").path("type").asText());

        JsonNode secondRequest = received.get(1);
        assertEquals(3, secondRequest.path("messages").size());
        assertEquals("assistant", secondRequest.path("messages").get(1).path("role").asText());
        assertEquals("tool_use", secondRequest.path("messages").get(1).path("content").get(0).path("type").asText());
        assertEquals("user", secondRequest.path("messages").get(2).path("role").asText());
        assertEquals("tool_result", secondRequest.path("messages").get(2).path("content").get(0).path("type").asText());
        assertEquals("toolu-1", secondRequest.path("messages").get(2).path("content").get(0).path("tool_use_id").asText());

        JsonNode finalTurn = LlmProtocol.normalizeAssistantResponse(
                JSON, JSON.readTree(secondResponse.body()), LlmProtocol.Type.ANTHROPIC);
        assertEquals("上海今天晴天。", LlmProtocol.extractText(finalTurn));
    }

    @Test
    void normalizesKnownProtocolNamesAndEndpoints() {
        assertEquals(LlmProtocol.Type.OPENAI, LlmProtocol.Type.parse(null));
        assertEquals(LlmProtocol.Type.OPENAI, LlmProtocol.Type.parse("openai"));
        assertEquals(LlmProtocol.Type.OPENAI, LlmProtocol.Type.parse("openai-compatible"));
        assertEquals(LlmProtocol.Type.ANTHROPIC, LlmProtocol.Type.parse("claude-native"));
        assertEquals(baseUrl + "/v1/chat/completions",
                LlmProtocol.endpoint(baseUrl + "/v1", LlmProtocol.Type.OPENAI));
        assertEquals(baseUrl + "/v1/messages",
                LlmProtocol.endpoint(baseUrl + "/v1", LlmProtocol.Type.ANTHROPIC));
        assertEquals(baseUrl + "/v1/messages",
                LlmProtocol.endpoint(baseUrl + "/v1/chat/completions", LlmProtocol.Type.ANTHROPIC));
        assertEquals(baseUrl + "/v1/responses", LlmProtocol.responsesEndpoint(baseUrl + "/v1"));
        assertEquals(baseUrl + "/v1/responses", LlmProtocol.responsesEndpoint(baseUrl + "/v1/chat/completions"));
        assertEquals(baseUrl + "/v1/responses", LlmProtocol.responsesEndpoint(baseUrl + "/v1/responses"));
    }

    @Test
    void serializesToolImagesForOpenAiAndAnthropicWithoutFlatteningThemToText() {
        Tool.ImageAttachment image = new Tool.ImageAttachment("42:1", "data:image/png;base64,AQID");

        Map<String, Object> openAiImage = LlmProtocol.buildImageContent(image.dataUri(), LlmProtocol.Type.OPENAI);
        assertEquals("image_url", openAiImage.get("type"));
        assertEquals(image.dataUri(), ((Map<?, ?>) openAiImage.get("image_url")).get("url"));

        Map<String, Object> anthropicResult = LlmProtocol.buildAnthropicToolResult(
                "toolu-image", "已选中原图", List.of(image));
        List<?> blocks = (List<?>) anthropicResult.get("content");
        assertEquals("tool_result", anthropicResult.get("type"));
        assertEquals("toolu-image", anthropicResult.get("tool_use_id"));
        assertEquals("text", ((Map<?, ?>) blocks.get(0)).get("type"));
        assertEquals("image_id=42:1", ((Map<?, ?>) blocks.get(1)).get("text"));
        assertEquals("image", ((Map<?, ?>) blocks.get(2)).get("type"));
        Map<?, ?> source = (Map<?, ?>) ((Map<?, ?>) blocks.get(2)).get("source");
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        assertEquals("AQID", source.get("data"));
    }

    @Test
    void openAiBodyOmitsReasoningEffortWhenRawPathDoesNotRequestIt() {
        Map<String, Object> body = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.OPENAI, "openai-model", "", List.of(), List.of(), 128, "");

        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test
    void disablesThinkingForAConfiguredOpenAiRouteWithoutGlobalReasoningSettings() {
        Map<String, Object> body = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.OPENAI, "deepseek-flash", "", List.of(), List.of(),
                8, "high", true);

        JsonNode request = JSON.valueToTree(body);
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test
    void extractsProtocolSpecificTokenUsage() throws Exception {
        LlmProtocol.TokenUsage openAi = LlmProtocol.extractUsage(
                JSON.readTree("{\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":7}}"),
                LlmProtocol.Type.OPENAI);
        LlmProtocol.TokenUsage anthropic = LlmProtocol.extractUsage(
                JSON.readTree("{\"usage\":{\"input_tokens\":19,\"output_tokens\":5}}"),
                LlmProtocol.Type.ANTHROPIC);

        assertEquals(new LlmProtocol.TokenUsage(21, 7), openAi);
        assertEquals(new LlmProtocol.TokenUsage(19, 5), anthropic);
    }

    private HttpResponse<String> send(LlmProtocol.Type type, String url, String apiKey,
                                      Map<String, Object> body) throws Exception {
        HttpRequest request = LlmProtocol.addAuthHeaders(HttpRequest.newBuilder()
                        .uri(URI.create(url)), type, apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record StubTool(String name, String description) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("city", Map.of("type", "string"));
            return Map.of("type", "object", "properties", properties);
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "晴";
        }
    }
}
