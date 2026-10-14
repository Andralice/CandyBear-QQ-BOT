package com.start.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.model.DecisionContext;
import com.start.runtime.RuntimeEvent;
import com.start.service.ConversationEvent;
import com.start.service.GenerationResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDashboardDecisionTraceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String originalHost;
    private String originalToken;
    private String originalPort;
    private WebDashboardListener dashboard;

    @BeforeEach
    void startLoopbackDashboard() {
        originalHost = System.getProperty("dashboard.host");
        originalToken = System.getProperty("dashboard.token");
        originalPort = System.getProperty("dashboard.port");
        System.setProperty("dashboard.host", "127.0.0.1");
        System.setProperty("dashboard.port", "0");
        System.clearProperty("dashboard.token");
        dashboard = new WebDashboardListener();
        dashboard.start();
    }

    @AfterEach
    void stopAndRestore() {
        if (dashboard != null) dashboard.stop();
        restore("dashboard.host", originalHost);
        restore("dashboard.token", originalToken);
        restore("dashboard.port", originalPort);
    }

    @Test
    void decisionsApiExposesMessageIdsButNotMessageBodies() throws Exception {
        DecisionContext context = DecisionContext.of(ConversationEvent.MENTION, 4, 2,
                false, 3, 1, List.of("22", "23"), "11");
        GenerationResult result = GenerationResult.reply("回复文本", 1, 25, 4, 2)
                .withEvidenceMessageIds(List.of("11", "20", "22", "23"));
        new DecisionTraceListener().onEvent(new RuntimeEvent.CommitFinished(
                "437625485", "3524398813", result, 450, context));

        HttpServer server = getServer();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/decisions?limit=50");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode decisions = JSON.readTree(response.body());
        JsonNode decision = decisions.get(decisions.size() - 1);
        assertEquals(List.of("22", "23"), JSON.convertValue(decision.path("inputMessageIds"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("11", decision.path("replyToMessageId").asText());
        assertEquals(4, decision.path("generation").asLong());
        assertEquals(2, decision.path("revision").asLong());
        assertEquals(List.of("11", "20", "22", "23"), JSON.convertValue(decision.path("evidenceMessageIds"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertFalse(response.body().contains("回复文本"));
        assertTrue(response.body().contains("evidenceMessageIds"));
    }

    private HttpServer getServer() throws Exception {
        var field = WebDashboardListener.class.getDeclaredField("server");
        field.setAccessible(true);
        return (HttpServer) field.get(dashboard);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
