package com.start.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacroReviewModeTest {

    @Test
    void unknownValuesStayDisabled() {
        assertEquals(MacroReviewMode.OFF, MacroReviewMode.parse(null));
        assertEquals(MacroReviewMode.OFF, MacroReviewMode.parse("future_mode"));
        assertEquals(MacroReviewMode.OFF, MacroReviewMode.parse(""));
    }

    @Test
    void parsesOnlyTheExplicitLiveShadowMode() {
        assertEquals(MacroReviewMode.LIVE_SHADOW, MacroReviewMode.parse("live_shadow"));
        assertEquals(MacroReviewMode.LIVE_SHADOW, MacroReviewMode.parse(" LIVE_SHADOW "));
    }
}
