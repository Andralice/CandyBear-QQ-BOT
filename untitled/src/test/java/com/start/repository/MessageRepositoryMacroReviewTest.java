package com.start.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MessageRepositoryMacroReviewTest {

    @Test
    void liveReviewPagesHaveStableDistinctKeys() {
        String firstPage = MessageRepository.macroReviewPageTaskKey("group-1", 500, 100);
        String nextPage = MessageRepository.macroReviewPageTaskKey("group-1", 500, 124);

        assertEquals("group:group-1:MACRO_REVIEW_PAGE:500:100", firstPage);
        assertNotEquals(firstPage, nextPage);
        assertEquals("group:group-1:MACRO_REVIEW_TRIGGER_AFTER:42",
                MessageRepository.macroReviewTriggerTaskKey("group-1", 42));
    }
}
