package com.start.service;

import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 提醒服务
 */
public class ReminderService {
    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);
    private static final ReminderService INSTANCE = new ReminderService();

    private volatile boolean enabled = true;
    private final Map<Long, AbstractReminderTask> activeTasks = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(3, r -> {
        Thread t = new Thread(r, "ReminderService-Worker");
        t.setDaemon(true);
        return t;
    });

    private Main botInstance;

    private ReminderService() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            activeTasks.clear();
        }));
    }

    public static ReminderService getInstance() {
        return INSTANCE;
    }

    public void setBotInstance(Main bot) {
        this.botInstance = bot;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            activeTasks.values().forEach(AbstractReminderTask::cancel);
            activeTasks.clear();
            logger.info("🔕 提醒服务已全局关闭");
        }
    }

    // ===== 原有：间隔提醒 =====
    public void startReminding(long userId, String message, long intervalSec, int maxRetries) {
        if (!enabled) return;
        stopReminding(userId);
        IntervalTask task = new IntervalTask(userId, message, intervalSec, maxRetries, botInstance);
        activeTasks.put(userId, task);
        task.start(scheduler);
    }

    // ===== 新增：一次性定时提醒（私聊） =====
    public void remindAt(long userId, String message, LocalDateTime triggerTime) {
        if (!enabled) return;
        stopReminding(userId);

        long delaySec = Duration.between(LocalDateTime.now(), triggerTime).getSeconds();
        if (delaySec <= 0) {
            botInstance.sendPrivateReply(userId, "提醒时间已过期。");
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (enabled) {
                botInstance.sendPrivateReply(userId, message);
                logger.info("✅ 一次性提醒已发送给 user={}", userId);
            }
            activeTasks.remove(userId);
        }, delaySec, TimeUnit.SECONDS);

        activeTasks.put(userId, new OneTimeTask(future));
    }

    // ===== 新增：一次性定时提醒（群聊） =====
    public void remindAtGroup(long groupId, long userId, String message, LocalDateTime triggerTime) {
        if (!enabled) return;
        String key = "group_" + groupId + "_" + userId;
        stopReminding(userId);

        long delaySec = Duration.between(LocalDateTime.now(), triggerTime).getSeconds();
        if (delaySec <= 0) {
            botInstance.sendGroupReply(groupId, "[CQ:at,qq=" + userId + "] 提醒时间已过期。");
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (enabled) {
                botInstance.sendGroupReply(groupId, "[CQ:at,qq=" + userId + "] " + message);
                logger.info("✅ 群提醒已发送 group={} user={}", groupId, userId);
            }
            activeTasks.remove(userId);
        }, delaySec, TimeUnit.SECONDS);

        activeTasks.put(userId, new OneTimeTask(future));
    }

    // ===== 新增：延迟私聊某人（可用于定时提醒别人） =====
    public void remindPrivate(long groupId, long targetUserId, String message, LocalDateTime triggerTime) {
        if (!enabled) return;

        long delaySec = Duration.between(LocalDateTime.now(), triggerTime).getSeconds();
        if (delaySec <= 0) {
            botInstance.sendPrivateReply(targetUserId, groupId, "提醒时间已过期：" + message);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (enabled) {
                botInstance.sendPrivateReply(targetUserId, groupId, message);
                logger.info("✅ 延迟私聊已发送 target={}", targetUserId);
            }
        }, delaySec, TimeUnit.SECONDS);

        activeTasks.put(targetUserId, new OneTimeTask(future));
    }

    /** 解析相对时间字符串（如"30分钟""1小时""5秒"）为秒数 */
    public static long parseDelaySeconds(String timeStr) {
        if (timeStr == null) return 0;
        timeStr = timeStr.trim();
        try {
            if (timeStr.contains("小时") || timeStr.contains("时")) {
                String num = timeStr.replaceAll("[^0-9.]", "");
                return (long) (Double.parseDouble(num) * 3600);
            }
            if (timeStr.contains("分钟") || timeStr.contains("分")) {
                String num = timeStr.replaceAll("[^0-9.]", "");
                return (long) (Double.parseDouble(num) * 60);
            }
            if (timeStr.contains("秒")) {
                String num = timeStr.replaceAll("[^0-9.]", "");
                return (long) Double.parseDouble(num);
            }
            return Long.parseLong(timeStr); // 纯数字，按秒处理
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ===== 新增：每日定时提醒 =====
    public void remindDaily(long userId, String message, LocalTime timeOfDay) {
        if (!enabled) return;
        stopReminding(userId);

        Runnable dailyRunnable = () -> {
            if (enabled) {
                botInstance.sendPrivateReply(userId, message);
                logger.debug("📤 每日提醒已发送给 user={}", userId);
            }
        };

        ScheduledFuture<?> future = scheduleNextDaily(dailyRunnable, timeOfDay);
        activeTasks.put(userId, new DailyTask(future, dailyRunnable, timeOfDay));
    }

    public void stopReminding(long userId) {
        AbstractReminderTask task = activeTasks.remove(userId);
        if (task != null) {
            task.cancel();
            logger.info("✅ 已停止对 user={} 的提醒", userId);
        }
    }

    public void onPrivateMessageReceived(long userId) {
        if (!enabled) return;
        AbstractReminderTask task = activeTasks.get(userId);
        if (task != null) {
            task.markAsReplied();
            activeTasks.remove(userId);
        }
    }

    // 计算下一次每日提醒的时间（今天 or 明天）
    private ScheduledFuture<?> scheduleNextDaily(Runnable task, LocalTime timeOfDay) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayTrigger = LocalDateTime.of(LocalDate.now(), timeOfDay);
        LocalDateTime nextTrigger = now.isAfter(todayTrigger) ?
                todayTrigger.plusDays(1) : todayTrigger;

        long delay = Duration.between(now, nextTrigger).getSeconds();
        return scheduler.scheduleAtFixedRate(task, delay, 24 * 3600, TimeUnit.SECONDS);
    }

    // ===== 抽象任务基类 =====
    private abstract static class AbstractReminderTask {
        protected final AtomicBoolean completed = new AtomicBoolean(false);
        public abstract void cancel();
        public void markAsReplied() {
            if (completed.compareAndSet(false, true)) {
                cancel();
            }
        }
    }

    // 间隔任务（原有）
    private static class IntervalTask extends AbstractReminderTask {
        private final long userId;
        private final String message;
        private final long intervalSeconds;
        private final int maxRetries;
        private final Main bot;
        private ScheduledFuture<?> future;
        private int retryCount = 0;

        IntervalTask(long userId, String message, long intervalSeconds, int maxRetries, Main bot) {
            this.userId = userId;
            this.message = message;
            this.intervalSeconds = intervalSeconds;
            this.maxRetries = maxRetries;
            this.bot = bot;
        }

        void start(ScheduledThreadPoolExecutor scheduler) {
            if (completed.get()) return;
            future = scheduler.scheduleWithFixedDelay(this::sendAndSchedule, 0, intervalSeconds, TimeUnit.SECONDS);
        }

        private void sendAndSchedule() {
            if (completed.get() || retryCount >= maxRetries) {
                cancel();
                return;
            }
            try {
                bot.sendPrivateReply(userId, message);
                retryCount++;
            } catch (Exception e) {
                logger.error("❌ 发送提醒失败", e);
            }
        }

        @Override
        public void cancel() {
            if (future != null) future.cancel(false);
            completed.set(true);
        }
    }

    // 一次性任务
    private static class OneTimeTask extends AbstractReminderTask {
        private final ScheduledFuture<?> future;
        OneTimeTask(ScheduledFuture<?> future) {
            this.future = future;
        }
        @Override
        public void cancel() {
            if (future != null) future.cancel(false);
            completed.set(true);
        }
    }

    // 每日任务
    private static class DailyTask extends AbstractReminderTask {
        private final ScheduledFuture<?> future;
        private final Runnable task;
        private final LocalTime timeOfDay;

        DailyTask(ScheduledFuture<?> future, Runnable task, LocalTime timeOfDay) {
            this.future = future;
            this.task = task;
            this.timeOfDay = timeOfDay;
        }

        @Override
        public void cancel() {
            if (future != null) future.cancel(false);
            completed.set(true);
        }

        // 如果需要重新调度（比如收到回复后又想开启），可扩展
    }
}