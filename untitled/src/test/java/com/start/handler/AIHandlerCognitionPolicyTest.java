package com.start.handler;

import com.start.service.ConversationEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIHandlerCognitionPolicyTest {

    @Test
    void secondPersonContinuationBypassesProactiveCooldowns() {
        assertTrue(AIHandler.bypassesReplyCooldown(ConversationEvent.COGNITIVE_CONTINUE));
    }

    @Test
    void thirdPersonInterjectionKeepsCooldownProtection() {
        assertFalse(AIHandler.bypassesReplyCooldown(ConversationEvent.COGNITIVE_INTERJECTION));
        assertFalse(AIHandler.bypassesReplyCooldown(ConversationEvent.PROBABILISTIC));
    }
}
