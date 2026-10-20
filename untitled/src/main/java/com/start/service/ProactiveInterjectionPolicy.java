package com.start.service;

/**
 * Applies hard group-safety guards before an optional proactive candidate reaches the model.
 */
public final class ProactiveInterjectionPolicy {

    // Interest-based participation may happen during a lively conversation;
    // keep only a high-traffic guard so the bot does not flood a fast stream.
    private static final int BUSY_MESSAGE_LIMIT = 12;
    private static final int RECENT_AI_REPLY_LIMIT = 3;

    public Decision assess(String message, ConversationMetrics.Snapshot snapshot,
                           boolean negativeCooldown) {
        if (message == null || message.isBlank()) return Decision.skip("empty_message");
        if (negativeCooldown) return Decision.skip("negative_feedback");
        if (snapshot.messagesLast30s() > BUSY_MESSAGE_LIMIT) {
            return Decision.skip("group_is_active");
        }
        if (snapshot.aiMessagesLast5m() >= RECENT_AI_REPLY_LIMIT) {
            return Decision.skip("bot_spoke_recently");
        }
        return Decision.schedule();
    }

    /** A delayed candidate is cancelled if a group becomes busy before it is sent. */
    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot) {
        return remainsAppropriate(snapshot, false);
    }

    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot, boolean negativeCooldown) {
        return snapshot.messagesLast30s() <= BUSY_MESSAGE_LIMIT
                && snapshot.aiMessagesLast5m() < RECENT_AI_REPLY_LIMIT
                && !negativeCooldown;
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
