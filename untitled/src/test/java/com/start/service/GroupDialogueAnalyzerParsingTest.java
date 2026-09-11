package com.start.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupDialogueAnalyzerParsingTest {

    @Test
    void transcriptResolvesProtocolReplyOwner() throws Exception {
        String transcript = GroupDialogueAnalyzer.buildTranscript(java.util.List.of(
                new BaiLianService.PublicMessage("101", "u1", "甲", "先问一下"),
                new BaiLianService.PublicMessage("102", "u2", "乙", "回甲", java.util.List.of(), 101L)));

        var messages = MAPPER.readTree(transcript).path("messages");
        assertEquals("u1", messages.get(1).path("reply_to_sender_id").asText());
        assertEquals(false, messages.get(1).path("mentions_bot").asBoolean());
    }

    @Test
    void unaddressedMessageWithoutBotEvidenceCannotBecomeSecondPersonTurn() {
        var relation = new GroupDialoguePlan.Relation(
                "u1", java.util.List.of(), "矮人笑话", "u1发出请求",
                GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON, true, "u1", "101",
                "像是在请求糖果熊", java.util.List.of());

        var constrained = GroupDialogueAnalyzer.applyTurnEvidenceConstraint(relation, java.util.List.of(
                new BaiLianService.PublicMessage("101", "u1", "甲", "来点矮人笑话")));

        assertEquals(false, constrained.botHasNextTurn());
        assertEquals(GroupDialoguePlan.BotRole.OUTSIDER, constrained.botRole());
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesProtocolIdsWithoutTreatingThemAsGeneratedReplyTargets() throws Exception {
        var relation = GroupDialogueAnalyzer.parseRelation(MAPPER.readTree("""
                {
                  "latest_speaker_id":"u3",
                  "latest_addressee_ids":["bot"],
                  "latest_topic":"测试库连接",
                  "reply_chain":"m3 回答 m1",
                  "bot_role":"ACTIVE_SECOND_PERSON",
                  "bot_has_next_turn":true,
                  "next_turn_target_user_id":"u3",
                  "next_turn_target_message_id":"m3",
                  "turn_reason":"回答了机器人的问题",
                  "forbidden_assumptions":["不要认错老王"]
                }
                """));

        assertTrue(relation.botHasNextTurn());
        assertEquals("u3", relation.nextTurnTargetUserId());
        assertEquals("m3", relation.nextTurnTargetMessageId());
        assertEquals(GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON, relation.botRole());
    }

    @Test
    void parsesIndependentOpportunityDecision() throws Exception {
        var opportunity = GroupDialogueAnalyzer.parseOpportunity(MAPPER.readTree("""
                {
                  "open_need":"金额正则",
                  "humans_explicitly_stalled":true,
                  "already_being_handled":false,
                  "concrete_contribution":"^\\\\d+\\\\.\\\\d{2}$",
                  "contribution_confidence":0.95,
                  "intrusion_risk":"LOW",
                  "should_interject":true,
                  "beneficiary_user_id":"u1",
                  "reason":"可以立即解决"
                }
                """));

        assertTrue(opportunity.shouldInterject());
        assertEquals("u1", opportunity.beneficiaryUserId());
        assertEquals(GroupDialoguePlan.Risk.LOW, opportunity.intrusionRisk());
    }

    @Test
    void protocolTargetOverridesModelRoutingForSecondPerson() {
        var plan = GroupDialoguePlan.merge(
                new GroupDialoguePlan.Relation("u1", java.util.List.of("999"), "测试库", "回答机器人",
                        GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON, true, "999", "错误正文",
                        "轮到机器人", java.util.List.of()),
                new GroupDialoguePlan.Opportunity("", false, true, "", 0,
                        GroupDialoguePlan.Risk.HIGH, false, "", ""),
                1, 1, 10);

        var normalized = GroupDialogueAnalyzer.applyProtocolTarget(plan, java.util.List.of(
                new BaiLianService.PublicMessage("m3", "u1", "小陈", "测试库")));

        assertEquals("u1", normalized.targetUserId());
        assertEquals("m3", normalized.targetMessageId());
    }
}
