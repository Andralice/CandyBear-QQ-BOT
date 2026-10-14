package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceToolProtocolTest {

    @Test
    void evidenceToolsExposeBoundedFunctionContracts() {
        EvidenceQueryRepository evidence = new EvidenceQueryRepository();
        MessageRepository messages = new MessageRepository();
        List<Tool> tools = List.of(
                new QueryMessageContextTool(evidence, messages, "u", "g", "s"),
                new QueryThreadTool(evidence, messages, "u", "g", "s"),
                new QueryUserTopicHistoryTool(evidence, messages, new ProfileFactRepository(null), "u", "g", "s"),
                new QueryMediaArtifactTool(evidence, messages, "u", "g", "s"),
                new QueryForwardChainTool(evidence, messages, "u", "g", "s"),
                new QueryGroupAtmosphereTool(evidence, messages, "u", "g", "s"),
                new QuerySimilarEventsTool(evidence, messages, "u", "g", "s"));

        assertEquals(7, tools.stream().map(Tool::getName).distinct().count());
        for (Tool tool : tools) {
            Map<String, Object> spec = tool.getFunctionSpec();
            Map<String, Object> function = cast(spec.get("function"));
            Map<String, Object> parameters = cast(function.get("parameters"));
            assertEquals(tool.getName(), function.get("name"));
            assertEquals("object", parameters.get("type"));
            assertFalse(tool.getDescription().isBlank());
        }
        assertTrue(tools.stream().anyMatch(tool -> tool.getDescription().contains("availability_status")));
        Tool context = tools.get(0);
        Map<String, Object> contextParameters = cast(cast(context.getFunctionSpec().get("function")).get("parameters"));
        Map<String, Object> properties = cast(contextParameters.get("properties"));
        assertTrue(properties.containsKey("after_message_id"));
        assertTrue(properties.containsKey("wait_ms"));
        assertTrue(context.getDescription().contains("after_message_id"));
        assertTrue(context.getDescription().contains("wait_ms"));
    }

    @Test
    void messageContextAfterUsesTheNewMessageStreamBranch() {
        EvidenceQueryRepository evidence = new EvidenceQueryRepository() {
            @Override
            public List<Map<String, Object>> messageContextAfter(String groupId, String sessionId,
                                                                  long afterMessageId, int limit) {
                assertEquals("g", groupId);
                assertEquals(41L, afterMessageId);
                assertEquals(8, limit);
                return List.of(Map.of("id", 42L, "provider_message_id", "9001",
                        "user_id", "u2", "content", "新消息"));
            }
        };
        QueryMessageContextTool context = new QueryMessageContextTool(evidence,
                new MessageRepository(), "u", "g", "s");

        String result = context.execute(Map.of("after_message_id", "41", "limit", "8"));

        assertTrue(result.contains("\"kind\":\"message_stream\""));
        assertTrue(result.contains("\"next_cursor\":42"));
        assertTrue(result.contains("新消息"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
