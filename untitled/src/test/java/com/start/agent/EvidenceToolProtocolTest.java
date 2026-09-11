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
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
