package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiLianServiceImageWebSearchTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildsOneIsolatedResponsesRequestPerImageWithInstructionsAndSearch() {
        Map<String, Object> first = BaiLianService.buildImageWebSearchRequestBody(
                "gpt-6-luna", "data:image/png;base64,first");
        Map<String, Object> second = BaiLianService.buildImageWebSearchRequestBody(
                "gpt-6-luna", "data:image/png;base64,second");

        JsonNode firstRequest = JSON.valueToTree(first);
        JsonNode secondRequest = JSON.valueToTree(second);
        assertEquals("gpt-6-luna", firstRequest.path("model").asText());
        assertTrue(firstRequest.path("instructions").asText().contains("独立核验"));
        assertEquals("web_search", firstRequest.path("tools").get(0).path("type").asText());
        assertEquals("required", firstRequest.path("tool_choice").asText());
        assertEquals("data:image/png;base64,first", firstRequest.path("input").get(0)
                .path("content").get(1).path("image_url").asText());
        assertEquals("data:image/png;base64,second", secondRequest.path("input").get(0)
                .path("content").get(1).path("image_url").asText());
        assertEquals(2, firstRequest.path("input").get(0).path("content").size());
        assertFalse(firstRequest.has("messages"));
        assertFalse(secondRequest.has("messages"));
    }

    @Test
    void parsesActualQueriesCitationsAndKeepsOnlyHttpSources() throws Exception {
        JsonNode response = JSON.readTree("""
                {
                  "output_text":"截图来自《星露谷物语》，可见角色位于农场地图。",
                  "output":[
                    {"type":"web_search_call","action":{"query":"星露谷物语农场截图","sources":[
                      {"title":"Stardew Valley Wiki","url":"https://stardewvalleywiki.com/Farm"},
                      {"title":"忽略本地文件","url":"file:///etc/passwd"}
                    ]}},
                    {"type":"message","content":[{"type":"output_text","text":"截图来自《星露谷物语》，可见角色位于农场地图。","annotations":[
                      {"type":"url_citation","title":"Stardew Valley Wiki","url":"https://stardewvalleywiki.com/Farm"}
                    ]}]}
                  ]
                }
                """);

        BaiLianService.ImageSearchEvidence result =
                BaiLianService.parseImageWebSearchResponse(JSON, 2, response);

        assertEquals(2, result.imageIndex());
        assertEquals("截图来自《星露谷物语》，可见角色位于农场地图。", result.summary());
        assertEquals(List.of("星露谷物语农场截图"), result.queries());
        assertEquals(1, result.sources().size());
        assertEquals("https://stardewvalleywiki.com/Farm", result.sources().get(0).url());
        String promptEvidence = BaiLianService.buildImageSearchEvidenceBlock(List.of(result));
        assertTrue(promptEvidence.contains("原图输入 2"));
        assertTrue(promptEvidence.contains("实际搜索词：星露谷物语农场截图"));
        assertTrue(promptEvidence.contains("https://stardewvalleywiki.com/Farm"));
    }

    @Test
    void emptyOrUnusableResponseIsNotPresentedAsVerifiedEvidence() throws Exception {
        assertNull(BaiLianService.parseImageWebSearchResponse(JSON, 1, JSON.readTree("{\"output\":[]}")));
        assertNull(BaiLianService.parseImageWebSearchResponse(JSON, 1,
                JSON.readTree("{\"output_text\":\"模型猜测这是某款游戏\",\"output\":[]}")));
    }

    @Test
    void onlyConfiguredOpenAiGpt6LunaCandidatesCanRunImageWebSearch() {
        var luna = MainModelPool.Candidate.fromBaseUrl(
                "luna", "https://provider.example/v1", "gpt-6-luna", "openai", "KEY", "secret");
        var otherModel = MainModelPool.Candidate.fromBaseUrl(
                "other", "https://provider.example/v1", "gpt-5.6-luna", "openai", "KEY", "secret");
        var claude = MainModelPool.Candidate.fromBaseUrl(
                "claude", "https://provider.example/v1", "gpt-6-luna", "anthropic", "KEY", "secret");
        var noCredential = MainModelPool.Candidate.fromBaseUrl(
                "missing-key", "https://provider.example/v1", "gpt-6-luna", "openai", "KEY", "");

        assertTrue(BaiLianService.supportsImageWebSearch(luna));
        assertFalse(BaiLianService.supportsImageWebSearch(otherModel));
        assertFalse(BaiLianService.supportsImageWebSearch(claude));
        assertFalse(BaiLianService.supportsImageWebSearch(noCredential));
    }

    @Test
    void imageSearchIsAnOptionalSingleImageToolRatherThanAnAutomaticPreflight() {
        var image = new com.start.runtime.conversation.ConversationSession.SearchImage(
                "data:image/png;base64,one", "msg-1", 1, 2);
        var tool = new BaiLianService.SearchCurrentImageTool(null, "group-1", List.of(image));

        JsonNode schema = JSON.valueToTree(tool.getParameters());
        Map<String, Object> request = LlmProtocol.buildRequestBody(LlmProtocol.Type.OPENAI, "gpt-6-luna",
                "system", List.of(), List.of(tool.getFunctionSpec()), 256, "low");

        assertTrue(tool.getDescription().contains("普通表情、闲聊"));
        assertEquals("auto", request.get("tool_choice"));
        assertEquals(List.of(1), JSON.convertValue(schema.path("properties").path("image_index")
                .path("enum"), JSON.getTypeFactory().constructCollectionType(List.class, Integer.class)));
        assertTrue(tool.execute(Map.of("image_index", 2)).contains("超出"));
    }

    @Test
    void imageSearchTimeoutIsBoundedIndependentlyFromMainModelTimeout() {
        assertEquals(8_000, BaiLianService.effectiveImageSearchTimeoutMs(8_000));
        assertEquals(1_000, BaiLianService.effectiveImageSearchTimeoutMs(300));
        assertEquals(30_000, BaiLianService.effectiveImageSearchTimeoutMs(90_000));
    }

    @Test
    void cachedWireFormatIsConvertedBeforeEnteringGroupEvidence() {
        String cached = BaiLianService.CACHED_IMAGE_WEB_EVIDENCE_PREFIX
                + "{\"1\":\"星露谷截图；来源：https://example.com\",\"2\":\"另一张图\"}";

        String evidence = BaiLianService.formatPublicImageDescription(cached);

        assertTrue(evidence.contains("图片1联网核验：星露谷截图"));
        assertTrue(evidence.contains("图片2联网核验：另一张图"));
        assertFalse(evidence.contains("{\"1\""));
    }
}
