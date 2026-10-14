package com.start.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyToCurrentMessageToolTest {
    @Test
    void isAnOptionalMarkerAndDoesNotSendAnything() {
        ReplyToCurrentMessageTool tool = new ReplyToCurrentMessageTool();

        assertEquals("reply_to_current_message", tool.getName());
        assertTrue(tool.getDescription().contains("默认不要调用"));
        assertTrue(tool.getDescription().contains("最近一条已经明确的内容"));
        assertEquals(Map.of("type", "object", "properties", Map.of(),
                "required", java.util.List.of(), "additionalProperties", false), tool.getParameters());
        String result = tool.execute(Map.of());
        assertTrue(result.contains("已标记"));
        assertTrue(result.contains("group_turn_result"));
        assertFalse(result.contains("已发送"));
    }
}
