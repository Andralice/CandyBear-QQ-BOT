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

    @Test
    void leadingThirdPartyNameDoesNotBecomeFollowUpToCandyBear() {
        var userThreads = new java.util.HashMap<String, BaiLianService.UserThread>();
        userThreads.put("777_10001", new BaiLianService.UserThread(
                System.currentTimeMillis(), "糖果熊：我在呢"));
        var store = new InMemoryConversationStateStore(
                userThreads, new java.util.HashMap<>(),
                new java.util.HashMap<>(), new java.util.HashMap<>());
        AIDatabaseService database = new AIDatabaseService() {
            @Override
            public Map<String, Object> getCandyBearPersonality() {
                return Map.of("activeReply", Map.of("baseProbability", 0.0));
            }
        };

        ConversationInterpreter interpreter = new ConversationInterpreter(store, database);
        var result = interpreter.interpret(
                "777", "10001", "乙", "小鲸鱼，这是你吗？", List.of());

        assertEquals(ConversationEvent.NOTHING, result.event());
    }

    @Test
    void thirdPartySelfIntroductionDoesNotConsumeAwaitOrTriggerProactiveReply() {
        Map<String, BaiLianService.PendingAwait> pendingAwaits = new java.util.HashMap<>();
        pendingAwaits.put("777_10001", new BaiLianService.PendingAwait(
                "777", "10001", "乙", "你怎么看？", "群聊", "session", 60_000));
        var store = new InMemoryConversationStateStore(
                new java.util.HashMap<>(), new java.util.HashMap<>(), pendingAwaits,
                new java.util.HashMap<>());
        AIDatabaseService database = new AIDatabaseService() {
            @Override
            public Map<String, Object> getCandyBearPersonality() {
                return Map.of("activeReply", Map.of("baseProbability", 1.0));
            }

            @Override
            public boolean shouldJoinTopic(String message, String groupId) {
                return true;
            }
        };

        ConversationInterpreter interpreter = new ConversationInterpreter(store, database);
        var result = interpreter.interpret(
                "777", "10001", "乙", "安洁，介绍一下你自己和你的朋友们", List.of());

        assertEquals(ConversationEvent.NOTHING, result.event());
        assertTrue(pendingAwaits.containsKey("777_10001"));
    }

    @Test
    void legacyTopicKeywordCreatesOnlyAnOptionalCandidateForTheMainModel() {
        var store = new InMemoryConversationStateStore(
                new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), new java.util.HashMap<>());
        AIDatabaseService database = new AIDatabaseService() {
            @Override
            public Map<String, Object> getCandyBearPersonality() {
                return Map.of("activeReply", Map.of("baseProbability", 0.0));
            }
        };
        ConversationInterpreter interpreter = new ConversationInterpreter(store, database);

        var addressedToSomeoneUnknown = interpreter.interpret(
                "777", "10001", "乙", "你不许玩游戏", List.of());
        var topicStatement = interpreter.interpret(
                "777", "10003", "丁", "大家都在玩游戏", List.of());
        var openGroupInvitation = interpreter.interpret(
                "777", "10002", "丙", "大家最近推荐什么游戏？", List.of());

        assertEquals(ConversationEvent.PROBABILISTIC, addressedToSomeoneUnknown.event());
        assertEquals(ConversationEvent.PROBABILISTIC, topicStatement.event());
        assertEquals(ConversationEvent.PROBABILISTIC, openGroupInvitation.event());
        for (var candidate : List.of(addressedToSomeoneUnknown, topicStatement, openGroupInvitation)) {
            assertTrue(candidate.event().allowsSilence());
            assertTrue(candidate.prompt().contains("只表示话题可能相关"));
            assertTrue(candidate.prompt().contains("明确是在与其他群友对话"));
            assertTrue(candidate.prompt().contains("开放群聊话题或邀请"));
            assertTrue(candidate.prompt().contains("<NO_REPLY>"));
        }
    }
}
