package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupDialogueAnalyzerProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
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
    void inheritedMainEndpointUsesAnthropicTransportAndToolUse() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        List<String> apiKeys = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/v1/messages", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String toolName = request.path("tools").path(0).path("name").asText();
            sendJson(exchange, toolName.equals("group_relation")
                    ? relationAnthropicResponse()
                    : opportunityAnthropicResponse());
        });

        GroupDialogueAnalyzer analyzer = new GroupDialogueAnalyzer(
                GroupCognitionMode.SHADOW, Set.of(123L), "native-key", baseUrl + "/v1",
                "claude-model", 5_000, "", "response_format", "", "anthropic", true);

        GroupDialoguePlan plan = analyzer.analyze(List.of(
                new BaiLianService.PublicMessage("m1", "u2", "小雨", "大家卡住了")));

        assertTrue(plan.shouldReply());
        assertEquals(GroupDialoguePlan.EntryRole.THIRD_PERSON_INTERJECTION, plan.entryRole());
        assertEquals("u2", plan.targetUserId());
        assertEquals(38, plan.tokensUsed());
        assertEquals(2, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            JsonNode request = requests.get(i);
            assertEquals("native-key", apiKeys.get(i));
            assertNull(authorizationHeaders.get(i));
            assertFalse(request.has("response_format"));
            assertTrue(request.path("system").asText().contains("群聊"));
            assertEquals("user", request.path("messages").get(0).path("role").asText());
            assertEquals("tool", request.path("tool_choice").path("type").asText());
            assertTrue(request.path("tools").get(0).has("input_schema"));
        }
    }

    @Test
    void existingOpenAiAnalyzerTransportRemainsUnchanged() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
        List<String> apiKeys = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            boolean relation = request.path("messages").get(0).path("content").asText()
                    .contains("话语关系");
            sendJson(exchange, relation ? relationOpenAiResponse() : opportunityOpenAiResponse());
        });

        GroupDialogueAnalyzer analyzer = new GroupDialogueAnalyzer(
                GroupCognitionMode.SHADOW, Set.of(123L), "openai-key", baseUrl + "/v1/chat/completions",
                "openai-model", 5_000);

        GroupDialoguePlan plan = analyzer.analyze(List.of(
                new BaiLianService.PublicMessage("m1", "u2", "小雨", "大家卡住了")));

        assertTrue(plan.shouldReply());
        assertEquals(2, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            JsonNode request = requests.get(i);
            assertEquals("openai-model", request.path("model").asText());
            assertEquals("Bearer openai-key", authorizationHeaders.get(i));
            assertNull(apiKeys.get(i));
            assertTrue(request.path("response_format").has("json_schema"));
            assertNull(request.get("tool_choice"));
            assertEquals("system", request.path("messages").get(0).path("role").asText());
        }
    }

    private static String relationAnthropicResponse() {
        return """
                {"type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu-r","name":"group_relation","input":{
                  "latest_speaker_id":"u2","latest_addressee_ids":[],"latest_topic":"卡住","reply_chain":"u2在群里发言",
                  "bot_role":"OUTSIDER","bot_has_next_turn":false,"next_turn_target_user_id":"","next_turn_target_message_id":"",
                  "turn_reason":"没有直接指向糖果熊","forbidden_assumptions":[]}}],"usage":{"input_tokens":10,"output_tokens":8}}
                """;
    }

    private static String opportunityAnthropicResponse() {
        return """
                {"type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu-o","name":"group_opportunity","input":{
                  "open_need":"需要具体帮助","humans_explicitly_stalled":true,"already_being_handled":false,
                  "concrete_contribution":"给出可执行建议","contribution_confidence":0.9,"intrusion_risk":"LOW",
                  "should_interject":true,"beneficiary_user_id":"u2","reason":"群友明确卡住"}}],"usage":{"input_tokens":11,"output_tokens":9}}
                """;
    }

    private static String relationOpenAiResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"latest_speaker_id\\\":\\\"u2\\\",\\\"latest_addressee_ids\\\":[],\\\"latest_topic\\\":\\\"卡住\\\",\\\"reply_chain\\\":\\\"u2在群里发言\\\",\\\"bot_role\\\":\\\"OUTSIDER\\\",\\\"bot_has_next_turn\\\":false,\\\"next_turn_target_user_id\\\":\\\"\\\",\\\"next_turn_target_message_id\\\":\\\"\\\",\\\"turn_reason\\\":\\\"没有直接指向糖果熊\\\",\\\"forbidden_assumptions\\\":[]}\"}}]}";
    }

    private static String opportunityOpenAiResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"open_need\\\":\\\"需要具体帮助\\\",\\\"humans_explicitly_stalled\\\":true,\\\"already_being_handled\\\":false,\\\"concrete_contribution\\\":\\\"给出可执行建议\\\",\\\"contribution_confidence\\\":0.9,\\\"intrusion_risk\\\":\\\"LOW\\\",\\\"should_interject\\\":true,\\\"beneficiary_user_id\\\":\\\"u2\\\",\\\"reason\\\":\\\"群友明确卡住\\\"}\"}}]}";
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
