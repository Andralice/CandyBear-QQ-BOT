package com.start.config;

/** Rollout mode for the macro evidence review pipeline. */
public enum MacroReviewMode {
    OFF,
    LIVE_SHADOW,
    LIVE_ACTIVE;

    public static MacroReviewMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }

    public boolean enabled() {
        return this != OFF;
    }

    public boolean ownsProfileExtraction() {
        return this == LIVE_SHADOW || this == LIVE_ACTIVE;
    }

    public boolean writesFacts() {
        return this == LIVE_ACTIVE;
    }
}
