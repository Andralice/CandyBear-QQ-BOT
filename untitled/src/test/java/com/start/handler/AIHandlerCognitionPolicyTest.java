package com.start.handler;

import com.start.service.ConversationEvent;
import com.start.service.BaiLianService;
import com.start.service.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIHandlerCognitionPolicyTest {

    @Test
    void secondPersonContinuationBypassesProactiveCooldowns() {
        assertTrue(AIHandler.bypassesReplyCooldown(ConversationEvent.COGNITIVE_CONTINUE));
    }

    @Test
    void explicitMentionBypassesGroupAndUserReplyCooldowns() {
        assertTrue(AIHandler.bypassesReplyCooldown(ConversationEvent.MENTION));
    }

    @Test
    void thirdPersonInterjectionKeepsCooldownProtection() {
        assertFalse(AIHandler.bypassesReplyCooldown(ConversationEvent.COGNITIVE_INTERJECTION));
        assertFalse(AIHandler.bypassesReplyCooldown(ConversationEvent.PROBABILISTIC));
    }

    @Test
    void implicitCandidateUsesImmediateBotTurnWithoutExtraDecisionCall() {
        var history = new ArrayList<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("bot-1", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "在呢"));
        history.add(new BaiLianService.PublicMessage("m-1", "u1", "甲", "不怎么"));

        assertTrue(AIHandler.isImplicitGroupCandidate(
                "不怎么", "m-1", null, false, new ConversationState("g", "u1"), history));
    }

    @Test
    void implicitCandidateRecognizesCaptionAfterMediaButNotUnrelatedText() {
        var history = List.of(new BaiLianService.PublicMessage("m-2", "u1", "甲", "ai画的", List.of(), null, "图片、文字"));
        assertTrue(AIHandler.isImplicitGroupCandidate(
                "ai画的", "m-2", null, true, new ConversationState("g", "u1"), history));
        assertFalse(AIHandler.isImplicitGroupCandidate(
                "今天吃饭了", "m-2", null, false, new ConversationState("g", "u1"), history));
    }

    @Test
    void implicitCandidateKeepsImageFollowUpWhenCaptionArrivesAsNextMessage() {
        var history = List.of(
                new BaiLianService.PublicMessage("img-1", "u2", "乙", "", List.of(), null, "图片"),
                new BaiLianService.PublicMessage("text-1", "u3", "丙", "ai画的", List.of(), null, "文字"));
        assertTrue(AIHandler.isImplicitGroupCandidate(
                "ai画的", "text-1", null, false, new ConversationState("g", "u3"), history));
    }

    @Test
    void replyToCandyBearIsCandidateEvenWithoutDirectionalWords() {
        var history = List.of(
                new BaiLianService.PublicMessage("900", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "画好了"),
                new BaiLianService.PublicMessage("901", "u1", "甲", "收到", List.of(), 900L, "文字、回复消息900"));
        assertTrue(AIHandler.isImplicitGroupCandidate(
                "收到", "901", 900L, false, new ConversationState("g", "u1"), history));
    }
}
