package com.start.runtime.conversation;

import java.util.Deque;

/** 对话运行时内存状态仓库。纯内存，无 SQL，无文件 I/O。 */
public interface ConversationStateStore {

    // === 追问窗口 ===
    UserThread getUserThread(String key);
    void putUserThread(String key, UserThread t);

    // === 异步等待 ===
    PendingAwait removePendingAwait(String key);
    void putPendingAwait(String key, PendingAwait a);
    void purgeExpiredAwaits();

    // === 群上下文事件 ===
    Deque<ContextEvent> getGroupContext(String groupId);
    void addGroupContext(String groupId, ContextEvent e);

    // === 速率控制 ===
    boolean canReact(String groupId);
    void recordReaction(String groupId);

    // === 内嵌类型 ===

    /** 追问窗口记录 */
    record UserThread(long lastInteraction, String lastBotReply) {}

    /** 群上下文事件 */
    record ContextEvent(long timestamp, String type, String content, String userId, String senderNick) {}

    /** 异步等待 */
    final class PendingAwait {
        public final String groupId;
        public final String targetUserId;
        public final String targetNickname;
        public final String question;
        public final String context;
        public final String sessionId;
        private final long createdAt;
        private final long timeoutMs;

        public PendingAwait(String groupId, String targetUserId, String targetNickname,
                            String question, String context, String sessionId, long timeoutMs) {
            this(groupId, targetUserId, targetNickname, question, context, sessionId,
                    System.currentTimeMillis(), timeoutMs);
        }

        public PendingAwait(String groupId, String targetUserId, String targetNickname,
                            String question, String context, String sessionId,
                            long createdAt, long timeoutMs) {
            this.groupId = groupId;
            this.targetUserId = targetUserId;
            this.targetNickname = targetNickname;
            this.question = question;
            this.context = context;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.timeoutMs = timeoutMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > timeoutMs;
        }
    }
}
