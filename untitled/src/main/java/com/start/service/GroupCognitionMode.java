package com.start.service;

/** Rollout mode for the parallel group-chat cognition pipeline. */
public enum GroupCognitionMode {
    /** Preserve the legacy path and make no cognition calls. */
    OFF,
    /** Analyze real messages and log the counterfactual decision without changing behavior. */
    SHADOW,
    /** Keep legacy triggering, but inject the analyzed perspective into replies that already run. */
    ASSIST,
    /** Let parallel cognition decide ordinary continuation and interjection candidates. */
    ENFORCE;

    public static GroupCognitionMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }

    public boolean analyzesMessages() {
        return this != OFF;
    }

    public boolean injectsPerspective() {
        return this == ASSIST || this == ENFORCE;
    }

    public boolean controlsOrdinaryTriggers() {
        return this == ENFORCE;
    }
}
