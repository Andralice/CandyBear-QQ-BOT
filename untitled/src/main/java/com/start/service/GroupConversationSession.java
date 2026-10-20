package com.start.service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 持久化的群聊会话快照。
 *
 * 这是糖果熊在群里的位置和会话生命周期，不等同于一次模型生成任务。
 * 生成任务失败或结束时，会话仍然可以继续观察后续消息。
 */
public final class GroupConversationSession {
    public enum LifecycleState {
        OBSERVING, CANDIDATE, ACTIVE, WAITING_FOR_REPLY, PAUSED, CLOSING, CLOSED
    }

    public enum BotPosition {
        DIRECT_TARGET,
        SECOND_PERSON_CONTINUATION,
        GROUP_WIDE_PARTICIPANT,
        TOPIC_OBSERVER,
        OTHER_MEMBER_CONVERSATION,
        PRIVATE_OR_SENSITIVE,
        UNKNOWN
    }

    public enum Action {
        OPEN, CONTINUE, CANDIDATE, OBSERVE, WAIT, CLOSE
    }

    public record Observation(
            String groupId,
            String threadId,
            String messageId,
            String senderId,
            String text,
            boolean botMentioned,
            boolean replyToBot,
            boolean addressedOther,
            boolean imagePresent,
            long observedAt
    ) {
        public Observation {
            groupId = clean(groupId);
            threadId = clean(threadId);
            messageId = clean(messageId);
            senderId = clean(senderId);
            text = text == null ? "" : text.trim();
            observedAt = observedAt <= 0 ? System.currentTimeMillis() : observedAt;
        }

        public Observation(String groupId, String threadId, String messageId, String senderId,
                           String text, boolean botMentioned, boolean replyToBot,
                           boolean addressedOther, boolean imagePresent) {
            this(groupId, threadId, messageId, senderId, text, botMentioned, replyToBot,
                    addressedOther, imagePresent, System.currentTimeMillis());
        }
    }

    public record Decision(
            String sessionId,
            String groupId,
            String threadId,
            LifecycleState state,
            BotPosition botPosition,
            Action action,
            String reason,
            String latestMessageId,
            long observedAt
    ) {}

    private final String sessionId;
    private final String groupId;
    private final String threadId;
    private LifecycleState lifecycleState;
    private BotPosition botPosition;
    private String topic;
    private String lastMessageId;
    private String lastBotMessageId;
    private String closeReason;
    private long lastActivityAt;
    private long lastBotMessageAt;
    private boolean botTurnPending;
    private final Set<String> participantIds = new LinkedHashSet<>();
    private final Set<String> evidenceMessageIds = new LinkedHashSet<>();
    private final List<GroupConversationEvidence> evidence = new ArrayList<>();

    public GroupConversationSession(String sessionId, String groupId, String threadId) {
        this(sessionId, groupId, threadId, LifecycleState.OBSERVING,
                BotPosition.UNKNOWN, null, null, null, null, 0L, 0L,
                Set.of(), Set.of(), List.of());
    }

    public GroupConversationSession(String sessionId, String groupId, String threadId,
                                    LifecycleState lifecycleState, BotPosition botPosition,
                                    String topic, String lastMessageId, String lastBotMessageId,
                                    String closeReason, long lastActivityAt, long lastBotMessageAt,
                                    Set<String> participantIds, Set<String> evidenceMessageIds) {
        this(sessionId, groupId, threadId, lifecycleState, botPosition, topic, lastMessageId,
                lastBotMessageId, closeReason, lastActivityAt, lastBotMessageAt,
                participantIds, evidenceMessageIds, List.of());
    }

    public GroupConversationSession(String sessionId, String groupId, String threadId,
                                    LifecycleState lifecycleState, BotPosition botPosition,
                                    String topic, String lastMessageId, String lastBotMessageId,
                                    String closeReason, long lastActivityAt, long lastBotMessageAt,
                                    Set<String> participantIds, Set<String> evidenceMessageIds,
                                    List<GroupConversationEvidence> evidence) {
        this.sessionId = clean(sessionId);
        this.groupId = clean(groupId);
        this.threadId = clean(threadId);
        this.lifecycleState = lifecycleState == null ? LifecycleState.OBSERVING : lifecycleState;
        this.botPosition = botPosition == null ? BotPosition.UNKNOWN : botPosition;
        this.topic = topic;
        this.lastMessageId = lastMessageId;
        this.lastBotMessageId = lastBotMessageId;
        this.closeReason = closeReason;
        this.lastActivityAt = lastActivityAt;
        this.lastBotMessageAt = lastBotMessageAt;
        if (participantIds != null) this.participantIds.addAll(participantIds);
        if (evidenceMessageIds != null) this.evidenceMessageIds.addAll(evidenceMessageIds);
        if (evidence != null) this.evidence.addAll(evidence);
    }

    public String sessionId() { return sessionId; }
    public String groupId() { return groupId; }
    public String threadId() { return threadId; }
    public synchronized LifecycleState lifecycleState() { return lifecycleState; }
    public synchronized BotPosition botPosition() { return botPosition; }
    public synchronized String topic() { return topic; }
    public synchronized String lastMessageId() { return lastMessageId; }
    public synchronized String lastBotMessageId() { return lastBotMessageId; }
    public synchronized String closeReason() { return closeReason; }
    public synchronized long lastActivityAt() { return lastActivityAt; }
    public synchronized long lastBotMessageAt() { return lastBotMessageAt; }
    public synchronized Set<String> participantIds() { return Set.copyOf(participantIds); }
    public synchronized Set<String> evidenceMessageIds() { return Set.copyOf(evidenceMessageIds); }
    public synchronized List<GroupConversationEvidence> evidence() { return List.copyOf(evidence); }

    synchronized void observe(Observation observation, LifecycleState nextState, BotPosition position,
                              String reason, String topic) {
        this.lifecycleState = nextState;
        this.botPosition = position;
        this.lastActivityAt = observation.observedAt();
        this.lastMessageId = emptyToNull(observation.messageId());
        if (!observation.senderId().isBlank()) participantIds.add(observation.senderId());
        if (!observation.messageId().isBlank()) {
            evidenceMessageIds.add(observation.messageId());
            while (evidenceMessageIds.size() > 64) {
                var iterator = evidenceMessageIds.iterator();
                iterator.next();
                iterator.remove();
            }
        }
        if (topic != null && !topic.isBlank()) this.topic = topic.substring(0, Math.min(255, topic.length()));
        if (nextState != LifecycleState.CLOSED) this.closeReason = null;
    }

    synchronized boolean addEvidence(GroupConversationEvidence item) {
        if (item == null || !sessionId.equals(item.sessionId())) return false;
        if (evidence.stream().anyMatch(existing -> existing.evidenceId().equals(item.evidenceId()))) return false;
        evidence.add(item);
        while (evidence.size() > 96) evidence.remove(0);
        if (!item.sourceMessageId().isBlank()) {
            evidenceMessageIds.add(item.sourceMessageId());
            while (evidenceMessageIds.size() > 64) {
                var iterator = evidenceMessageIds.iterator();
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    synchronized void markBotReply(String messageId, long at) {
        this.lastBotMessageId = messageId == null || messageId.isBlank() ? "__bot_reply__" : messageId;
        this.lastBotMessageAt = at;
        this.lastActivityAt = at;
        this.botTurnPending = true;
        this.lifecycleState = LifecycleState.ACTIVE;
        this.botPosition = BotPosition.DIRECT_TARGET;
        if (messageId != null && !messageId.isBlank()) evidenceMessageIds.add(messageId);
    }

    synchronized boolean hasPendingBotTurn() { return botTurnPending; }
    synchronized void consumePendingBotTurn() { botTurnPending = false; }

    synchronized void close(String reason, long at) {
        this.lifecycleState = LifecycleState.CLOSED;
        this.closeReason = reason == null ? "rule" : reason;
        this.lastActivityAt = at;
    }

    public synchronized GroupConversationSession copy() {
        return new GroupConversationSession(sessionId, groupId, threadId, lifecycleState, botPosition,
                topic, lastMessageId, lastBotMessageId, closeReason, lastActivityAt, lastBotMessageAt,
                participantIds, evidenceMessageIds, evidence);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
