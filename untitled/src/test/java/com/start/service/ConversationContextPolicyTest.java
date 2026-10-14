package com.start.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

class ConversationContextPolicyTest {

    @Test
    void privateMessagesHaveNoGroupHistoryAndGroupLookupsStillWork() {
        var history = new ConcurrentHashMap<String, java.util.Deque<BaiLianService.PublicMessage>>();
        var groupHistory = new ArrayDeque<BaiLianService.PublicMessage>();
        history.put("group-1", groupHistory);

        assertNull(BaiLianService.lookupPublicGroupHistory(history, null));
        assertSame(groupHistory, BaiLianService.lookupPublicGroupHistory(history, "group-1"));
    }

    @Test
    void followUpAndAiCommentReceiveGroupEvidenceForTurnResolution() {
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.FOLLOW_UP));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.AI_COMMENTED));
        assertEquals(0, BaiLianService.publicContextLimit(ConversationEvent.AWAIT_REPLY));
    }

    @Test
    void onlyAmbiguousCandidatesCanChooseSilenceInTheMainTurn() {
        assertTrue(ConversationEvent.FOLLOW_UP.allowsSilence());
        assertTrue(ConversationEvent.AI_COMMENTED.allowsSilence());
        assertTrue(ConversationEvent.PROBABILISTIC.allowsSilence());
        assertTrue(ConversationEvent.IMPLICIT_CANDIDATE.allowsSilence());
        assertFalse(ConversationEvent.MENTION.allowsSilence());
        assertFalse(ConversationEvent.AWAIT_REPLY.allowsSilence());
        assertFalse(ConversationEvent.PASSIVE_TRIGGER.allowsSilence());
    }

    @Test
    void explicitAndPassiveRepliesUseSmallBackgroundWindows() {
        assertEquals(6, BaiLianService.publicContextLimit(ConversationEvent.MENTION));
        assertEquals(6, BaiLianService.publicContextLimit(ConversationEvent.PASSIVE_TRIGGER));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.PROBABILISTIC));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.COGNITIVE_CONTINUE));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.COGNITIVE_INTERJECTION));
        assertEquals(8, BaiLianService.publicContextLimit(ConversationEvent.IMPLICIT_CANDIDATE));
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

    @Test
    void turnEvidenceKeepsCurrentSenderMentionAndReplyChain() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage(
                "1", "u1", "甲", "糖果熊刚才说了这个", List.of(), null, "文字"));
        history.add(new BaiLianService.PublicMessage(
                "2", "u2", "乙", "我是在回甲", List.of(), 1L, "文字、回复消息1"));
        history.add(new BaiLianService.PublicMessage(
                "m3", "u3", "丙", "我是在叫糖果熊", List.of(999L), null, "文字、@用户999"));

        String evidence = BaiLianService.buildTurnEvidence(history, Set.of("m3"));

        assertTrue(evidence.contains("message_id=m3"));
        assertTrue(evidence.contains("sender_id=u3"));
        assertTrue(evidence.contains("at_user_ids=[999]"));
        assertTrue(evidence.contains("[本轮待处理]"));
        assertTrue(evidence.contains("reply_to_sender_id=u1"));
        assertTrue(evidence.contains("reply_to_sender_name=甲"));
        assertTrue(evidence.contains("reply_target_found=true"));
        assertTrue(evidence.contains("reply_to_message_parts=文字"));
        assertTrue(evidence.contains("reply_to_content=糖果熊刚才说了这个"));
        assertTrue(evidence.contains("message_parts=文字、@用户999"));
        assertTrue(evidence.contains("sender_role=群友"));
        assertTrue(evidence.contains("current_message=true"));
        assertTrue(evidence.contains("reply_target_is_candybear=false"));
        assertTrue(evidence.contains("is_candybear=false"));
    }

    @Test
    void currentBotVocativeIsDerivedOnlyFromCurrentMessages() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("old", "u1", "甲", "糖果熊，你看这个"));
        history.add(new BaiLianService.PublicMessage("current", "u2", "乙", "小鲸鱼，这是你吗？"));

        assertFalse(BaiLianService.isCurrentMessageAddressedToBot(history, Set.of("current")));
        assertTrue(BaiLianService.isCurrentMessageAddressedToBot(history, Set.of("old")));
    }

    @Test
    void knownDeepSeekPeerIsMarkedAsAnotherBotNotCandyBear() {
        assertEquals("其他机器人", BaiLianService.senderRole("3111651836"));
        assertEquals("群友", BaiLianService.senderRole("2590355304"));
    }

    @Test
    void turnEvidenceKeepsNonTextMessageAndBotReceiptIdWithVerifiedVisionText() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage(
                "900", String.valueOf(com.start.config.BotConfig.getBotQq()), "糖果熊", "画好了", List.of(), null, "文字"));
        history.add(new BaiLianService.PublicMessage(
                "901", "u3", "丙", "", List.of(), 900L, "图片"));
        history.getLast().imageDescription = "一只戴红围巾的熊站在雪地里";

        String evidence = BaiLianService.buildTurnEvidence(history, Set.of("901"));

        assertTrue(evidence.contains("[本轮待处理]"));
        assertTrue(evidence.contains("message_parts=图片"));
        assertTrue(evidence.contains("reply_to_sender_id=" + com.start.config.BotConfig.getBotQq()));
        assertTrue(evidence.contains("reply_to_sender_name=糖果熊"));
        assertTrue(evidence.contains("reply_target_found=true"));
        assertTrue(evidence.contains("reply_to_message_parts=文字"));
        assertTrue(evidence.contains("reply_to_content=画好了"));
        assertTrue(evidence.contains("reply_target_is_candybear=true"));
        assertTrue(evidence.contains("image_content=一只戴红围巾的熊站在雪地里"));
        assertTrue(evidence.contains("红围巾"));
        assertTrue(evidence.contains("content=（无文字内容）"));
    }

    @Test
    void imageGuidanceOnlyAppearsWhenEvidenceContainsAnImage() {
        var textOnly = new ArrayDeque<BaiLianService.PublicMessage>();
        textOnly.add(new BaiLianService.PublicMessage("1", "u1", "甲", "普通消息", List.of(), null, "文字"));
        assertFalse(BaiLianService.buildTurnEvidence(textOnly, Set.of()).contains("证据链里有图片"));

        var withImage = new ArrayDeque<BaiLianService.PublicMessage>();
        withImage.add(new BaiLianService.PublicMessage("2", "u1", "甲", "", List.of(), null, "图片"));
        String evidence = BaiLianService.buildTurnEvidence(withImage, Set.of("2"));
        assertTrue(evidence.contains("证据链里有图片"));
        assertTrue(evidence.contains("当前证据链没有识图结果"));
    }

    @Test
    void denseRealReplyChainNeverLosesCurrentMessageToEvidenceBudget() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        for (int i = 0; i < 10; i++) {
            history.add(new BaiLianService.PublicMessage(String.valueOf(1000 + i),
                    "member-" + i, "群友", "聊天背景".repeat(55)));
        }
        history.add(new BaiLianService.PublicMessage("309720350", "member-a", "甲", "而且没啥玩的"));
        history.add(new BaiLianService.PublicMessage("574016084", "member-c", "丙",
                "星露谷物语种地", List.of(), 309720350L, "文字、回复消息309720350"));

        String evidence = BaiLianService.buildTurnEvidence(history, Set.of("574016084"));
        assertTrue(evidence.contains("message_id=574016084"), "本轮消息被旧背景挤出证据链");
        assertTrue(evidence.contains("reply_to_content=而且没啥玩的"));
        assertTrue(evidence.contains("reply_target_is_candybear=false"));
    }

    @Test
    void evidenceMarksIndependentSpeakerChangeInsteadOfMergingTheirMeaning() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        history.add(new BaiLianService.PublicMessage("309720350", "u1", "甲", "我最近没啥玩的", List.of(), null, "文字"));
        history.add(new BaiLianService.PublicMessage("574016084", "u2", "乙", "星露谷物语", List.of(), null, "文字"));

        String evidence = BaiLianService.buildTurnEvidence(history, Set.of("574016084"));

        assertTrue(evidence.contains("message_id=574016084"));
        assertTrue(evidence.contains("relation_to_previous=SPEAKER_CHANGED_NO_PROTOCOL_LINK"));
        assertTrue(evidence.contains("不得把后一句改写成前一句的回答"));
    }

    @Test
    void completedVisionDescriptionIsPartOfEvidenceButMissingDescriptionIsNotGuessed() {
        var history = new ArrayDeque<BaiLianService.PublicMessage>();
        var described = new BaiLianService.PublicMessage("img-1", "u1", "甲", "",
                List.of(), null, "图片");
        described.imageDescription = "一只戴红围巾的棕色熊";
        history.add(described);
        history.add(new BaiLianService.PublicMessage("img-2", "u2", "乙", "",
                List.of(), null, "图片"));

        String evidence = BaiLianService.buildTurnEvidence(history, Set.of("img-2"));
        assertTrue(evidence.contains("image_content=一只戴红围巾的棕色熊"));
        assertTrue(evidence.contains("image_content=图片存在但当前证据链没有识图结果，不能据此猜测"));
    }
}
