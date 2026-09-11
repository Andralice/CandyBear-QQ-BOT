package com.start.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.List;

class ConversationContextPolicyTest {

    @Test
    void followUpAndAiCommentDoNotLoadUnrelatedGroupHistory() {
        assertEquals(0, BaiLianService.publicContextLimit(ConversationEvent.FOLLOW_UP));
        assertEquals(0, BaiLianService.publicContextLimit(ConversationEvent.AI_COMMENTED));
        assertEquals(0, BaiLianService.publicContextLimit(ConversationEvent.AWAIT_REPLY));
    }

    @Test
    void explicitAndPassiveRepliesUseSmallBackgroundWindows() {
        assertEquals(6, BaiLianService.publicContextLimit(ConversationEvent.MENTION));
        assertEquals(6, BaiLianService.publicContextLimit(ConversationEvent.PASSIVE_TRIGGER));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.PROBABILISTIC));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.COGNITIVE_CONTINUE));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.COGNITIVE_INTERJECTION));
    }

    @Test
    void excludesCurrentMessagesByIdWithoutRemovingSameTextFromOtherUsers() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("m1", "u1", "甲", "相同内容"));
        history.add(new BaiLianService.PublicMessage("m2", "u2", "乙", "相同内容"));

        String context = BaiLianService.buildPublicGroupContext(history, 5, Set.of("m1"));

        assertFalse(context.contains("甲(u1)：相同内容"));
        assertTrue(context.contains("乙(u2)：相同内容"));
    }

    @Test
    void excludesAllMessagesWithoutLeavingAnEmptyBackgroundBlock() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("m1", "u1", "甲", "第一条"));
        history.add(new BaiLianService.PublicMessage("m2", "u1", "甲", "第二条"));

        String context = BaiLianService.buildPublicGroupContext(history, 5, Set.of("m1", "m2"));

        assertEquals("", context);
    }

    @Test
    void skipsExcludedTailAndKeepsOlderBackgroundMessages() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("old", "u1", "甲", "旧背景"));
        history.add(new BaiLianService.PublicMessage("current", "u2", "乙", "当前消息"));

        String context = BaiLianService.buildPublicGroupContext(history, 1, Set.of("current"));

        assertTrue(context.contains("旧背景"));
        assertFalse(context.contains("当前消息"));
    }

    @Test
    void keepsNewestMessagesWithinCharacterBudget() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("old", "u1", "甲", "旧消息"));
        history.add(new BaiLianService.PublicMessage("new", "u2", "乙", "这是最新消息"));

        String context = BaiLianService.buildPublicGroupContext(history, 2, Set.of(), 60);

        assertTrue(context.contains("这是最新消息"));
        assertFalse(context.contains("旧消息"));
        assertTrue(context.length() <= 60);
    }

    @Test
    void publicMessageKeepsProtocolAddressingMetadata() {
        var message = new BaiLianService.PublicMessage(
                "m3", "u1", "甲", "回复内容", List.of(123L, 456L), 99L);

        assertEquals(List.of(123L, 456L), message.atUserIds);
        assertEquals(99L, message.replyToMessageId);
    }
}
