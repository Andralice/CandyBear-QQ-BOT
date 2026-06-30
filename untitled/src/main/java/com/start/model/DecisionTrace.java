package com.start.model;

import com.start.runtime.RuntimeEvent;
import com.start.service.GenerationMetadata;
import com.start.service.GenerationResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

    public DecisionTrace(long timestamp, String groupId, String userId, String eventType,
                         String decision, String reason, int toolCalls, int tokensUsed,
                         long latencyMs, long generation, long revision, boolean allowSilence) {
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
    }

    /** 从 CommitFinished 事件构造 */
    public static DecisionTrace from(RuntimeEvent.CommitFinished f) {
        GenerationResult r = f.result();
        GenerationMetadata m = r != null ? r.metadata() : null;
        String dec = r != null && r.isSilent() ? "SILENT"
                : r != null && r.isError() ? "ERROR" : "REPLY";
        return new DecisionTrace(
                System.currentTimeMillis(), f.groupId(), f.userId(),
                "COMMIT", dec,
                r != null && r.isSilent() ? "model_no_reply" : "ok",
                m != null ? m.toolCalls() : 0,
                m != null ? m.tokensUsed() : 0,
                f.latencyMs(),
                m != null ? m.generation() : 0,
                m != null ? m.revision() : 0,
                false);
    }

    /** 紧凑的单行日志格式 */
    public String toLogLine() {
        String time = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("[%s] gid=%s uid=%s gen=%d rev=%d event=%s → %s (%s) tools=%d tok=%d %dms",
                time, groupId != null ? groupId : "-", userId,
                generation, revision,
                eventType, decision, reason, toolCalls, tokensUsed, latencyMs);
    }

    @Override
    public String toString() { return toLogLine(); }
}
