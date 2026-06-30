package com.start.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群聊节奏感知，让 LLM 感知"群里的气氛"。
 * 不做行为决策，只维护事实数据。
 */
public class ConversationMetrics {

    private final Map<String, GroupWindow> windows = new ConcurrentHashMap<>();

    /** 记录一条群消息 */
    public void recordMessage(String groupId, String userId) {
        GroupWindow w = windows.computeIfAbsent(groupId, k -> new GroupWindow());
        w.recordMessage(userId);
    }

    /** 记录机器人发言 */
    public void recordAiReply(String groupId) {
        GroupWindow w = windows.get(groupId);
        if (w != null) w.recordAiReply();
    }

    /** 获取当前群聊节奏快照 */
    public Snapshot getSnapshot(String groupId) {
        GroupWindow w = windows.get(groupId);
        if (w == null) return Snapshot.EMPTY;
        return w.snapshot();
    }

    public record Snapshot(int messagesLast30s, int aiMessagesLast5m, int activeParticipants) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0);

        public String toPromptHint() {
            if (messagesLast30s == 0) return "";
            StringBuilder sb = new StringBuilder("\n\n【当前群聊节奏】");
            sb.append("\n最近30秒消息数: ").append(messagesLast30s);
            sb.append("\n参与人数: ").append(activeParticipants);
            if (aiMessagesLast5m > 0) {
                sb.append("\n你最近5分钟说了 ").append(aiMessagesLast5m).append(" 次话");
            }
            return sb.toString();
        }
    }

    /** 每个群的滑动窗口 */
    private static class GroupWindow {
        private final long[] messageTimestamps = new long[64];
        private int msgWriteIdx;
        private int msgCount;

        private final long[] aiReplyTimestamps = new long[32];
        private int aiWriteIdx;
        private int aiCount;

        // 30 秒内的去重用户
        private final java.util.LinkedHashSet<String> recentUsers = new java.util.LinkedHashSet<>();
        private long lastUserClean;

        synchronized void recordMessage(String userId) {
            messageTimestamps[msgWriteIdx % 64] = System.currentTimeMillis();
            msgWriteIdx++;
            if (msgCount < 64) msgCount++;

            long now = System.currentTimeMillis();
            if (now - lastUserClean > 30_000) {
                recentUsers.clear();
                lastUserClean = now;
            }
            if (userId != null && !userId.isEmpty()) {
                recentUsers.add(userId);
            }
        }

        synchronized void recordAiReply() {
            aiReplyTimestamps[aiWriteIdx % 32] = System.currentTimeMillis();
            aiWriteIdx++;
            if (aiCount < 32) aiCount++;
        }

        synchronized Snapshot snapshot() {
            long now = System.currentTimeMillis();
            int recent30s = 0;
            for (int i = 0; i < Math.min(msgCount, 64); i++) {
                if (now - messageTimestamps[i] <= 30_000) recent30s++;
            }
            int aiRecent = 0;
            for (int i = 0; i < Math.min(aiCount, 32); i++) {
                if (now - aiReplyTimestamps[i] <= 300_000) aiRecent++;
            }
            return new Snapshot(recent30s, aiRecent, recentUsers.size());
        }
    }
}
