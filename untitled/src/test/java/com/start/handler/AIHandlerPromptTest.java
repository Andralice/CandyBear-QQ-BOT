package com.start.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIHandlerPromptTest {

    @Test
    void generationUsesEventPromptOnceAndLatestBufferAfterRegeneration() {
        assertEquals("事件提示", AIHandler.composeGenerationPrompt("", "事件提示", 0));
        assertEquals("可见文字", AIHandler.composeGenerationPrompt("可见文字", "旧事件提示", 1));

        String combined = AIHandler.composeGenerationPrompt("红包来了", "事件提示", 0);
        assertTrue(combined.startsWith("事件提示"));
        assertTrue(combined.contains("红包来了"));
    }
}
