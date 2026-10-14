package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProactiveInterjectionPolicyTest {

    private final ProactiveInterjectionPolicy policy = new ProactiveInterjectionPolicy();

    @Test
    void skipsACandidateWhileTheGroupIsBusy() {
        var busy = new ConversationMetrics.Snapshot(6, 0, 5);

        var decision = policy.assess("这首歌的编曲真的很好听", busy, false);

        assertFalse(decision.shouldSchedule());
        assertTrue(decision.reason().equals("group_is_active"));
    }

    @Test
    void eligibleCandidateAlwaysReachesTheMainModelWithoutARandomGate() {
        var quiet = new ConversationMetrics.Snapshot(1, 0, 1);

        var decision = policy.assess("这首歌的编曲真的很好听", quiet, false);

        assertTrue(decision.shouldSchedule());
        assertTrue(decision.reason().equals("candidate"));
    }

    @Test
    void delayedCandidateIsRejectedWhenTheGroupBecomesBusy() {
        var quiet = new ConversationMetrics.Snapshot(1, 0, 1);
        var busy = new ConversationMetrics.Snapshot(5, 0, 5);

        assertTrue(policy.remainsAppropriate(quiet));
        assertFalse(policy.remainsAppropriate(busy));
    }

    @Test
    void recentBotRepliesSuppressAnotherInterjection() {
        var recentlyActive = new ConversationMetrics.Snapshot(1, 3, 1);

        var decision = policy.assess("这部电影的配乐很喜欢", recentlyActive, false);

        assertFalse(decision.shouldSchedule());
        assertTrue(decision.reason().equals("bot_spoke_recently"));
    }

    @Test
    void negativeCooldownSkipsBothInitialAndDelayedChecks() {
        var quiet = new ConversationMetrics.Snapshot(1, 0, 1);

        var decision = policy.assess("大家最近有什么好听的歌推荐吗？", quiet, true);

        assertFalse(decision.shouldSchedule());
        assertTrue(decision.reason().equals("negative_feedback"));
        assertFalse(policy.remainsAppropriate(quiet, true));
    }

    @Test
    void negativeFeedbackMustBeExplicitlyDirectedAtCandyBear() {
        assertTrue(ProactiveFeedbackDetector.isDirectedNegativeFeedback(
                "闭嘴", List.of(123L), 123L));
        assertTrue(ProactiveFeedbackDetector.isDirectedNegativeFeedback(
                "糖果熊别说话了", List.of(), 123L));
        assertFalse(ProactiveFeedbackDetector.isDirectedNegativeFeedback(
                "无语", List.of(), 123L));
    }
}
