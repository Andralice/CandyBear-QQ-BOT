package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationResultReplyReferenceTest {
    @Test
    void quoteChoiceDefaultsToFalseAndSurvivesEvidenceEnrichment() {
        GroupTurnAssessment assessment = new GroupTurnAssessment(GroupTurnAssessment.Decision.REPLY,
                List.of("你好"), "", "", "", false, "test");

        assertFalse(GenerationResult.reply("你好", 0, 0, assessment).replyToCurrentMessage());
        GenerationResult selected = GenerationResult.reply("你好", 1, 0, assessment, true)
                .withEvidenceMessageIds(List.of("message-1"));
        assertTrue(selected.replyToCurrentMessage());
    }
}
