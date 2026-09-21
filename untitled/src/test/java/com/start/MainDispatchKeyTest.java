package com.start;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainDispatchKeyTest {
    @Test
    void inboundEventsUseStablePerConversationKeys() {
        assertEquals("inbound:group:437625485", Main.dispatchKey("group", 437625485L, 10001L));
        assertEquals("inbound:private:10001", Main.dispatchKey("private", 0L, 10001L));
        assertEquals("inbound:unknown", Main.dispatchKey("notice", 0L, 0L));
    }
}
