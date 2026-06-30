package com.start.runtime.conversation;

import com.start.service.ConversationEvent;
import com.start.service.ConversationInterpreter;
import com.start.service.ConversationMetrics;
import com.start.service.GenerationResult;

import java.util.List;

/**
 * 一次群聊对话的完整生命周期状态。
 * 取代散落在各处的 groupId/userId/nickname/ats/allowSilence 参数。
 */
public class ConversationSession {
    private final String groupId;
    private final String userId;
    private final String nickname;
    private final String userPrompt;
    private final List<Long> atUserIds;
    private final boolean allowSilence;
    private final long generation;
    private final long revision;
    private final ConversationEvent event;
    private final ConversationMetrics.Snapshot metricsSnapshot;
    private final long startMs;

    private GenerationResult result;
    private long latencyMs;

    private ConversationSession(Builder b) {
        this.groupId = b.groupId;
        this.userId = b.userId;
        this.nickname = b.nickname;
        this.userPrompt = b.userPrompt;
        this.atUserIds = b.atUserIds;
        this.allowSilence = b.allowSilence;
        this.generation = b.generation;
        this.revision = b.revision;
        this.event = b.event;
        this.metricsSnapshot = b.metricsSnapshot;
        this.startMs = b.startMs;
    }

    // ---- getters ----

    public String groupId() { return groupId; }
    public String userId() { return userId; }
    public String nickname() { return nickname; }
    public String userPrompt() { return userPrompt; }
    public List<Long> atUserIds() { return atUserIds; }
    public boolean allowSilence() { return allowSilence; }
    public long generation() { return generation; }
    public long revision() { return revision; }
    public ConversationEvent event() { return event; }
    public ConversationMetrics.Snapshot metricsSnapshot() { return metricsSnapshot; }
    public long startMs() { return startMs; }
    public GenerationResult result() { return result; }
    public long latencyMs() { return latencyMs; }

    public String sessionId() { return "group_" + groupId + "_" + userId; }

    /** 标记生成完成 */
    public void complete(GenerationResult r, long latency) {
        this.result = r;
        this.latencyMs = latency;
    }

    public boolean isSilent() { return result != null && result.isSilent(); }
    public boolean shouldSend() { return result != null && result.shouldSend(); }

    // ---- Builder ----

    public static Builder of(String groupId, String userId, String nickname) {
        return new Builder(groupId, userId, nickname);
    }

    public static class Builder {
        private final String groupId;
        private final String userId;
        private final String nickname;
        private String userPrompt = "";
        private List<Long> atUserIds = List.of();
        private boolean allowSilence;
        private long generation;
        private long revision;
        private ConversationEvent event = ConversationEvent.NOTHING;
        private ConversationMetrics.Snapshot metricsSnapshot = ConversationMetrics.Snapshot.EMPTY;
        private long startMs = System.currentTimeMillis();

        Builder(String groupId, String userId, String nickname) {
            this.groupId = groupId;
            this.userId = userId;
            this.nickname = nickname;
        }

        public Builder userPrompt(String v) { userPrompt = v; return this; }
        public Builder atUserIds(List<Long> v) { atUserIds = v; return this; }
        public Builder allowSilence(boolean v) { allowSilence = v; return this; }
        public Builder generation(long v) { generation = v; return this; }
        public Builder revision(long v) { revision = v; return this; }
        public Builder event(ConversationEvent v) { event = v; return this; }
        public Builder metricsSnapshot(ConversationMetrics.Snapshot v) { metricsSnapshot = v; return this; }
        public Builder startMs(long v) { startMs = v; return this; }

        /** 从 InterpretResult 填充事件相关字段 */
        public Builder fromInterpret(ConversationInterpreter.InterpretResult r) {
            this.event = r.event();
            this.userPrompt = r.prompt() != null ? r.prompt() : "";
            return this;
        }

        public ConversationSession build() { return new ConversationSession(this); }
    }
}
