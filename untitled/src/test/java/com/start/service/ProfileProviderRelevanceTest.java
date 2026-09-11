package com.start.service;

import com.start.model.ProfileFactSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileProviderRelevanceTest {
    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 9, 11, 12, 0);

    @Test
    void injectsOnlyFactsRelevantToCurrentPrompt() {
        List<ProfileFactSummary> facts = List.of(
                fact("interest", "最近多次主动参与 Minecraft 话题"),
                fact("preference", "喜欢薄荷绿"),
                fact("habit", "通常在晚上聊天")
        );

        List<ProfileFactSummary> selected = ProfileProvider.relevantFacts(facts, "Minecraft 有什么好玩的地图？");

        assertEquals(List.of("最近多次主动参与 Minecraft 话题"), selected.stream()
                .map(ProfileFactSummary::claim).toList());
    }

    @Test
    void leavesProfileOutWhenPromptHasNoMatchingFact() {
        List<ProfileFactSummary> facts = List.of(
                fact("interest", "喜欢薄荷绿"),
                fact("habit", "通常在晚上聊天")
        );

        assertTrue(ProfileProvider.relevantFacts(facts, "今天群里发生什么？").isEmpty());
    }

    @Test
    void keepsLegacyCallBoundedWhenNoPromptIsAvailable() {
        List<ProfileFactSummary> facts = List.of(
                fact("interest", "喜欢薄荷绿"),
                fact("habit", "通常在晚上聊天")
        );

        assertEquals(facts, ProfileProvider.relevantFacts(facts, null));
    }

    private static ProfileFactSummary fact(String kind, String claim) {
        return new ProfileFactSummary(1, kind, "OBSERVED_BEHAVIOR", claim, "TEMPORARY", 0.7,
                "PERSONAL", OBSERVED_AT, null);
    }
}
