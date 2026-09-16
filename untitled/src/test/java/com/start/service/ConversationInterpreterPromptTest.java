package com.start.service;

import com.start.runtime.conversation.InMemoryConversationStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationInterpreterPromptTest {

    @Test
    void passiveEventsReturnSemanticPromptsInsteadOfReadyMadeReplies() {
        ConversationInterpreter interpreter = new ConversationInterpreter(null, null);

        var redbag = interpreter.checkPassiveReactions("[CQ:redbag,id=1]");
        var music = interpreter.checkPassiveReactions("[CQ:music,type=163,id=2]");

        assertTrue(redbag.isPresent());
        assertTrue(music.isPresent());
        assertTrue(redbag.get().contains("红包"));
        assertTrue(music.get().contains("音乐"));
        assertFalse(redbag.get().contains("手慢无"));
        assertFalse(music.get().contains("我也听过"));
    }

    @Test
    void rawProtocolEventRoutesToAiInsteadOfDirectReply() {
        AIDatabaseService database = new AIDatabaseService() {
            @Override
            public Map<String, Object> getCandyBearPersonality() {
                return Map.of("activeReply", Map.of("baseProbability", 0.0));
            }
        };
        ConversationInterpreter interpreter = new ConversationInterpreter(
                new InMemoryConversationStateStore(
                        new java.util.HashMap<>(), new java.util.HashMap<>(),
                        new java.util.HashMap<>(), new java.util.HashMap<>()), database);

        ConversationInterpreter.InterpretResult result = interpreter.interpret(
                "777", "10001", "测试用户", "", "[CQ:redbag,id=1]", List.of());

        assertEquals(ConversationEvent.PASSIVE_TRIGGER, result.event());
        assertTrue(result.needsAi());
        assertFalse(result.isDirect());
    }

}
