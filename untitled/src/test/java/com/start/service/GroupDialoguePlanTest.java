package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupDialoguePlanTest {

    @Test
    void secondPersonTurnTakesPriorityOverInterjectionOpportunity() {
        GroupDialoguePlan plan = GroupDialoguePlan.merge(
                relation(true, "u1", "m3"), opportunity(true, "u2"), 30, 40, 100);

        assertEquals(GroupDialoguePlan.Action.REPLY, plan.action());
        assertEquals(GroupDialoguePlan.EntryRole.SECOND_PERSON, plan.entryRole());
        assertEquals("u3", plan.targetUserId());
        assertEquals("m3", plan.targetMessageId());
    }

    @Test
    void concreteThirdPartyOpportunityProducesInterjection() {
        GroupDialoguePlan plan = GroupDialoguePlan.merge(
                relation(false, "", ""), opportunity(true, "u2"), 30, 40, 100);

        assertTrue(plan.shouldReply());
        assertEquals(GroupDialoguePlan.EntryRole.THIRD_PERSON_INTERJECTION, plan.entryRole());
        assertEquals("u2", plan.targetUserId());
        assertTrue(plan.toPromptNarrative().contains("前面的对话不是对你说的"));
        assertTrue(plan.toPromptNarrative().contains("只是承载最新消息的会话锚点"));
        assertTrue(plan.compactSummary().contains("parallelMs=40"));
        assertTrue(plan.compactSummary().contains("interaction=OTHER"));
        assertTrue(plan.compactSummary().contains("contribution=\"可提供两位小数正则\""));
    }

    @Test
    void noTurnAndNoOpportunityKeepsObserverSilent() {
        GroupDialoguePlan plan = GroupDialoguePlan.merge(
                relation(false, "", ""), opportunity(false, ""), 30, 40, 100);

        assertFalse(plan.shouldReply());
        assertEquals(GroupDialoguePlan.EntryRole.OBSERVER, plan.entryRole());
        assertEquals("", plan.targetMessageId());
    }

    @Test
    void activeSecondPersonPositionIsPreservedEvenWhenSilent() {
        GroupDialoguePlan.Relation relation = new GroupDialoguePlan.Relation(
                "u1", List.of("bot"), "陪伴", "u1回应糖果熊",
                GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON, false, "", "",
                "对话可暂时收束", List.of());

        GroupDialoguePlan plan = GroupDialoguePlan.merge(
                relation, opportunity(false, ""), 30, 40, 100);

        assertFalse(plan.shouldReply());
        assertEquals(GroupDialoguePlan.EntryRole.SECOND_PERSON, plan.entryRole());
    }

    @Test
    void modeParsingIsFailClosed() {
        assertEquals(GroupCognitionMode.SHADOW, GroupCognitionMode.parse("shadow"));
        assertEquals(GroupCognitionMode.ENFORCE, GroupCognitionMode.parse(" ENFORCE "));
        assertEquals(GroupCognitionMode.OFF, GroupCognitionMode.parse("unknown"));
        assertEquals(GroupCognitionMode.OFF, GroupCognitionMode.parse(null));
    }

    @Test
    void analyzerOnlyEnablesExplicitCanaryGroups() {
        GroupDialogueAnalyzer analyzer = new GroupDialogueAnalyzer(
                GroupCognitionMode.SHADOW, java.util.Set.of(123L), "key",
                "https://example.invalid/v1/chat/completions", "model", 2_000);

        assertEquals(GroupCognitionMode.SHADOW, analyzer.modeFor("123"));
        assertEquals(GroupCognitionMode.OFF, analyzer.modeFor("456"));
        assertEquals(GroupCognitionMode.OFF, analyzer.modeFor("not-a-group"));
    }

    private static GroupDialoguePlan.Relation relation(boolean hasTurn, String target, String message) {
        return new GroupDialoguePlan.Relation("u3", List.of(target), "测试库连接", "u3 回答糖果熊",
                hasTurn ? GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON : GroupDialoguePlan.BotRole.OUTSIDER,
                hasTurn, target, message,
                "轮到糖果熊继续", List.of("不要把老王当成提问者"));
    }

    private static GroupDialoguePlan.Opportunity opportunity(boolean interject, String beneficiary) {
        return new GroupDialoguePlan.Opportunity("金额正则", true, false,
                "可提供两位小数正则", 0.95, GroupDialoguePlan.Risk.LOW,
                interject, beneficiary, "群友明确卡住");
    }
}
