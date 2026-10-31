package com.start.thread;

import com.hankcs.hanlp.HanLP;
import com.start.config.DatabaseConfig;
import com.start.model.ConversationThread;
import com.start.repository.ThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thread 生命周期管理器。
 *
 * 职责：
 *   - 消息到达时检测/创建/更新 Thread
 *   - 定期推进生命周期：ACTIVE → IDLE（5min 无消息）→ RESOLVED（30min）
 *   - 合并主题高度重叠的 Thread
 *   - 计算 Scene 视图
 */
public class ThreadManager {

    private static final Logger logger = LoggerFactory.getLogger(ThreadManager.class);
    private static final long IDLE_MINUTES = 5;
    private static final long RESOLVE_MINUTES = 30;
    private static final double MERGE_JACCARD_THRESHOLD = 0.6;

    private final ThreadRepository repo;
    private final ThreadDetector detector;
    private final AttentionAllocator allocator;
    private final GroupMessageSource msgSource;

    public ThreadManager() {
        this(new ThreadRepository(DatabaseConfig.getDataSource()), null);
    }

    public ThreadManager(ThreadRepository repo) {
        this(repo, null);
    }

    public ThreadManager(ThreadRepository repo, GroupMessageSource msgSource) {
        this.repo = repo;
        this.detector = new ThreadDetector();
        this.allocator = new AttentionAllocator();
        this.msgSource = msgSource;
    }

    /**
     * 处理一条消息：归类到 Thread、更新状态、推进生命周期，返回当前 Scene。
     *
     * @param groupId  群 ID
     * @param userId   发消息的用户
     * @param message  消息文本
     * @param botQq    bot 的 QQ 号
     * @return 当前群的 Scene 视图
     */
    public SceneState processMessage(String groupId, String userId, String message, long botQq) {
        return processMessage(groupId, userId, message, "", botQq);
    }

    public SceneState processMessage(String groupId, String userId, String message, String messageId, long botQq) {
        // 1. 推进生命周期（IDLE → RESOLVED）
        repo.markResolved(RESOLVE_MINUTES);
        repo.markIdle(IDLE_MINUTES);

        // 2. 加载活跃 Thread
        List<ConversationThread> activeThreads = repo.findActiveByGroup(groupId);

        // 3. 检测消息归属
        ConversationThread matched = detector.detect(groupId, userId, message, messageId, activeThreads);

        if (matched != null) {
            // 归入已有 Thread
            addParticipant(matched, userId);
            matched.setLastMessageAt(Instant.now());
            matched.setMessageCount(matched.getMessageCount() + 1);
            if (matched.getStatus().equals("IDLE")) {
                matched.setStatus("ACTIVE"); // 复活
                repo.updateStatus(matched.getId(), "ACTIVE", null);
            }
            double newWeight = allocator.calculate(matched, botQq);
            repo.updateAfterMessage(matched.getId(), matched.getParticipantIds(), newWeight);
            matched.setAttentionWeight(newWeight);
            // P1: Topic 聚合重算 — 消息数 >= 5 时用最近消息窗口重新提取
            if (matched.getMessageCount() >= 5 && msgSource != null) {
                try {
                    List<String> recent = msgSource.getRecentMessages(groupId, 10);
                    if (recent != null && !recent.isEmpty()) {
                        String newTopic = extractTopic(String.join(" ", recent));
                        if (newTopic != null && !newTopic.equals(matched.getTopic())) {
                            String oldTopic = matched.getTopic();
                            matched.setTopic(newTopic);
                            repo.updateTopic(matched.getId(), newTopic);
                            logger.debug("Thread {} topic updated: {} → {}", matched.getId(), oldTopic, newTopic);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Topic aggregation skipped: {}", e.getMessage());
                }
            }
        } else if (message != null && !message.isBlank() && !isNoise(message)) {
            // 创建新 Thread
            ConversationThread newThread = new ConversationThread();
            newThread.setGroupId(groupId);
            newThread.setTopic(extractTopic(message));
            newThread.setStatus("ACTIVE");
            newThread.setParticipants(new ArrayList<>(List.of(userId)));
            newThread.setLastMessageAt(Instant.now());
            newThread.setMessageCount(1);
            newThread.setAttentionWeight(allocator.calculate(newThread, botQq));
            repo.save(newThread);
            activeThreads.add(newThread);
        }

        // 4. 尝试合并相似 Thread
        activeThreads = maybeMerge(activeThreads);

        // 5. 重新计算所有 Thread 权重，排序
        for (ConversationThread t : activeThreads) {
            t.setAttentionWeight(allocator.calculate(t, botQq));
        }
        activeThreads.sort((a, b) -> Double.compare(b.getAttentionWeight(), a.getAttentionWeight()));

        // 6. 构建 SceneState
        return buildScene(activeThreads);
    }

    /** 获取当前 Scene 视图（不处理消息，只读）。 */
    public SceneState getScene(String groupId, long botQq) {
        repo.markResolved(RESOLVE_MINUTES);
        repo.markIdle(IDLE_MINUTES);
        List<ConversationThread> activeThreads = repo.findActiveByGroup(groupId);
        for (ConversationThread t : activeThreads) {
            t.setAttentionWeight(allocator.calculate(t, botQq));
        }
        activeThreads.sort((a, b) -> Double.compare(b.getAttentionWeight(), a.getAttentionWeight()));
        return buildScene(activeThreads);
    }

    private SceneState buildScene(List<ConversationThread> threads) {
        if (threads.isEmpty()) return SceneState.EMPTY;

        String focusedTopic = null;
        List<String> topicParts = new ArrayList<>();
        for (int i = 0; i < Math.min(threads.size(), 5); i++) {
            ConversationThread t = threads.get(i);
            if (t.getTopic() != null && !t.getTopic().isBlank()) {
                int pct = (int) Math.round(t.getAttentionWeight() * 100);
                topicParts.add(t.getTopic() + "(" + pct + "%)");
                if (focusedTopic == null) focusedTopic = t.getTopic();
            }
        }

        return new SceneState(threads, String.join(" | ", topicParts), focusedTopic, threads.size());
    }

    /** 合并主题高度重叠的 Thread。将较新的合并到较早的。 */
    private List<ConversationThread> maybeMerge(List<ConversationThread> threads) {
        if (threads.size() < 2) return threads;

        List<ConversationThread> result = new ArrayList<>();
        for (ConversationThread t : threads) {
            boolean merged = false;
            for (ConversationThread existing : result) {
                if (existing.getTopic() == null || t.getTopic() == null) continue;
                double sim = ThreadDetector.jaccardSimilarity(existing.getTopic(), t.getTopic());
                if (sim >= MERGE_JACCARD_THRESHOLD) {
                    // 合并：把 t 的参与者加入 existing，t 标记为 MERGED
                    mergeParticipants(existing, t);
                    existing.setMessageCount(existing.getMessageCount() + t.getMessageCount());
                    if (t.getLastMessageAt() != null &&
                            (existing.getLastMessageAt() == null || t.getLastMessageAt().isAfter(existing.getLastMessageAt()))) {
                        existing.setLastMessageAt(t.getLastMessageAt());
                    }
                    repo.updateStatus(t.getId(), "MERGED", existing.getId());
                    // 持久化合目标 Thread 的参与者与消息计数
                    repo.updateMergeTarget(existing.getId(), existing.getParticipantIds(),
                            existing.getMessageCount(), existing.getLastMessageAt());
                    logger.debug("Thread merged: {} → {}", t.getId(), existing.getId());
                    merged = true;
                    break;
                }
            }
            if (!merged) result.add(t);
        }
        return result;
    }

    private void addParticipant(ConversationThread thread, String userId) {
        List<String> participants = new ArrayList<>(new LinkedHashSet<>(thread.getParticipants()));
        if (!participants.contains(userId)) {
            participants.add(userId);
            thread.setParticipants(participants);
        }
    }

    private void mergeParticipants(ConversationThread target, ConversationThread source) {
        LinkedHashSet<String> all = new LinkedHashSet<>(target.getParticipants());
        all.addAll(source.getParticipants());
        target.setParticipants(new ArrayList<>(all));
    }

    /** 判断消息是否为无意义闲聊（长度过短、纯标点/数字/表情）。 */
    private boolean isNoise(String message) {
        if (message == null) return true;
        String stripped = message.replaceAll("[\\p{Punct}\\p{Digit}\\s\\p{So}]+", "").trim();
        // 去除标点数字空白和 emoji 后，剩余有意义字符不足 5 个 → 噪音
        return stripped.length() < 5;
    }

    /** 用 HanLP 提取消息关键词作为话题描述。失败时回退到截断前 20 字符。 */
    private String extractTopic(String message) {
        if (message == null || message.isBlank()) return null;
        try {
            String clean = message.replaceAll("[\\p{Punct}\\s]+", " ").trim();
            if (clean.isEmpty()) return message.substring(0, Math.min(20, message.length()));
            List<String> kw = HanLP.extractKeyword(clean, 3);
            if (kw != null && !kw.isEmpty()) {
                return kw.stream().filter(k -> k != null && !k.isBlank()).limit(3).collect(Collectors.joining(" "));
            }
        } catch (Exception e) {
            logger.debug("HanLP topic extraction failed: {}", e.getMessage());
        }
        // 回退：截断
        String cleaned = message.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }
}
