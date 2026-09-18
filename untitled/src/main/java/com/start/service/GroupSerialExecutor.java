package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 key 串行执行任务。群聊使用共享 worker 池和每 key 的有界队列，
 * 私聊使用独立的有界线程池。避免为每个群永久创建线程。
 */
public class GroupSerialExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GroupSerialExecutor.class);

    private static final int MAX_GROUP_QUEUE_SIZE = 100;
    private static final int MAX_PRIVATE_QUEUE_SIZE = 200;

    private final Map<String, QueueState> groupQueues = new ConcurrentHashMap<>();
    private final ExecutorService groupWorkerPool;
    private final ThreadPoolExecutor privateChatExecutor;
    private final long defaultMaxQueueTimeMs;

    // ===== 监控指标（线程安全） =====
    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksRejected = new AtomicLong();
    private final AtomicLong tasksExpired = new AtomicLong();
    private final AtomicLong tasksActive = new AtomicLong();
    private final AtomicLong totalExecutionTimeMs = new AtomicLong();
    private final AtomicLong maxExecutionTimeMs = new AtomicLong();

    public GroupSerialExecutor(int privateThreads, long defaultMaxQueueTimeMs) {
        int threads = Math.max(1, privateThreads);
        this.groupWorkerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Conversation-Worker");
            t.setDaemon(true);
            return t;
        });
        this.privateChatExecutor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PRIVATE_QUEUE_SIZE), r -> {
            Thread t = new Thread(r, "Private-AI-Worker");
            t.setDaemon(true);
            return t;
        });
        this.defaultMaxQueueTimeMs = defaultMaxQueueTimeMs;
    }

    /** 提交任务，使用默认超时 */
    public void execute(String groupId, Runnable task) {
        execute(groupId, task, defaultMaxQueueTimeMs);
    }

    /**
     * 提交到同一用户的会话生成队列。同群不同用户使用不同 key，可以并行生成；
     * 同一用户的生成任务仍保持顺序。
     */
    public void executeConversation(String groupId, String userId, Runnable task) {
        execute("conversation:" + String.valueOf(groupId) + ":" + String.valueOf(userId), task);
    }

    /**
     * 提交任务到对应群的串行队列。超过排队时间的任务会被丢弃。
     *
     * @param groupId 群号（null 表示私聊，走共享线程池）
     * @param task    要执行的任务
     * @param maxQueueTimeMs 最大排队时间毫秒，超过则丢弃
     */
    public void execute(String groupId, Runnable task, long maxQueueTimeMs) {
        tasksSubmitted.incrementAndGet();
        long submitTime = System.currentTimeMillis();

        QueuedTask queued = new QueuedTask(() -> {
            long waited = System.currentTimeMillis() - submitTime;
            if (waited > maxQueueTimeMs) {
                tasksExpired.incrementAndGet();
                logger.debug("丢弃过期任务 group={} 排队{}ms", groupId, waited);
                return;
            }
            if (waited > 500) {
                logger.debug("任务排队{}ms group={}", waited, groupId);
            }
            // 记录任务执行耗时
            tasksActive.incrementAndGet();
            long execStart = System.currentTimeMillis();
            try {
                task.run();
            } finally {
                long duration = System.currentTimeMillis() - execStart;
                tasksActive.decrementAndGet();
                tasksCompleted.incrementAndGet();
                totalExecutionTimeMs.addAndGet(duration);
                // 简单的最大耗时记录（非精确 max，但 O(1)）
                long prevMax = maxExecutionTimeMs.get();
                while (duration > prevMax && !maxExecutionTimeMs.compareAndSet(prevMax, duration)) {
                    prevMax = maxExecutionTimeMs.get();
                }
            }
        }, submitTime, maxQueueTimeMs);

        if (groupId == null) {
            try {
                privateChatExecutor.execute(queued.task());
            } catch (RejectedExecutionException e) {
                tasksRejected.incrementAndGet();
                logger.warn("私聊任务队列已满，丢弃任务");
            }
        } else {
            enqueueGroupTask(groupId, queued);
        }
    }

    private void enqueueGroupTask(String groupId, QueuedTask task) {
        while (true) {
            QueueState state = groupQueues.computeIfAbsent(groupId, ignored -> new QueueState());
            boolean shouldStart = false;
            synchronized (state) {
                // A worker may have removed this state between lookup and locking it.
                if (groupQueues.get(groupId) != state) continue;
                if (state.tasks.size() >= MAX_GROUP_QUEUE_SIZE) {
                    tasksRejected.incrementAndGet();
                    logger.warn("群 {} 任务队列已满，丢弃任务", groupId);
                    return;
                }
                state.tasks.addLast(task);
                if (!state.running) {
                    state.running = true;
                    shouldStart = true;
                }
            }
            if (shouldStart) scheduleNext(groupId, state);
            return;
        }
    }

    private void scheduleNext(String groupId, QueueState state) {
        try {
            groupWorkerPool.execute(() -> runNext(groupId, state));
        } catch (RejectedExecutionException e) {
            synchronized (state) {
                state.tasks.clear();
                state.running = false;
            }
            groupQueues.remove(groupId, state);
            tasksRejected.incrementAndGet();
            logger.warn("群 {} worker 池已关闭，丢弃任务", groupId);
        }
    }

    private void runNext(String groupId, QueueState state) {
        QueuedTask task;
        synchronized (state) {
            task = state.tasks.pollFirst();
            if (task == null) {
                state.running = false;
                groupQueues.remove(groupId, state);
                return;
            }
        }

        try {
            if (System.currentTimeMillis() - task.submittedAt() <= task.maxQueueTimeMs()) {
                task.task().run();
            } else {
                tasksExpired.incrementAndGet();
                logger.debug("丢弃过期任务 group={} 排队{}ms", groupId,
                        System.currentTimeMillis() - task.submittedAt());
            }
        } catch (Throwable t) {
            logger.error("群 {} 任务执行异常", groupId, t);
        }

        boolean continueRunning;
        synchronized (state) {
            continueRunning = !state.tasks.isEmpty();
            if (!continueRunning) {
                state.running = false;
                groupQueues.remove(groupId, state);
            }
        }
        if (continueRunning) scheduleNext(groupId, state);
    }

    private static final class QueueState {
        private final ArrayDeque<QueuedTask> tasks = new ArrayDeque<>();
        private boolean running;
    }

    private record QueuedTask(Runnable task, long submittedAt, long maxQueueTimeMs) {}

    /**
     * 监控指标快照，供 WebDashboard 等横切模块拉取。
     * 字段均为调用瞬间值，保证后续修改不会影响快照本身。
     */
    public static final class ExecutorMetrics {
        public final long tasksSubmitted;
        public final long tasksCompleted;
        public final long tasksRejected;
        public final long tasksExpired;
        public final long tasksActive;
        public final long totalExecutionTimeMs;
        public final long maxExecutionTimeMs;
        public final int groupQueuesCount;
        public final int groupQueuesTotalSize;
        public final int privateQueueSize;
        public final int privateQueueCapacity;
        public final int privateActiveThreads;

        public ExecutorMetrics(long tasksSubmitted, long tasksCompleted, long tasksRejected,
                               long tasksExpired, long tasksActive, long totalExecutionTimeMs,
                               long maxExecutionTimeMs, int groupQueuesCount,
                               int groupQueuesTotalSize, int privateQueueSize,
                               int privateQueueCapacity, int privateActiveThreads) {
            this.tasksSubmitted = tasksSubmitted;
            this.tasksCompleted = tasksCompleted;
            this.tasksRejected = tasksRejected;
            this.tasksExpired = tasksExpired;
            this.tasksActive = tasksActive;
            this.totalExecutionTimeMs = totalExecutionTimeMs;
            this.maxExecutionTimeMs = maxExecutionTimeMs;
            this.groupQueuesCount = groupQueuesCount;
            this.groupQueuesTotalSize = groupQueuesTotalSize;
            this.privateQueueSize = privateQueueSize;
            this.privateQueueCapacity = privateQueueCapacity;
            this.privateActiveThreads = privateActiveThreads;
        }
    }

    /** 获取当前线程池监控快照。 */
    public ExecutorMetrics getMetrics() {
        int groupQueuesSize = 0;
        for (QueueState q : groupQueues.values()) {
            synchronized (q) {
                groupQueuesSize += q.tasks.size();
            }
        }
        return new ExecutorMetrics(
                tasksSubmitted.get(),
                tasksCompleted.get(),
                tasksRejected.get(),
                tasksExpired.get(),
                tasksActive.get(),
                totalExecutionTimeMs.get(),
                maxExecutionTimeMs.get(),
                groupQueues.size(),
                groupQueuesSize,
                privateChatExecutor.getQueue().size(),
                MAX_PRIVATE_QUEUE_SIZE,
                privateChatExecutor.getActiveCount()
        );
    }

    /** 关闭所有执行器 */
    public void shutdown() {
        groupWorkerPool.shutdownNow();
        privateChatExecutor.shutdownNow();
        groupQueues.clear();
    }
}
