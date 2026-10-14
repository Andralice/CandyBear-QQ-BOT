package com.start.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 每次消息处理的完整决策链路记录，用于可观测性和 prompt 调优 */
public class DecisionTrace {
    private final long timestamp;
    private final String groupId;
    private final String userId;
    private final String eventType;
    private final String decision;
    private final String reason;
    private final int toolCalls;
    private final int tokensUsed;
    private final long latencyMs;
    private final long generation;
    private final long revision;
    private final boolean allowSilence;
    private final List<String> inputMessageIds;
    private final String replyToMessageId;
    private final List<String> evidenceMessageIds;

    public DecisionTrace(long timestamp, String groupId, String userId, String eventType,
                         String decision, String reason, int toolCalls, int tokensUsed,
                         long latencyMs, long generation, long revision, boolean allowSilence) {
        this(timestamp, groupId, userId, eventType, decision, reason, toolCalls, tokensUsed,
                latencyMs, generation, revision, allowSilence, List.of(), "", List.of());
    }

    public DecisionTrace(long timestamp, String groupId, String userId, String eventType,
                         String decision, String reason, int toolCalls, int tokensUsed,
                         long latencyMs, long generation, long revision, boolean allowSilence,
                         List<String> inputMessageIds, String replyToMessageId,
                         List<String> evidenceMessageIds) {
        this.timestamp = timestamp;
        this.groupId = groupId;
        this.userId = userId;
        this.eventType = eventType;
        this.decision = decision;
        this.reason = reason;
        this.toolCalls = toolCalls;
        this.tokensUsed = tokensUsed;
        this.latencyMs = latencyMs;
        this.generation = generation;
        this.revision = revision;
        this.allowSilence = allowSilence;
        this.inputMessageIds = inputMessageIds == null ? List.of() : List.copyOf(inputMessageIds);
        this.replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
        this.evidenceMessageIds = evidenceMessageIds == null ? List.of() : List.copyOf(evidenceMessageIds);
    }

    /** 紧凑的单行日志格式 */
    public String toLogLine() {
        String time = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("[%s] gid=%s uid=%s gen=%d rev=%d event=%s → %s (%s) tools=%d tok=%d %dms input_ids=%s reply_to=%s evidence_ids=%s",
                time, groupId != null ? groupId : "-", userId,
                generation, revision,
                eventType, decision, reason, toolCalls, tokensUsed, latencyMs,
                inputMessageIds, replyToMessageId.isBlank() ? "-" : replyToMessageId, evidenceMessageIds);
    }

    @Override
    public String toString() { return toLogLine(); }
}
