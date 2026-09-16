package com.start.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ConversationStateMessageIdTest {

    @Test
    void keepsMessageIdsAlignedWithBufferedMessages() {
        ConversationState state = new ConversationState("123", "456");

        state.addMessage("相同内容", "message-1");
        state.addMessage("相同内容", "message-2");
        state.addMessage("无 ID 的旧调用");

        assertEquals(Arrays.asList("message-1", "message-2", null), state.getPendingMessageIds());
        assertEquals(3, state.getPendingMessages().size());
    }

    @Test
    void keepsImageOwnerAndIndexAcrossBufferedMessages() {
        ConversationState state = new ConversationState("123", "456");

        state.addImageInfo("https://example.com/one.png", "one", "message-1", 1, "alice");
        state.addImageInfo("https://example.com/two.png", "two", "message-2", 1, "bob");

        assertEquals("message-1", state.getImageInfos().get(0).messageId());
        assertEquals(1, state.getImageInfos().get(0).imageIndex());
        assertEquals("alice", state.getImageInfos().get(0).senderId());
        assertEquals("message-2", state.getImageInfos().get(1).messageId());
        assertEquals(1, state.getImageInfos().get(1).imageIndex());
        assertEquals("bob", state.getImageInfos().get(1).senderId());
    }
}
