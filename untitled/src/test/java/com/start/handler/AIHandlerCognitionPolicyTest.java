package com.start.handler;

import com.start.service.ConversationEvent;
import com.start.service.BaiLianService;
import com.start.service.ConversationState;
import com.start.service.ConversationInterpreter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void bareQuestionMarkAfterAnotherMembersImageIsNotAnImplicitCandidate() {
        var history = List.of(
                new BaiLianService.PublicMessage("image", "u2", "乙", "", List.of(), null, "图片"),
                new BaiLianService.PublicMessage("question", "u3", "丙", "？"));
        var state = new ConversationState("g", "u3");
        state.addMessage("？", "question");

        assertFalse(AIHandler.isImplicitGroupCandidate(
                "？", "question", null, false, state, history));
    }

    @Test
    void bareQuestionAfterBotNeedsProtocolReplyEvidence() {
        String botId = String.valueOf(com.start.config.BotConfig.getBotQq());
        var history = List.of(
                new BaiLianService.PublicMessage("1", botId, "糖果熊", "你觉得呢？"),
                new BaiLianService.PublicMessage("question", "u1", "甲", "？"));

        assertFalse(AIHandler.isImplicitGroupCandidate(
                "？", "question", null, false, new ConversationState("g", "u1"), history));
        assertTrue(AIHandler.isImplicitGroupCandidate(
                "？", "question", 1L, false, new ConversationState("g", "u1"), history));
    }

    @Test
    void bareQuestionAfterSharedContentDoesNotUseOldSameUserBufferAsAddressProof() {
        var history = List.of(
                new BaiLianService.PublicMessage("share", "u2", "乙", "赛博朋克：边缘行者 2，吴彦祖配音"),
                new BaiLianService.PublicMessage("first", "u1", "甲", "还没做完"),
                new BaiLianService.PublicMessage("question", "u1", "甲", "？"));
        var state = new ConversationState("g", "u1");
        state.addMessage("还没做完", "first");
        state.addMessage("？", "question");

        assertFalse(AIHandler.isImplicitGroupCandidate(
                "？", "question", null, false, state, history));
    }

    @Test
    void splitShortFollowUpRemainsEligibleWhenItsContiguousTurnStartsAfterCandyBear() {
        var history = List.of(
                new BaiLianService.PublicMessage("bot", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "你觉得呢？"),
                new BaiLianService.PublicMessage("first", "u1", "甲", "嗯，我想想"),
                new BaiLianService.PublicMessage("question", "u1", "甲", "然后呢？"));
        var state = new ConversationState("g", "u1");
        state.addMessage("嗯，我想想", "first");
        state.addMessage("然后呢？", "question");

        assertTrue(AIHandler.isImplicitGroupCandidate(
                "然后呢？", "question", null, false, state, history));
    }

    @Test
    void bareQuestionDoesNotEnterSplitFollowUpCandidatePath() {
        var history = List.of(
                new BaiLianService.PublicMessage("bot", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "你觉得呢？"),
                new BaiLianService.PublicMessage("first", "u1", "甲", "嗯，我想想"),
                new BaiLianService.PublicMessage("question", "u1", "甲", "？"));
        var state = new ConversationState("g", "u1");
        state.addMessage("嗯，我想想", "first");
        state.addMessage("？", "question");

        assertFalse(AIHandler.isImplicitGroupCandidate(
                "？", "question", null, false, state, history));
    }

    @Test
    void splitShortFollowUpIsNotEligibleAfterAnotherMemberTakesTheTurn() {
        var history = List.of(
                new BaiLianService.PublicMessage("bot", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "你觉得呢？"),
                new BaiLianService.PublicMessage("first", "u1", "甲", "嗯"),
                new BaiLianService.PublicMessage("other", "u2", "乙", "我也想说"),
                new BaiLianService.PublicMessage("question", "u1", "甲", "？"));
        var state = new ConversationState("g", "u1");
        state.addMessage("嗯", "first");
        state.addMessage("？", "question");

        assertFalse(AIHandler.isImplicitGroupCandidate(
                "？", "question", null, false, state, history));
    }

    @Test
    void sameSpeakerImageFollowUpAfterAddressingAnotherMemberStaysInHumanThread() {
        var history = List.of(
                new BaiLianService.PublicMessage("image", "3524398813", "甲", "", List.of(), null, "图片"),
                new BaiLianService.PublicMessage("reply", "2590355304", "乙", "这张图你发的吗？",
                        List.of(3524398813L), null, "文字"),
                new BaiLianService.PublicMessage("follow-up", "2590355304", "乙", "这张图是我看到的网图"));
        var withoutHumanAddressEvidence = List.of(
                history.get(0),
                new BaiLianService.PublicMessage("reply", "2590355304", "乙", "这张图你发的吗？"),
                history.get(2));

        assertTrue(AIHandler.isImplicitGroupCandidate(
                "这张图是我看到的网图", "follow-up", null, false,
                new ConversationState("437625485", "2590355304"), withoutHumanAddressEvidence));
        assertFalse(AIHandler.isImplicitGroupCandidate(
                "这张图是我看到的网图", "follow-up", null, false,
                new ConversationState("437625485", "2590355304"), history));
    }

    @Test
    void replyToCandyBearIsCandidateEvenWithoutDirectionalWords() {
        var history = List.of(
                new BaiLianService.PublicMessage("900", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "画好了"),
                new BaiLianService.PublicMessage("901", "u1", "甲", "收到", List.of(), 900L, "文字、回复消息900"));
        assertTrue(AIHandler.isImplicitGroupCandidate(
                "收到", "901", 900L, false, new ConversationState("g", "u1"), history));
    }

    @Test
    void realGroupMemberReplyDoesNotBecomeCandyBearCandidateJustBecauseItQuotesSomeone() {
        // 2026-09-23 09:55, group 437625485; IDs are OneBot provider IDs, not DB IDs.
        var history = List.of(
                new BaiLianService.PublicMessage("309720350", "member-a", "甲", "而且没啥玩的"),
                new BaiLianService.PublicMessage("359977521", "member-b", "乙", "emm"),
                new BaiLianService.PublicMessage("574016084", "member-c", "丙",
                        "星露谷物语种地", List.of(), 309720350L, "文字、回复消息309720350"));

        assertFalse(AIHandler.isImplicitGroupCandidate("星露谷物语种地", "574016084",
                309720350L, false, new ConversationState("437625485", "member-c"), history));
    }

    @Test
    void leadingThirdPartyNameOwnsFollowingYou() {
        var history = List.of(
                new BaiLianService.PublicMessage("m-1", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "在吗"),
                new BaiLianService.PublicMessage("m-2", "member-b", "乙", "小鲸鱼，这是你吗？"));

        assertFalse(AIHandler.isImplicitGroupCandidate("小鲸鱼，这是你吗？", "m-2", null,
                false, new ConversationState("g", "member-b"), history));
        assertTrue(AIHandler.isImplicitGroupCandidate("糖果熊，这是你吗？", "m-2", null,
                false, new ConversationState("g", "member-b"), history));
    }

    @Test
    void explicitBotVocativeIsEligibleWithoutAnImmediateBotMessage() {
        var history = List.of(
                new BaiLianService.PublicMessage("human", "member-a", "甲", "今天群里挺热闹"),
                new BaiLianService.PublicMessage("current", "member-b", "乙", "糖果熊，你觉得呢？"));

        assertTrue(AIHandler.isImplicitGroupCandidate("糖果熊，你觉得呢？", "current", null,
                false, new ConversationState("g", "member-b"), history));
    }

    @Test
    void mentioningCandyBearInTheMiddleIsNotProofThatTheMessageAddressesIt() {
        assertFalse(AIHandler.hasBotTurnEvidenceOrUnresolvedReply(
                "我想起糖果熊上次说过这个", "m-3", null, List.of(), List.of()));
        assertTrue(AIHandler.hasBotTurnEvidenceOrUnresolvedReply(
                "糖果熊，这是你吗？", "m-3", null, List.of(), List.of()));
    }

    @Test
    void unresolvedProtocolReplyRemainsAnOptionalCandidateForPersistedTargetResolution() {
        assertTrue(AIHandler.hasBotTurnEvidenceOrUnresolvedReply(
                "收到", "m-2", 999L, List.of(), List.of()));
        assertFalse(AIHandler.hasBotTurnEvidenceOrUnresolvedReply(
                "收到", "m-2", 1L, List.of(), List.of(
                        new BaiLianService.PublicMessage("1", "member-a", "甲", "我先说"))));
    }

    @Test
    void thirdPartySelfIntroductionIsNotAnImplicitCandyBearCandidate() {
        var history = List.of(
                new BaiLianService.PublicMessage("m-1", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "在吗"),
                new BaiLianService.PublicMessage("m-2", "member-b", "乙", "安洁，介绍一下你自己和你的朋友们"));

        assertFalse(AIHandler.isImplicitGroupCandidate("安洁，介绍一下你自己和你的朋友们", "m-2", null,
                false, new ConversationState("g", "member-b"), history));
        assertTrue(AIHandler.isImplicitGroupCandidate("糖果熊，介绍一下你自己和你的朋友们", "m-2", null,
                false, new ConversationState("g", "member-b"), history));
    }

    @Test
    void slowOptionalReplyIsDiscardedAfterAnotherGroupMemberTakesTheTurn() {
        // Production incident: 2590355304's candidate was answered by 3111651836
        // before CandyBear's slower model reply was delivered.
        var history = List.of(
                new BaiLianService.PublicMessage("question", "2590355304", "提问者",
                        "安洁，介绍一下你自己和你的朋友们"),
                new BaiLianService.PublicMessage("human-answer", "3111651836", "大肥鱼",
                        "另一位群友已经接话"),
                new BaiLianService.PublicMessage("bot-answer", String.valueOf(com.start.config.BotConfig.getBotQq()),
                        "糖果熊", "上一轮机器人的群消息"));

        assertTrue(AIHandler.isOptionalReplySupersededByLatestHuman(
                ConversationEvent.IMPLICIT_CANDIDATE, "2590355304", history));
        assertFalse(AIHandler.isOptionalReplySupersededByLatestHuman(
                ConversationEvent.MENTION, "2590355304", history));
    }

    @Test
    void optionalReplyCanContinueWhenOriginalSpeakerReclaimsTheTurn() {
        var history = List.of(
                new BaiLianService.PublicMessage("human-answer", "3111651836", "大肥鱼", "另一位群友先接话"),
                new BaiLianService.PublicMessage("follow-up", "2590355304", "提问者", "我还想问一下"));

        assertFalse(AIHandler.isOptionalReplySupersededByLatestHuman(
                ConversationEvent.IMPLICIT_CANDIDATE, "2590355304", history));
    }

    @Test
    void legacyFollowUpWindowCannotCrossAnotherHumanTurn() {
        var history = List.of(
                new BaiLianService.PublicMessage("bot-1", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "你先看看这个"),
                new BaiLianService.PublicMessage("human-1", "member-b", "乙", "我也想说一句"),
                new BaiLianService.PublicMessage("human-2", "member-a", "甲", "为什么"));

        var followUp = ConversationInterpreter.InterpretResult.of(ConversationEvent.FOLLOW_UP, "旧线程");
        assertSame(ConversationInterpreter.InterpretResult.NOTHING,
                AIHandler.guardInterpreterAddress(followUp, "为什么", "human-2", null,
                        List.of(), history));
        assertTrue(AIHandler.wasAddressGuardRejected(followUp, "为什么", "human-2", null,
                List.of(), history));
    }

    @Test
    void legacyFollowUpStillWorksForImmediateBotTurnAndExplicitAddressing() {
        var history = List.of(
                new BaiLianService.PublicMessage("bot-1", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "你先看看这个"),
                new BaiLianService.PublicMessage("human-1", "member-a", "甲", "为什么"));
        var followUp = ConversationInterpreter.InterpretResult.of(ConversationEvent.FOLLOW_UP, "旧线程");

        assertSame(followUp, AIHandler.guardInterpreterAddress(followUp, "为什么", "human-1", null,
                List.of(), history));
        assertSame(followUp, AIHandler.guardInterpreterAddress(followUp, "糖果熊，你觉得呢？", "human-1", null,
                List.of(), history));
    }

    @Test
    void bareQuestionDoesNotInheritLegacyFollowUpWithoutProtocolReply() {
        String botId = String.valueOf(com.start.config.BotConfig.getBotQq());
        var history = List.of(
                new BaiLianService.PublicMessage("1", botId, "糖果熊", "你觉得呢？"),
                new BaiLianService.PublicMessage("human-1", "member-a", "甲", "？"));
        var followUp = ConversationInterpreter.InterpretResult.of(ConversationEvent.FOLLOW_UP, "旧线程");

        assertSame(ConversationInterpreter.InterpretResult.NOTHING,
                AIHandler.guardInterpreterAddress(followUp, "？", "human-1", null,
                        List.of(), history));
        assertSame(followUp,
                AIHandler.guardInterpreterAddress(followUp, "？", "human-1", 1L,
                        List.of(), history));
    }

    @Test
    void replyToAnotherHumanCannotKeepLegacyFollowUp() {
        var history = List.of(
                new BaiLianService.PublicMessage("1", "member-a", "甲", "我先说"),
                new BaiLianService.PublicMessage("2", "member-b", "乙", "为什么", List.of(), 1L,
                        "文字、回复消息1"));
        var commented = ConversationInterpreter.InterpretResult.of(ConversationEvent.AI_COMMENTED, "旧线程");

        assertSame(ConversationInterpreter.InterpretResult.NOTHING,
                AIHandler.guardInterpreterAddress(commented, "为什么", "2", 1L, List.of(), history));
    }

    @Test
    void outboundReplyTargetUsesLatestNumericInboundMessageId() {
        var state = new ConversationState("g", "u1");
        state.addMessage("第一句", "100");
        state.addMessage("第二句", "101");
        assertEquals(101L, AIHandler.latestPendingReplyMessageId(state));
    }

    @Test
    void outboundReplyTargetSkipsLegacySymbolicMessageIds() {
        var state = new ConversationState("g", "u1");
        state.addMessage("旧消息", "legacy-id");
        assertNull(AIHandler.latestPendingReplyMessageId(state));
    }

    @Test
    void latestUnquotedMessageClearsThePriorPendingReplyTarget() {
        var state = new ConversationState("g", "u1");
        state.setReplyToMessageId(100L);
        state.setReplyToMessageId(null);

        assertNull(state.getReplyToMessageId());
    }
}
