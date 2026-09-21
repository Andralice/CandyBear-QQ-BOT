package com.start.config;

/** Rollout mode for the macro evidence review pipeline. */
public enum MacroReviewMode {
    OFF,
    LIVE_SHADOW;

    public static MacroReviewMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }
}
