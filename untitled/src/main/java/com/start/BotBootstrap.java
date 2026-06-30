package com.start;

import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.handler.CPTracker;
import com.start.handler.HandlerRegistry;
import com.start.model.LongTermMemory;
import com.start.model.RecurringTask;
import com.start.repository.*;
import com.start.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务装配与后台任务启动器。
 * 将 Main 从冗长的构造器和 init() 中解放，保持其专注 WebSocket + 消息分发。
 */
public final class BotBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(BotBootstrap.class);

    private BotBootstrap() {}

    /** 创建并装配所有核心服务，注入到 Main 实例。 */
    public static void wireServices(Main bot) {
        // WebSocket API 封装
        bot.oneBotWsService = new OneBotWsService(bot);

        // 基础服务
        bot.userService = new UserService();
        bot.messageService = new MessageService();

        bot.aiDatabaseService = new AIDatabaseService();

        // 知识库 & 情绪
        bot.keywordKnowledgeService = new KeywordKnowledgeService(DatabaseConfig.getDataSource());
        bot.moodService = new BotMoodService(new GroupMoodRepository(DatabaseConfig.getDataSource()));

        // TTS
        bot.ttsService = new TtsService();

        // 大模型服务
        bot.baiLianService = new BaiLianService(bot.keywordKnowledgeService, bot.userAffinityRepo, bot.ttsService);
        bot.baiLianService.setMoodService(bot.moodService);
        bot.baiLianService.setBotInstance(bot);

        // 异常监控
        bot.errorMonitorService = new ErrorMonitorService(bot.baiLianService);

        // 群聊串行执行器 & Shell 服务
        GroupSerialExecutor groupExecutor = new GroupSerialExecutor(4, 30_000);
        ServerAdminService shellService = new ServerAdminService();

        // Handler 注册中心
        ConversationManager conversationManager = new ConversationManager();
        bot.handlerRegistry = new HandlerRegistry(bot.baiLianService, groupExecutor, bot, shellService, conversationManager);

        // DashScope API Key
        if (BotConfig.getBaiLianApiKey() != null && !BotConfig.getBaiLianApiKey().isBlank()) {
            System.setProperty("dashscope.api-key", BotConfig.getBaiLianApiKey());
        }
    }

    /** 启动所有后台定时任务（守护线程）。 */
    public static void startBackgroundTasks(Main bot) {
        // 防刷检测
        bot.spamDetector = new SpamDetector(bot);
        logger.info("SpamDetector 初始化完成");

        // 糖果熊知识种子
        bot.keywordKnowledgeService.seedCandyBearKnowledge();

        // 人生引擎
        CandyBearLifeRepository lifeRepo = new CandyBearLifeRepository(DatabaseConfig.getDataSource());
        CandyBearScheduleRepository scheduleRepo = new CandyBearScheduleRepository(DatabaseConfig.getDataSource());
        CandyBearLifeEngine lifeEngine = new CandyBearLifeEngine(lifeRepo, scheduleRepo, bot.baiLianService);
        bot.baiLianService.setLifeEngine(lifeEngine);
        lifeEngine.onStartup();
        logger.info("糖果熊人生引擎已启动（四层架构：章节->周记->日记->工具查询 + LifeState + 日程表）");

        startLifeEngineThread(bot, lifeEngine);
        startPortraitService(bot);
        startReminderService(bot);
        startEventChecker(bot);
        startRecurringScheduler(bot);
        startErrorMonitor(bot);
    }

    private static void startLifeEngineThread(Main bot, CandyBearLifeEngine lifeEngine) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(millisUntilNext3AM());
                    lifeEngine.dailyTick();
                    logger.info("人生引擎 tick 完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("人生引擎 tick 失败", e);
                }
            }
        }, "CandyBearLife-Thread");
        t.setDaemon(true);
        t.start();
    }

    private static void startPortraitService(Main bot) {
        bot.portraitService = new UserPortraitService(bot.baiLianService, new MessageRepository());
        bot.portraitService.runUpdateTask();
        logger.info("用户画像首次更新完成");

        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10 * 60 * 1000);
                    bot.portraitService.runUpdateTask();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("用户画像更新任务异常", e);
                }
            }
        }, "UserPortrait-Update-Thread");
        t.setDaemon(true);
        t.start();
        logger.info("用户画像系统已启动");
    }

    private static void startReminderService(Main bot) {
        ReminderService reminderService = ReminderService.getInstance();
        reminderService.setBotInstance(bot);
        reminderService.setEnabled(true);
        logger.info("私聊提醒服务已初始化");
    }

    private static void startEventChecker(Main bot) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10 * 60 * 1000);
                    List<LongTermMemory> dueEvents = bot.longTermMemoryRepo.findDueEvents();
                    for (LongTermMemory event : dueEvents) {
                        try {
                            String prompt = "你之前记下了一个定时事件：\"" + event.getContent()
                                    + "\"\n涉及用户：" + event.getUserId()
                                    + "\n现在时间到了，请自然地提醒或祝福。";
                            String reply = bot.baiLianService.generate(
                                    "event_" + event.getId(),
                                    event.getUserId(),
                                    prompt,
                                    event.getGroupId(),
                                    "糖果熊"
                            );
                            if (reply != null && !reply.trim().isEmpty()) {
                                bot.sendGroupReply(Long.parseLong(event.getGroupId()), reply);
                            }
                            bot.longTermMemoryRepo.markTriggered(event.getId());
                            logger.info("定时事件已触发: {} -> {}", event.getContent(), event.getGroupId());
                        } catch (Exception e) {
                            logger.error("定时事件触发失败 id={}: {}", event.getId(), e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("定时事件检查异常", e);
                }
            }
        }, "EventChecker-Thread");
        t.setDaemon(true);
        t.start();
        logger.info("定时事件检查器已启动");
    }

    private static void startRecurringScheduler(Main bot) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60 * 1000);
                    bot.recurringTaskRepo.expireOldTasks();
                    List<RecurringTask> dueTasks = bot.recurringTaskRepo.findDueTasks();
                    for (RecurringTask task : dueTasks) {
                        try {
                            logger.info("周期任务触发: {} (id={})", task.getTaskName(), task.getId());
                            String sessionId = "recurring_" + task.getId() + "_" + System.currentTimeMillis();
                            String reply = bot.baiLianService.generate(
                                    sessionId,
                                    task.getUserId(),
                                    task.getTriggerPrompt(),
                                    task.getGroupId(),
                                    "糖果熊"
                            );
                            if (reply != null && !reply.trim().isEmpty() && task.getGroupId() != null) {
                                bot.sendGroupReply(Long.parseLong(task.getGroupId()), reply);
                            }
                            LocalDateTime nextFire = Main.computeNextFireFromCron(task.getCronExpr());
                            bot.recurringTaskRepo.markFired(task.getId(), nextFire);
                        } catch (Exception e) {
                            logger.error("周期任务执行失败 id={}: {}", task.getId(), e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("周期任务调度异常", e);
                }
            }
        }, "RecurringTask-Scheduler");
        t.setDaemon(true);
        t.start();
        logger.info("周期任务调度器已启动");
    }

    private static void startErrorMonitor(Main bot) {
        bot.errorMonitorService.setBotInstance(bot);
        bot.errorMonitorService.start();
        logger.info("异常自动监控已启动");
    }

    private static long millisUntilNext3AM() {
        return Main.millisUntilNext3AM();
    }
}
