package com.start;

import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.handler.CPTracker;
import com.start.handler.DailyProfessionHandler;
import com.start.handler.HandlerRegistry;
import com.start.runtime.ConversationRuntime;
import com.start.runtime.conversation.ConversationRuntimeConfig;
import com.start.runtime.sticker.StickerHarvesterListener;
import com.start.runtime.trace.DecisionTraceListener;
import com.start.runtime.trace.MetricsListener;
import com.start.runtime.trace.WebDashboardListener;
import com.start.model.LongTermMemory;
import com.start.service.ConversationMetrics;
import com.start.model.RecurringTask;
import com.start.repository.*;
import com.start.service.*;
import static com.start.service.ScheduleExecutor.executeDueEvent;
import com.start.util.DatabaseErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 服务装配与后台任务启动器。
 * 将 Main 从冗长的构造器和 init() 中解放，保持其专注 WebSocket + 消息分发。
 */
public final class BotBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(BotBootstrap.class);

    private BotBootstrap() {}

    /** 创建并装配所有核心服务，注入到 Main 实例。 */
    public static void wireServices(Main bot) {
        // 启动时数据库连通性检查
        if (!DatabaseErrorHandler.isDatabaseAvailable()) {
            logger.error("数据库连接失败，请检查网络、SSH 隧道或数据库服务状态");
        }

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
        if (BotConfig.isVolcTtsEnabled()) {
            bot.ttsService.setVolcTtsEngine(new VolcTtsEngine());
        }

        // 大模型服务
        bot.baiLianService = new BaiLianService(bot.keywordKnowledgeService, bot.userAffinityRepo, bot.ttsService);
        bot.baiLianService.setMoodService(bot.moodService);
        bot.baiLianService.setBotInstance(bot);
        bot.evidenceAnalysisWorker = new EvidenceAnalysisWorker(bot.baiLianService, bot);
        bot.evidenceRetentionService = new EvidenceRetentionService();

        // 异常监控
        bot.errorMonitorService = new ErrorMonitorService(bot.baiLianService);

        // 群聊串行执行器 & Shell 服务
        GroupSerialExecutor groupExecutor = new GroupSerialExecutor(4, 30_000);
        bot.conversationExecutor = groupExecutor;
        ServerAdminService shellService = new ServerAdminService();

        // 工具审计：单例注入，异步写库
        ToolAuditService.init(new ToolAuditLogRepository(DatabaseConfig.getDataSource()));
        // 工具授权服务：集中权限 + 频率限流
        ToolAuthorizationService.init();

        // 运行时事件总线 + 监听器
        ConversationRuntime runtime = new ConversationRuntime();
        bot.conversationRuntime = runtime;
        ConversationMetrics metrics = new ConversationMetrics();
        bot.baiLianService.setConversationMetrics(metrics);
        runtime.addListener(new MetricsListener(metrics));
        runtime.addListener(new DecisionTraceListener());

        // 表情包自动入库服务 + Listener（消费 ImageDescribed 事件）
        StickerIngestService.init().setBaiLianService(bot.baiLianService);
        runtime.addListener(new StickerHarvesterListener());

        // 运行时配置
        ConversationRuntimeConfig config = ConversationRuntimeConfig.defaults();

        // Handler 注册中心
        ConversationManager conversationManager = new ConversationManager();
        bot.handlerRegistry = new HandlerRegistry(bot.baiLianService, groupExecutor, bot, shellService, conversationManager, runtime, config);

        // DashScope API Key
        if (BotConfig.getBaiLianApiKey() != null && !BotConfig.getBaiLianApiKey().isBlank()) {
            System.setProperty("dashscope.api-key", BotConfig.getBaiLianApiKey());
        }
    }

    /** 启动所有后台定时任务（守护线程）。 */
    public static void startBackgroundTasks(Main bot) {
        // 启动自检（5.1）：必需字段 + OneBot WS URL + AI API Key
        validateConfiguration();

        // 防刷检测
        bot.spamDetector = new SpamDetector(bot);
        logger.info("SpamDetector 初始化完成");

        bot.handlerRegistry.startProactiveMonitor(bot);

        // 糖果熊知识种子
        bot.keywordKnowledgeService.seedCandyBearKnowledge();

        // 人生引擎
        CandyBearLifeRepository lifeRepo = new CandyBearLifeRepository(DatabaseConfig.getDataSource());
        CandyBearScheduleRepository scheduleRepo = new CandyBearScheduleRepository(DatabaseConfig.getDataSource());
        CandyBearLifeEngine lifeEngine = new CandyBearLifeEngine(lifeRepo, scheduleRepo, bot.baiLianService);
        bot.baiLianService.setLifeEngine(lifeEngine);
        lifeEngine.onStartup();
        logger.info("糖果熊人生引擎已启动（四层架构：章节->周记->日记->工具查询 + LifeState + 日程表）");

        // 今日首次部署：全员职业重抽（脉系+战力每日随机）
        DailyProfessionHandler.rerollAllProfessions();

        startLifeEngineThread(bot, lifeEngine);
        // Evidence-first profiles replace the legacy free-text overwrite loop.
        logger.info("旧用户画像自动覆盖任务已停用，等待证据画像流水线接管");
        bot.evidenceAnalysisWorker.start();
        bot.evidenceRetentionService.start();
        startReminderService(bot);
        startEventChecker(bot);
        startRecurringScheduler(bot);
        startErrorMonitor(bot);
        startDashboard(bot);
    }

    /**
     * 启动前快速检查：缺关键配置直接拒绝启动（5.1 启动自检）。
     * <p>
     * 注意：BotConfig 静态初始化阶段已经把未替换的 ${VAR} 占位符 fallback 为空值 / 0，
     * 不会在这里之前抛 NumberFormatException。所有"必填项缺失"的判断集中在这里，
     * 并列出每一项，方便部署时一眼看出少配了什么。
     *
     * 在产生外部副作用（数据库连接、迁移、WebSocket 连接）之前校验配置。
     * Main.main() 会在创建 Main 实例前调用；startBackgroundTasks() 仍会再次调用，
     * 防止其他启动入口绕过检查。
     */
    public static void validateConfiguration() {
        List<String> missing = collectMissingConfigs(
                BotConfig.getBotQq(),
                BotConfig.getBaiLianApiKey(),
                BotConfig.getWsUrl(),
                BotConfig.getAllowedGroups(),
                BotConfig.isPrivateWhitelistEnabled(),
                BotConfig.getAllowedPrivateUsers());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "启动失败：缺少必需环境变量/配置：\n  - "
                            + String.join("\n  - ", missing)
                            + "\n请在 application.properties 中配置或通过环境变量注入（参考 .env 模板）。");
        }
        // 数据库连接 + 迁移在 DatabaseConfig.initConnectionPool 内部已检查
        // Dashboard 鉴权在 WebDashboardListener.start() 内部已检查
        logger.info("✅ 启动自检通过（bot.qq + BAILIAN_API_KEY + WS_URL + ALLOWED_GROUPS 全部已配置）");
    }

    /**
     * 必填配置检查（纯函数，方便测试）。
     * 缺哪项就在 missing 列表里加一项人类可读的描述。
     *
     * <p>参数全部从 BotConfig 读取，但签名上只依赖基本类型 + Set，
     * 不耦合 BotConfig 静态状态，可以传 mock snapshot 验证。
     */
    static List<String> collectMissingConfigs(
            long botQq,
            String baiLianApiKey,
            String wsUrl,
            Set<Long> allowedGroups,
            boolean privateWhitelistEnabled,
            Set<Long> allowedPrivateUsers) {
        List<String> missing = new ArrayList<>();
        if (botQq <= 0L) {
            missing.add("BOT_QQ（机器人 QQ 号，application.properties: bot.qq）");
        }
        if (baiLianApiKey == null || baiLianApiKey.isBlank()) {
            missing.add("BAILIAN_API_KEY（百炼 AI API Key，application.properties: bailian.api-key）");
        }
        if (wsUrl == null || wsUrl.isBlank()) {
            missing.add("NAPCT_WS_URL（NapCat WebSocket 地址，application.properties: ws.url）");
        }
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            missing.add("ALLOWED_GROUPS（允许的群号列表，application.properties: allowed.groups）");
        }
        // ALLOWED_PRIVATE_USERS 仅在私聊白名单开启时才是必填；
        // 默认 private.whitelist.enabled=false 时不检查，避免破坏现有 dev 流程。
        if (privateWhitelistEnabled && (allowedPrivateUsers == null || allowedPrivateUsers.isEmpty())) {
            missing.add("ALLOWED_PRIVATE_USERS（私聊白名单用户列表，启用 private.whitelist.enabled=true 时必填）");
        }
        return missing;
    }

    private static void startDashboard(Main bot) {
        WebDashboardListener dashboard = new WebDashboardListener();
        bot.dashboardListener = dashboard;
        if (bot.baiLianService != null) {
            dashboard.setReplayService(new ManualMessageReplayService(DatabaseConfig.getDataSource(),
                    bot.baiLianService::snapshotReplayProfile, bot.baiLianService::generateReplay));
        }
        if (bot.conversationExecutor != null) {
            dashboard.setExecutorMetricsProvider(() -> bot.conversationExecutor.getMetrics());
        }
        // 健康状态注入（5.2）
        dashboard.setHealthProvider(() -> bot.getHealth());
        dashboard.start();
        if (bot.conversationRuntime != null) {
            bot.conversationRuntime.addListener(dashboard);
        }
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
                            Runnable trigger = () -> executeDueEvent(
                                    bot.longTermMemoryRepo,
                                    event,
                                    () -> bot.baiLianService.generate(
                                            "event_" + event.getId(),
                                            event.getUserId(),
                                            prompt,
                                            event.getGroupId(),
                                            "糖果熊"),
                                    bot::sendGroupReply,
                                    bot::sendPrivateReply);
                            if (bot.conversationExecutor != null) {
                                bot.conversationExecutor.execute(event.getGroupId(), trigger);
                            } else {
                                trigger.run();
                            }
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
                            Runnable fire = () -> ScheduleExecutor.executeDueTask(
                                    bot.recurringTaskRepo,
                                    task,
                                    () -> bot.baiLianService.generate(
                                            sessionId,
                                            task.getUserId(),
                                            task.getTriggerPrompt(),
                                            task.getGroupId(),
                                            "糖果熊"),
                                    bot::sendGroupReply,
                                    bot::sendPrivateReply,
                                    () -> Main.computeNextFireFromCron(task.getCronExpr()));
                            if (bot.conversationExecutor != null) {
                                bot.conversationExecutor.execute(task.getGroupId(), fire);
                            } else {
                                fire.run();
                            }
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
