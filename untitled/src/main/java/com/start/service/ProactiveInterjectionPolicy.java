package com.start.service;

/**
 * Applies hard group-safety guards before an optional proactive candidate reaches the model.
 */
public final class ProactiveInterjectionPolicy {

    public Decision assess(String message, ConversationMetrics.Snapshot snapshot,
                           boolean negativeCooldown) {
        if (message == null || message.isBlank()) return Decision.skip("empty_message");
        if (negativeCooldown) return Decision.skip("negative_feedback");
        return Decision.schedule();
    }

    /** Negative feedback remains the only policy guard after a candidate is scheduled. */
    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot) {
        return remainsAppropriate(snapshot, false);
    }

    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot, boolean negativeCooldown) {
        return !negativeCooldown;
    }

    public record Decision(boolean shouldSchedule, String reason) {
        static Decision schedule() {
            return new Decision(true, "candidate");
        }

        static Decision skip(String reason) {
            return new Decision(false, reason);
        }
    }
}
