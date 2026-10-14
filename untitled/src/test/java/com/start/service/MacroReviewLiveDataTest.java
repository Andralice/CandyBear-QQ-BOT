package com.start.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit live-model probe; it never writes facts or sends QQ messages. */
@EnabledIfEnvironmentVariable(named = "RUN_MACRO_REVIEW_LIVE_TESTS", matches = "true")
class MacroReviewLiveDataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void realModelReviewsInjectedGroupWindow() throws Exception {
        String rawMessages = System.getenv("MACRO_REVIEW_LIVE_JSON");
        String messageFile = System.getenv("MACRO_REVIEW_LIVE_JSON_FILE");
        if ((rawMessages == null || rawMessages.isBlank()) && messageFile != null && !messageFile.isBlank()) {
            rawMessages = Files.readString(Path.of(messageFile));
        }
        String apiKey = System.getenv("MACRO_REVIEW_API_KEY");
        assertNotNull(rawMessages);
        assertNotNull(apiKey);
        assertFalse(rawMessages.isBlank());
        assertFalse(apiKey.isBlank());
        int timeoutMs = Integer.parseInt(System.getenv().getOrDefault("MACRO_REVIEW_LIVE_TIMEOUT_MS", "120000"));

        List<Map<String, Object>> messages = MAPPER.readValue(rawMessages,
                new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> row : messages) {
            Object id = row.get("id");
            if (id != null && !(id instanceof Number)) {
                row.put("id", Long.parseLong(String.valueOf(id)));
            }
        }
        messages.sort(Comparator.comparingLong(row -> Long.parseLong(String.valueOf(row.get("id")))));
        assertTrue(messages.size() >= 8);

        MacroReviewService service = new MacroReviewService(
                apiKey,
                "https://www.wyy22.com/v1/chat/completions",
                "gpt-5.6-luna",
                timeoutMs,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                null,
                null);

        MacroReviewService.ReviewResult result = service.review("437625485", messages, Map.of());
        assertNotNull(result);
        assertTrue(result.result().isObject());
        assertTrue(result.result().path("observations").isArray());
        var validated = EvidenceAnalysisWorker.validateMacroResult(result.result(), messages);
        assertTrue(validated.path("validation").path("invalid_observations").asInt() >= 0);
        System.out.println("LIVE_MACRO_RESULT=" + result.result());
        System.out.println("LIVE_MACRO_VALIDATION=" + validated.path("validation"));
    }
}
