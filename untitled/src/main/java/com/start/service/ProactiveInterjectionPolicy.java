package com.start.service;

/**
 * Applies hard group-safety guards before an optional proactive candidate reaches the model.
 */
public final class ProactiveInterjectionPolicy {

    private static final int BUSY_MESSAGE_LIMIT = 5;
    private static final int BUSY_PARTICIPANT_LIMIT = 4;
    private static final int RECENT_AI_REPLY_LIMIT = 3;

    public Decision assess(String message, ConversationMetrics.Snapshot snapshot,
                           boolean negativeCooldown) {
        if (message == null || message.isBlank()) return Decision.skip("empty_message");
        if (negativeCooldown) return Decision.skip("negative_feedback");
        if (snapshot.messagesLast30s() > BUSY_MESSAGE_LIMIT
                || (snapshot.messagesLast30s() >= 4 && snapshot.activeParticipants() > BUSY_PARTICIPANT_LIMIT)) {
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
                && !(snapshot.messagesLast30s() >= 4 && snapshot.activeParticipants() > BUSY_PARTICIPANT_LIMIT)
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
