package com.start.runtime.conversation;

import com.start.service.BaiLianService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 共享 BaiLianService 状态 Map 的 StateStore 实现。
 * 读/写同一份内存数据，不复制。
 */
public class InMemoryConversationStateStore implements ConversationStateStore {

    private final Map<String, BaiLianService.UserThread> userThreads;
    private final Map<String, Deque<BaiLianService.ContextEvent>> groupContexts;
    private final Map<String, BaiLianService.PendingAwait> pendingAwaits;
    private final Map<String, List<Long>> groupReactionHistory;
    private static final int MAX_REACTIONS = 10;
    private static final long REACTION_WINDOW_MS = 300_000;

    public InMemoryConversationStateStore(
            Map<String, BaiLianService.UserThread> userThreads,
            Map<String, Deque<BaiLianService.ContextEvent>> groupContexts,
            Map<String, BaiLianService.PendingAwait> pendingAwaits,
            Map<String, List<Long>> groupReactionHistory) {
        this.userThreads = userThreads;
        this.groupContexts = groupContexts;
        this.pendingAwaits = pendingAwaits;
        this.groupReactionHistory = groupReactionHistory;
    }

    @Override
    public UserThread getUserThread(String key) {
        BaiLianService.UserThread t = userThreads.get(key);
        return t != null ? new UserThread(t.lastInteraction, t.lastBotReply) : null;
    }

    @Override
    public void putUserThread(String key, UserThread t) {
        userThreads.put(key, new BaiLianService.UserThread(t.lastInteraction(), t.lastBotReply()));
    }

    @Override
    public PendingAwait removePendingAwait(String key) {
        BaiLianService.PendingAwait a = pendingAwaits.remove(key);
        if (a == null) return null;
        return new PendingAwait(a.groupId, a.targetUserId, a.targetNickname,
                a.question, a.context, a.sessionId, a.createdAt, a.timeoutMs);
    }

    @Override
    public void putPendingAwait(String key, PendingAwait a) { /* not used from interpreter side */ }

    @Override
    public void purgeExpiredAwaits() {
        pendingAwaits.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    @Override
    public Deque<ContextEvent> getGroupContext(String groupId) {
        Deque<BaiLianService.ContextEvent> src = groupContexts.get(groupId);
        if (src == null) return null;
        Deque<ContextEvent> converted = new ConcurrentLinkedDeque<>();
        for (BaiLianService.ContextEvent e : src) {
            converted.add(new ContextEvent(e.timestamp, e.type, e.content, e.userId, e.senderNick));
        }
        return converted;
    }

    @Override
    public void addGroupContext(String groupId, ContextEvent e) {
        groupContexts.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>())
                .addLast(new BaiLianService.ContextEvent(e.timestamp(), e.type(), e.content(),
                        e.userId(), e.senderNick()));
    }

    @Override
    public boolean canReact(String groupId) {
        List<Long> history = groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
        history.removeIf(ts -> System.currentTimeMillis() - ts > REACTION_WINDOW_MS);
        return history.size() < MAX_REACTIONS;
    }

    @Override
    public void recordReaction(String groupId) {
        groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }
}
