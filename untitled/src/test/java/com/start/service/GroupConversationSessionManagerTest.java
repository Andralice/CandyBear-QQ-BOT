package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupConversationSessionManagerTest {

    @Test
    void explicitMentionOpensSessionAndReplyKeepsBotTurnEvidence() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();

        GroupConversationSession.Decision opened = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "糖果熊，你在吗？", true, false, false, false));
        assertEquals(GroupConversationSession.Action.OPEN, opened.action());
        assertEquals(GroupConversationSession.BotPosition.DIRECT_TARGET, opened.botPosition());

        manager.markBotReplyForMessage("g", "m-1", "", System.currentTimeMillis() + 1);
        GroupConversationSession.Decision continued = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-2", "u-1", "嗯，我想问个事", false, false, false, false));
        assertEquals(GroupConversationSession.Action.CONTINUE, continued.action());
        assertEquals(GroupConversationSession.BotPosition.SECOND_PERSON_CONTINUATION, continued.botPosition());
    }

    @Test
    void otherMemberConversationDoesNotOpenCandyBearSession() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();

        GroupConversationSession.Decision decision = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-2", "m-1", "u-2", "小明，你怎么看？", false, false, true, false));
        assertEquals(GroupConversationSession.Action.OBSERVE, decision.action());
        assertEquals(GroupConversationSession.BotPosition.OTHER_MEMBER_CONVERSATION, decision.botPosition());
        assertEquals(GroupConversationSession.LifecycleState.OBSERVING, decision.state());
    }

    @Test
    void parallelThreadsRemainIndependent() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        manager.observe(new GroupConversationSession.Observation(
                "g", "a", "a-1", "u-1", "糖果熊，帮我看看", true, false, false, false));
        manager.observe(new GroupConversationSession.Observation(
                "g", "b", "b-1", "u-2", "糖果熊，另一个问题", true, false, false, false));

        List<GroupConversationSession> sessions = manager.activeForGroup("g");
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(s -> s.threadId().equals("a")));
        assertTrue(sessions.stream().anyMatch(s -> s.threadId().equals("b")));
    }

    @Test
    void candidateExpiresWithoutBecomingLongTermConversation() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        GroupConversationSession.Decision candidate = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "大家早上好", false, false, false, false, 1_000L));
        assertEquals(GroupConversationSession.Action.CANDIDATE, candidate.action());

        assertEquals(1, manager.expire(91_001L));
        assertEquals(GroupConversationSession.LifecycleState.CLOSED,
                manager.get("g", "thread-1").orElseThrow().lifecycleState());
    }

    @Test
    void imageWithoutTextRemainsUnknown() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        GroupConversationSession.Decision decision = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-image", "m-image", "u-1", "", false, false, false, true));
        assertEquals(GroupConversationSession.BotPosition.UNKNOWN, decision.botPosition());
        assertEquals("unresolved_image_context", decision.reason());
    }

    @Test
    void continuationDoesNotLeakPastAnotherHumanTurn() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "糖果熊，你在吗？", true, false, false, false));
        manager.markBotReplyForMessage("g", "m-1", "", System.currentTimeMillis() + 1);
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-2", "u-1", "我想问个事", false, false, false, false));

        GroupConversationSession.Decision later = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-3", "u-2", "我也想说", false, false, false, false));
        assertEquals(GroupConversationSession.Action.OBSERVE, later.action());
        assertEquals(GroupConversationSession.BotPosition.TOPIC_OBSERVER, later.botPosition());
    }

    @Test
    void explicitResolutionPhraseClosesOnlyTheCurrentThread() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "糖果熊，帮我看看", true, false, false, false));

        GroupConversationSession.Decision closed = manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-2", "u-1", "谢谢，解决了", false, false, false, false));
        assertEquals(GroupConversationSession.Action.CLOSE, closed.action());
        assertEquals(GroupConversationSession.LifecycleState.CLOSED, closed.state());
        assertEquals("explicit_closure_phrase", manager.get("g", "thread-1").orElseThrow().closeReason());
    }
}
