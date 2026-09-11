package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;
import com.start.Main;
import com.start.runtime.trace.WebDashboardListener;
import com.start.agent.social.LuckTool;
import com.start.agent.MemoryTool;
import com.start.memory.BeliefRecall;
import com.start.agent.social.PokeTool;
import com.start.agent.social.ProfessionPKTool;
import com.start.agent.social.ProfessionTool;
import com.start.agent.social.RankTool;
import com.start.agent.RecallMemoryTool;
import com.start.agent.RememberFactTool;
import com.start.agent.ReminderTool;
import com.start.agent.SetBeliefTool;
import com.start.agent.ScheduleEventTool;
import com.start.agent.SearchHistoryTool;
import com.start.agent.AwaitReplyTool;
import com.start.agent.QueryLifeTool;
import com.start.agent.SendGroupTool;
import com.start.agent.SendPrivateTool;
import com.start.agent.SendStatusTool;
import com.start.agent.social.UserAffinityTool;
import com.start.agent.social.UserProfileTool;
import com.start.agent.WebSearchTool;
import com.start.agent.EggGroupSearchTool;
import com.start.agent.EstablishFactTool;
import com.start.agent.social.SanjiaoTool;
import com.start.agent.social.MerchantSubscribeTool;
import com.start.agent.social.TravelingMerchantTool;
import com.start.agent.KnowledgeBaseTool;
import com.start.repository.MerchantRepository;
import com.start.agent.LearnKnowledgeTool;
import com.start.agent.LogEventTool;
import com.start.agent.social.UserAliasTool;
import com.start.agent.VoiceTool;
import com.start.agent.WeatherTool;
import com.start.agent.ShellTool;
import com.start.agent.ScheduleRecurringTaskTool;
import com.start.agent.StickerTool;
import com.start.agent.LinkPreviewTool;
import com.start.agent.WebFetchTool;
import com.start.agent.QueryFileTool;
import com.start.agent.SendFileTool;
import com.start.agent.evo.SelfEvolveTool;
import com.start.agent.evo.RestartBotTool;
import com.start.agent.evo.UpdateConfigTool;
import com.start.agent.evo.ReadCodeTool;
import com.start.agent.evo.CreateFileTool;
import com.start.agent.evo.AuditTool;
import com.start.agent.evo.AuditAgentTool;
import com.start.agent.DigestTool;
import com.start.agent.SearchDigestTool;
import com.start.agent.evo.EvolutionHistoryTool;
import com.start.agent.QueryImagesTool;
import com.start.agent.SetWorkingMemoryTool;
import com.start.agent.WhitelistTool;
import com.start.model.WorkingMemory;
import com.start.repository.BeliefRepository;
import com.start.repository.MessageRepository;
import com.start.repository.RecurringTaskRepository;
import com.start.repository.ThreadRepository;
import com.start.repository.WorkingMemoryRepository;
import com.start.service.EggGroupDataCenter;
import com.start.thread.SceneState;
import com.start.thread.ThreadManager;
import com.start.util.BailianRetryExecutor;
import com.hankcs.hanlp.HanLP;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.model.ConversationThread;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserProfileRepository;
import com.start.repository.BotMemoryRepository;
import com.start.repository.EvolutionRecordRepository;
import com.start.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * 百炼大模型服务类 (BaiLian Service)
 * <p>
 * 本类是 QQ 机器人核心智能交互模块，主要负责处理用户消息、维护对话上下文、
 * 调用大语言模型（LLM）生成回复，并集成 Agent 工具调用能力。
 * </p>
 *
 * <h3>主要功能特性：</h3>
 * <ul>
 *     <li><b>多模态上下文管理</b>：维护会话历史（Session History），支持群聊公共上下文、用户个人画像及好感度注入。</li>
 *     <li><b>RAG 知识库增强</b>：集成 {@link KeywordKnowledgeService}，在生成回复前检索相关知识库内容，提高回答准确性。</li>
 *     <li><b>工具调用</b>：支持 40+ 工具动态执行（天气、搜索、记忆、语音等），多轮 function calling 循环。</li>
 *     <li><b>拟人化交互逻辑</b>：
 *         <ul>
 *             <li>内置糖果熊人设，控制回复风格（简短、自然、偶尔可爱）。</li>
 *             <li>支持主动插话机制（基于话题兴趣、历史互动频率）。</li>
 *             <li>具备追问识别能力，能针对上一轮 AI 回复进行连贯对话。</li>
 *         </ul>
 *     </li>
 *     <li><b>频率控制与防刷屏</b>：针对群聊场景实施每分钟发言上限限制，以及主动插话的时间窗口控制。</li>
 *     <li><b>双模型架构</b>：
 *         <ul>
 *             <li>主聊天模型：使用 MiniMax-M2.5 (via scnet.cn)，侧重自然语言交流与角色扮演。</li>
 *             <li>Agent/任务模型：使用 Qwen-Max (via Aliyun DashScope)，侧重逻辑判断与工具调用。</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <h3>核心方法说明：</h3>
 * <ul>
 *     <li>{@link #generate(String, String, String, String, String)}：主入口，处理普通聊天消息，返回 AI 回复文本。</li>
 *     <li>{@link #shouldReactToGroupMessage}：决策是否需要对群内非 @ 消息进行主动回应。</li>
 *     <li>{@link #recordPublicGroupMessage}：记录群内公共消息，用于构建群聊背景上下文。</li>
 * </ul>
 *
 * @author alice
 * @version 1.0
 * @see com.start.agent.Tool
 * @see com.start.service.KeywordKnowledgeService
 */
public class BaiLianService {
    private final KeywordKnowledgeService knowledgeService;
    private final UserAffinityRepository userAffinityRepo;

    private static final Logger logger = LoggerFactory.getLogger(BaiLianService.class);
    private static final long BOT_QQ = BotConfig.getBotQq();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BEIJING_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy年M月d日 EEEE HH:mm:ss '北京时间'",
            Locale.CHINA
    );


    private final UserProfileRepository profileRepo = new UserProfileRepository(DatabaseConfig.getDataSource());
    private final UserAliasRepository userAliasRepo = new UserAliasRepository();
    private BotMoodService moodService;
    private CandyBearLifeEngine lifeEngine;
    private final GameStateService gameStateService = new GameStateService();
    private final BotMemoryService botMemory = new BotMemoryService(new BotMemoryRepository(DatabaseConfig.getDataSource()));
    private Main botInstance;

    private ConversationMetrics metrics;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ProfileProvider profileProvider = new ProfileProvider();
    private ThreadManager threadManager;
    private final ConcurrentHashMap<String, Long> lastThreadUpdateMs = new ConcurrentHashMap<>();
    public void setMoodService(BotMoodService moodService) { this.moodService = moodService; }
    public BotMoodService getMoodService() { return moodService; }
    public void setLifeEngine(CandyBearLifeEngine e) { this.lifeEngine = e; }
    public void setBotInstance(Main bot) { this.botInstance = bot; }
    public void setConversationMetrics(ConversationMetrics m) { this.metrics = m; }
    public ConversationMetrics getConversationMetrics() { return metrics; }
    public GameStateService getGameStateService() { return gameStateService; }
    public BotMemoryService getBotMemory() { return botMemory; }

    public ThreadManager getThreadManager() {
        if (threadManager == null) {
            threadManager = new ThreadManager(
                    new ThreadRepository(DatabaseConfig.getDataSource()),
                    (groupId, count) -> {
                        Deque<PublicMessage> history = publicGroupHistory.get(groupId);
                        if (history == null || history.isEmpty()) return java.util.List.of();
                        java.util.List<PublicMessage> all = new java.util.ArrayList<>(history);
                        int start = Math.max(0, all.size() - count);
                        return all.subList(start, all.size()).stream()
                                .map(m -> m.content)
                                .collect(Collectors.toList());
                    });
        }
        return threadManager;
    }

    /** AIHandler 调用：将消息归入 Thread 并更新群聊场景。同群 5 秒内去重。 */
    public void processThreadMessage(String groupId, String userId, String message) {
        if (groupId == null) return;
        long now = System.currentTimeMillis();
        Long last = lastThreadUpdateMs.get(groupId);
        if (last != null && now - last < 5000) return;
        lastThreadUpdateMs.put(groupId, now);
        try {
            getThreadManager().processMessage(groupId, userId, message, BOT_QQ);
        } catch (Exception e) {
            logger.warn("Thread processing failed, groupId={}, userId={}", groupId, userId, e);
        }
    }

    private final String baiLianApiKey = BotConfig.getBaiLianApiKey();
    private final String baiLianBaseUrl = BotConfig.getBaiLianBaseUrl();
    private final String bailianChatModel = BotConfig.getBaiLianChatModel();
    private final int bailianTimeoutMs = BotConfig.getBaiLianTimeoutMs();
    private final int bailianMaxRetries = BotConfig.getBaiLianMaxRetries();
    private final String baiLianReasoningEffort = BotConfig.getBaiLianReasoningEffort();

    private final String agentApiKey = BotConfig.getAgentApiKey();
    private final String agentBaseUrl = BotConfig.getAgentBaseUrl();
    private final String agentModel = BotConfig.getAgentModel();
    private final String agentReasoningEffort = BotConfig.getAgentReasoningEffort();

    private final int agentTimeoutMs = BotConfig.getAgentTimeoutMs();
    private final int agentMaxRetries = BotConfig.getAgentMaxRetries();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(BotConfig.getHttpConnectTimeoutMs()))
            .executor(Executors.newFixedThreadPool(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TtsService ttsService;
    private final EggGroupDataCenter eggGroupDataCenter = new EggGroupDataCenter();
    private MerchantApiService merchantApiService;
    private MerchantRepository merchantRepo;
    private ServerAdminService shellService;

    private final RuntimeConfigService runtimeConfig = new RuntimeConfigService();
    private volatile RuleSet ruleSet;

    public void setMerchantApiService(MerchantApiService s) { this.merchantApiService = s; }
    public void setMerchantRepo(MerchantRepository r) { this.merchantRepo = r; }
    public void setShellService(ServerAdminService s) { this.shellService = s; }

    public RuntimeConfigService getRuntimeConfig() { return runtimeConfig; }

    private RuleSet getRuleSet() {
        if (ruleSet == null) {
            synchronized (this) {
                if (ruleSet == null) {
                    ruleSet = RuleSetLoader.load(runtimeConfig);
                }
            }
        }
        return ruleSet;
    }

    public BaiLianService(KeywordKnowledgeService knowledgeService, UserAffinityRepository userAffinityRepo, TtsService ttsService) {
        this.knowledgeService = Objects.requireNonNull(knowledgeService, "knowledgeService cannot be null");
        this.userAffinityRepo = Objects.requireNonNull(userAffinityRepo, "userAffinityRepo cannot be null");
        this.ttsService = Objects.requireNonNull(ttsService, "ttsService cannot be null");
    }
    // === 上下文管理 ===
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>(); // sessionId -> 消息历史
    private final Map<String, Long> lastClearTime = new ConcurrentHashMap<>();

    // === 主动插话控制（与 ConversationStateStore 共享） ===
    final Map<String, Deque<Long>> groupReactionHistory = new ConcurrentHashMap<>();
    private final AIDatabaseService aiDatabaseService = new AIDatabaseService();


    public AIDatabaseService getAiDatabaseService() { return aiDatabaseService; }

    /** 创建与 BaiLianService 共享状态的 StateStore */
    public com.start.runtime.conversation.ConversationStateStore createSharedStateStore() {
        return new com.start.runtime.conversation.InMemoryConversationStateStore(
                userThreads, groupContexts, pendingAwaits, groupReactionHistory);
    }
    // === 新增：糖果熊发言频率控制（每分钟上限）===
    private final Map<String, Deque<Long>> botMessageHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private static final int MAX_MESSAGES_PER_MINUTE = 10; // 每分钟最多发言次数

    // === 对话线程追踪（与 ConversationStateStore 共享） ===
    final Map<String, UserThread> userThreads = new ConcurrentHashMap<>();
    final Map<String, Deque<ContextEvent>> groupContexts = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> suppressSessionWrite = ThreadLocal.withInitial(() -> false); // PR4: 控制 generate() 是否写 sessions

    /** 待处理文件缓存 — key = sessionKey(group_xxx_yyy / private_xxx), value = 文件元数据列表 */
    private final Map<String, List<Map<String, String>>> pendingFiles = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_FILES_PER_SESSION = 10;
    private static final int MAX_PENDING_SESSIONS = 50;

    public void addPendingFiles(String sessionKey, List<Map<String, String>> files) {
        pendingFiles.merge(sessionKey, files, (old, neu) -> {
            old.addAll(neu);
            // 每会话最多保留 MAX_PENDING_FILES_PER_SESSION 个
            while (old.size() > MAX_PENDING_FILES_PER_SESSION) old.remove(0);
            return old;
        });
        // 全局会话数限制，超出则淘汰最旧的
        while (pendingFiles.size() > MAX_PENDING_SESSIONS) {
            String oldest = pendingFiles.keySet().iterator().next();
            pendingFiles.remove(oldest);
        }
    }

    public List<Map<String, String>> getPendingFiles(String sessionKey) {
        List<Map<String, String>> files = pendingFiles.get(sessionKey);
        return files != null ? files : Collections.emptyList();
    }

    /** 移除指定 file_id 的待处理文件，返回是否移除成功 */
    public boolean removePendingFile(String sessionKey, String fileId) {
        List<Map<String, String>> files = pendingFiles.get(sessionKey);
        if (files == null) return false;
        boolean removed = files.removeIf(f -> fileId.equals(f.get("file_id")));
        if (files.isEmpty()) pendingFiles.remove(sessionKey);
        return removed;
    }

    public List<Map<String, String>> getAndClearPendingFiles(String sessionKey) {
        List<Map<String, String>> files = pendingFiles.remove(sessionKey);
        return files != null ? files : Collections.emptyList();
    }

    // 内部类 — public 供 StateStore 共享
    public static class UserThread {
        public final long lastInteraction;
        public final String lastBotReply;

        public UserThread(long time, String reply) {
            this.lastInteraction = time;
            this.lastBotReply = reply;
        }
    }

    public static class ContextEvent {
        public final long timestamp;
        public final String type;
        public final String content;
        public final String userId;
        public final String senderNick;

        public ContextEvent(long ts, String type, String content, String userId, String nick) {
            this.timestamp = ts;
            this.type = type;
            this.content = content;
            this.userId = userId;
            this.senderNick = nick;
        }
    }

    // 消息结构（用于会话历史）
    public static class Message {
        public String role;
        public String content;
        public long timestamp;  // 该条消息的时刻（epoch millis），跨天判断和时间戳注入用

        public Message(String role, String content) {
            this(role, content, System.currentTimeMillis());
        }

        public Message(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    private record ToolResult(String name, String result) {}

    // === 异步等待回复 ===
    private final Map<String, PendingAwait> pendingAwaits = new ConcurrentHashMap<>(); // key = groupId_userId

    public static class PendingAwait {
        public final String groupId;
        public final String targetUserId;
        public final String targetNickname;
        public final String question;
        public final String context;
        public final String sessionId;
        public final long createdAt;
        public final long timeoutMs;

        public PendingAwait(String groupId, String targetUserId, String targetNickname,
                            String question, String context, String sessionId, long timeoutMs) {
            this.groupId = groupId;
            this.targetUserId = targetUserId;
            this.targetNickname = targetNickname;
            this.question = question;
            this.context = context;
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.timeoutMs = timeoutMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > timeoutMs;
        }
    }

    /** 注册异步等待：AI 问了某人一个问题，等待其回复 */
    public void registerAwait(String groupId, String targetUserId, String targetNickname,
                              String question, String context, String sessionId, long timeoutMs) {
        String key = groupId + "_" + targetUserId;
        pendingAwaits.put(key, new PendingAwait(groupId, targetUserId, targetNickname,
                question, context, sessionId, timeoutMs));
    }

    /** 取消对某用户的异步等待（主动触发或追问时调用） */
    public void cancelPendingAwait(String groupId, String userId) {
        String key = groupId + "_" + userId;
        PendingAwait removed = pendingAwaits.remove(key);
        if (removed != null) {
            logger.debug("async await cancelled: {} -> {}", key, removed.question);
        }
    }

    /** 清理所有过期的异步等待 */
    void purgeExpiredAwaits() {
        pendingAwaits.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // ===== 公共方法 =====

    public void clearContext(String sessionId) {
        // 只设标记，由下次 generate() 实际清理。避免跨线程 race。
        lastClearTime.put(sessionId, System.currentTimeMillis());
    }

    /** 设 true 时 generate() 不写内存会话，需调用方在消息送达后调 commitGeneration()。 */
    public void setSuppressSessionWrite(boolean suppress) {
        suppressSessionWrite.set(suppress);
    }

    /**
     * 延迟提交：在生成回复并发送成功后调用，提交内存会话和运行时追踪数据。
     * 用于配合 setSuppressSessionWrite(true) 的 generate() 调用。
     */
    public void commitGeneration(String sessionId, String userId, String userPrompt,
                                  String reply, String groupId) {
        List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (history) {
            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }
            history.add(new Message("user", userPrompt));
            history.add(new Message("assistant", reply));
        }

        if (groupId != null) {
            recordUserInteraction(groupId, userId, reply);

            Deque<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());
            synchronized (msgHistory) {
                long now = System.currentTimeMillis();
                msgHistory.removeIf(ts -> now - ts > 60_000);
                if (msgHistory.size() < MAX_MESSAGES_PER_MINUTE) {
                    msgHistory.addLast(now);
                }
            }
        }
    }

    /** 将 OneBot 已解析出的 @ 用户与正文重新拼成模型可读的上下文。 */
    private String buildAtMentionContext(String prompt, List<Long> atUserIds,
                                         Map<String, String> groupMembers) {
        if (atUserIds == null || atUserIds.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【本条消息的 @ 信息】");
        sb.append("\n正文（@ 用户段已从正文中分离）：").append(prompt == null ? "" : prompt);
        sb.append("\n本条消息实际 @ 的用户：");
        Set<Long> unique = new LinkedHashSet<>(atUserIds);
        boolean first = true;
        for (Long qq : unique) {
            if (qq == null) continue;
            if (!first) sb.append("、");
            first = false;
            String displayName = qq == BOT_QQ ? "糖果熊"
                    : groupMembers != null ? groupMembers.get(String.valueOf(qq)) : null;
            sb.append("@").append(displayName == null || displayName.isBlank() ? "用户" : displayName)
                    .append("(QQ=").append(qq).append(")");
        }
        sb.append("\n这是协议层真实 @ 数据，QQ 号是唯一身份，昵称只用于理解和展示。")
                .append("需要记录别称时，target_user_id 必须使用被 @ 用户的 QQ 号。");
        return sb.toString();
    }

    public static String getBeijingTimeString() {
        // 1. 定义北京时区 (Asia/Shanghai 等同于北京时间)
        ZoneId beijingZone = ZoneId.of("Asia/Shanghai");

        // 2. 获取该时区的当前时间
        ZonedDateTime now = ZonedDateTime.now(beijingZone);

        // 3. 定义格式化器
        // yyyy年M月d日: 日期
        // EEEE: 完整的星期名称 (如：星期日)
        // HH:mm:ss: 24小时制时间
        // '北京时间': 固定文本
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "yyyy年M月d日 EEEE HH:mm:ss '北京时间'",
                Locale.CHINA // 确保星期和月份显示为中文
        );

        // 4. 返回格式化后的字符串
        return now.format(formatter);
    }

    public static String getTodayDateStr() {
        return java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static String getYesterdayDateStr() {
        return java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static String getDayBeforeYesterdayStr() {
        return java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // 调用 AI（同步），返回第一条短回复（或空字符串表示不应回复）
    /**
     * 生成 AI 回复消息。
     *
     * 该方法整合了知识库检索（用于上下文增强）和百炼大模型调用，
     * 并维护会话历史、频率控制等逻辑，最终返回 AI 的自然语言回复。
     *
     * @param sessionId   会话唯一标识，用于维护对话上下文
     * @param userId      用户唯一标识
     * @param userPrompt  用户当前输入的提示文本
     * @param groupId     群组 ID（若为私聊可为 null）
     * @return AI 生成的回复文本；若失败或被限流则返回默认兜底语句
     */
    /**
     * 生成AI回复的核心方法
     *
     * @param sessionId 会话ID，用于维护对话历史
     * @param userId 用户QQ号
     * @param userPrompt 用户发送的消息内容
     * @param groupId 群组ID（若为私聊则为null）
     * @param nickname 用户昵称
     * @return AI生成的回复内容，若因限流等原因不回复则返回空字符串或兜底文本
     */
    public String generate(String sessionId, String userId, String userPrompt, String groupId, String nickname) {
        return generate(sessionId, userId, userPrompt, groupId, nickname, Collections.emptyList(), false).reply();
    }

    public String generate(String sessionId, String userId, String userPrompt, String groupId, String nickname, List<Long> atUserIds) {
        return generate(sessionId, userId, userPrompt, groupId, nickname, atUserIds, false).reply();
    }

    /** 兼容旧调用方，同时允许聊天主模型直接接收本轮图片。 */
    public String generate(String sessionId, String userId, String userPrompt, String groupId, String nickname,
                           List<Long> atUserIds, List<String> imageDataUris) {
        return generate(sessionId, userId, userPrompt, groupId, nickname, atUserIds, false,
                Collections.emptySet(), ConversationEvent.NOTHING, null, imageDataUris).reply();
    }

    /** 基于 ConversationSession 的生成入口。 */
    public GenerationResult generate(com.start.runtime.conversation.ConversationSession session) {
        return generate(session.sessionId(), session.userId(), session.userPrompt(),
                session.groupId(), session.nickname(), session.atUserIds(), session.allowSilence(),
                session.excludedPublicMessageIds(), session.event(), session.dialoguePlan(), session.imageDataUris());
    }

    /** 带沉默权的生成方法。allowSilence=true 时模型可以输出 <NO_REPLY> 选择沉默。 */
    public GenerationResult generate(String sessionId, String userId, String userPrompt, String groupId, String nickname, List<Long> atUserIds, boolean allowSilence) {
        return generate(sessionId, userId, userPrompt, groupId, nickname, atUserIds, allowSilence,
                Collections.emptySet(), ConversationEvent.NOTHING, null, Collections.emptyList());
    }

    private GenerationResult generate(String sessionId, String userId, String userPrompt, String groupId,
                                      String nickname, List<Long> atUserIds, boolean allowSilence,
                                      java.util.Set<String> excludedPublicMessageIds,
                                      ConversationEvent event, GroupDialoguePlan dialoguePlan,
                                      List<String> imageDataUris) {
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}], ats={}, allowSilence={}", sessionId, userPrompt, atUserIds, allowSilence);

        String context = "";
        String agentToolContext = "";
        String publicGroupContext = "";
        String timeContext = "【当前时间】是：" + getBeijingTimeString()
            + "\n调用 search_chat_history / recall_memory 查日期时，用 yyyy-MM-dd 格式。今天=" + getTodayDateStr()
            + "（昨天/前天请自行 -1d / -2d）。"
            + "session history 已带 [MM-dd HH:mm] 时间戳，请直接看时间戳判断每条消息的时序；"
            + "**【铁律】不允许在正常回复里带任何时间戳前缀**（如 [08-14 16:32] / [16:32] / (16:32) 等），"
            + "也不允许模仿历史消息的时间戳格式输出。时间戳仅供内部判断时序，永远不要复述、引用、模仿；"
            + "只有用户明确要求查询或说明时间时才输出时间信息。"
            + "若看到时间戳跨天/隔夜，说明是新的一天。";

        int publicContextLimit = publicContextLimit(event);
        if (groupId != null && publicContextLimit > 0) {
            Deque<PublicMessage> recent = getPublicGroupHistory(groupId);
            publicGroupContext = buildPublicGroupContext(recent, publicContextLimit, excludedPublicMessageIds);
        }

        context = profileProvider.getProfileContext(userId, groupId);

        KeywordKnowledgeService.KnowledgeResult knowledgeResult =
                knowledgeService.query(userPrompt, userId, groupId);

        String knowledgeContext = "";

        if (knowledgeResult != null &&
                knowledgeResult.similarityScore >= 0.3 &&
                knowledgeResult.answer != null &&
                !knowledgeResult.answer.trim().isEmpty()) {

            knowledgeContext = knowledgeResult.answer.trim();
            logger.info("📚 知识库命中（用于上下文增强）: 关键词={}, 分数={}",
                    knowledgeResult.matchedKeywords, knowledgeResult.similarityScore);
        } else {
            logger.debug("📚 知识库未命中或分数过低: 分数={}, 答案={}",
                    knowledgeResult != null ? knowledgeResult.similarityScore : "null",
                    knowledgeResult != null && knowledgeResult.answer != null ? "有效" : "无效");
        }

        try {
            boolean suppress = suppressSessionWrite.get();

            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            synchronized (history) {
                if (lastClearTime.containsKey(sessionId)) {
                    history.clear();
                    lastClearTime.remove(sessionId);
                } else if (!history.isEmpty()) {
                    // 跨段清理：最后一条消息距今超过 4 小时，视为新的一段对话
                    long now = System.currentTimeMillis();
                    long lastTs = history.get(history.size() - 1).timestamp;
                    if (now - lastTs > 4 * 60 * 60 * 1000L) {
                        history.clear();
                        logger.debug("session 跨段清理（>4h）: sessionId={}, lastTs={}", sessionId, lastTs);
                    }
                }

                if (!suppress) {
                    history.add(new Message("user", userPrompt));
                }
            }

            // === 构建 PromptContext ===
            PromptContext ctx = new PromptContext()
                    .nickname(nickname).userId(userId).groupId(groupId)
                    .isGuier(String.valueOf(BotConfig.getAdminQq()).equals(userId))
                    .userProfileText(context)
                    .knowledgeContext(knowledgeContext)
                    .atUserIds(atUserIds).botQq(BOT_QQ)
                    .dialoguePlan(dialoguePlan);

            if (moodService != null) {
                String gk = groupId != null ? groupId : "private_" + userId;
                ctx.moodDescription(moodService.getMoodDescription(gk));
            }

            // 会话认知加载（ConversationBelief → BeliefRecall）
            final BeliefRepository beliefRepo = groupId != null
                    ? new BeliefRepository(DatabaseConfig.getDataSource()) : null;
            if (beliefRepo != null) {
                try {
                    List<BeliefRecall> beliefRecalls = beliefRepo.findActiveByGroupAndUser(groupId, userId)
                            .stream()
                            .map(b -> new BeliefRecall(
                                    b.getTopic(), b.getUserEmotion(), b.getBotIntent(),
                                    b.getUnresolvedQuestion(),
                                    (int) java.time.Duration.between(b.getUpdatedAt(), java.time.Instant.now()).toMinutes(),
                                    b.isActive()))
                            .toList();
                    if (!beliefRecalls.isEmpty()) {
                        ctx.beliefRecalls(beliefRecalls);
                    }
                } catch (Exception e) {
                    logger.debug("Belief loading skipped: {}", e.getMessage());
                }
            }

            // 群聊场景加载（ConversationThread → SceneState）
            WorkingMemory wm = null;
            if (groupId != null && threadManager != null) {
                try {
                    SceneState scene = threadManager.getScene(groupId, BOT_QQ);
                    if (scene != null && !scene.isEmpty()) {
                        ctx.sceneState(scene);
                        // 工作记忆加载（绑定当前 Thread 的 WorkingMemory）
                        Long currentThreadId = findCurrentThreadId(scene);
                        if (currentThreadId != null) {
                            WorkingMemoryRepository wmRepo = new WorkingMemoryRepository(DatabaseConfig.getDataSource());
                            wm = wmRepo.findActiveByThread(currentThreadId);
                            // 生命周期自动检测：用户消息含"取消/结束"意图 + 有 ACTIVE 任务 → 自动关闭
                            // 防止 LLM 忘记调 set_working_memory(COMPLETED) 导致幽灵任务
                            if (wm != null) {
                                // 生命周期自动检测：纯函数判断是否应关闭，副作用由我们执行
                                MemoryLifecycleDetector detector = new MemoryLifecycleDetector();
                                if (detector.shouldComplete(wm, userPrompt)) {
                                    wmRepo.markCompleted(wm.getId());
                                    logger.debug("WorkingMemory auto-completed by lifecycle detector (wm_id={})", wm.getId());
                                    wm = null; // 本轮不再注入
                                }
                            }
                            if (wm != null) {
                                ctx.workingMemory(wm);
                                // Belief 是用户级认知，不该被 Thread 覆盖。
                                // 原逻辑：非 Thread 参与者时清空 belief → 会让"路过群但一直在聊"的用户丢认知。
                                // 改为：只按时间窗口过滤，超过 2 小时的 Belief 自动失活，不注入。
                                if (ctx.beliefRecalls != null && !ctx.beliefRecalls.isEmpty()) {
                                    ctx.beliefRecalls(ctx.beliefRecalls.stream()
                                            .filter(b -> b.ageMinutes() >= 0 && b.ageMinutes() < 120)
                                            .toList());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Scene loading failed, groupId={}, userId={}", groupId, userId, e);
                }
            }

            // 别称只由模型主动调用 manage_alias 写入；这里仅准备完整的 @ 上下文。
            Map<String, UserAliasRepository.AliasInfo> aliasInfoMap;
            if (groupId != null) {
                aliasInfoMap = userAliasRepo.getGroupAliasInfoMap(groupId);
            } else {
                aliasInfoMap = new java.util.LinkedHashMap<>();
            }
            if (!aliasInfoMap.containsKey(userId)) {
                UserAliasRepository.AliasInfo info = new UserAliasRepository.AliasInfo();
                userAliasRepo.getBestAlias(userId, groupId != null ? groupId : "0").ifPresent(a -> { info.bestAlias = a; info.aliases.add(a); });
                userAliasRepo.getLocation(userId, groupId != null ? groupId : "0").ifPresent(l -> info.primaryLocation = l);
                if (info.bestAlias != null) aliasInfoMap.put(userId, info);
            }
            ctx.aliasInfoMap(aliasInfoMap);
            userAliasRepo.getLocation(userId, groupId != null ? groupId : "0").ifPresent(ctx::userLocation);

            // @ 状态
            boolean isAtBot = atUserIds != null && atUserIds.contains(BOT_QQ);
            ctx.isAtBot(isAtBot);
            if (atUserIds != null) {
                ctx.otherAts(atUserIds.stream().filter(q -> q != BOT_QQ).toList());
            }

            // 游戏状态
            if (groupId != null) {
                ctx.spyGameDesc(gameStateService.getOrCreateSpy(groupId).getDescription());
                ctx.numberGameDesc(gameStateService.getOrCreateNumber(groupId).getDescription());
            }

            ctx.publicGroupContext(publicGroupContext)
               .timeContext(timeContext)
               .allowSilence(allowSilence);

            // 群成员全量（QQ → 显示名）—— 让 LLM @ 时知道 QQ 是什么，不瞎猜
            if (groupId != null && botInstance != null && botInstance.getOneBotWsService() != null) {
                try {
                    long gidLong = Long.parseLong(groupId);
                    Map<String, String> members = botInstance.getOneBotWsService()
                            .getGroupMemberDisplayNamesAsync(gidLong)
                            .get(5, TimeUnit.SECONDS);
                    if (members != null && !members.isEmpty()) {
                        ctx.groupMembers(members);
                    }
                } catch (Exception e) {
                    logger.debug("群成员拉取失败 gid={}: {}", groupId, e.getMessage());
                }
            }

            ctx.atMentionContext(buildAtMentionContext(userPrompt, atUserIds, ctx.groupMembers));

            // 群聊节奏
            if (metrics != null && groupId != null) {
                ctx.metricsHint(metrics.getSnapshot(groupId).toPromptHint());
            }

            // 待处理文件
            List<Map<String, String>> pendingFilesForSession = getPendingFiles(sessionId);
            if (!pendingFilesForSession.isEmpty()) {
                StringBuilder fb = new StringBuilder("\n\n【待处理文件】当前会话收到 ").append(pendingFilesForSession.size()).append(" 个文件：\n");
                for (Map<String, String> f : pendingFilesForSession) {
                    fb.append("- ").append(f.getOrDefault("file_name", "未知"));
                    String sz = f.get("file_size");
                    if (sz != null && !sz.isEmpty()) {
                        try { long bs = Long.parseLong(sz); fb.append("（").append(bs < 1024 ? bs + "B" : bs < 1048576 ? bs/1024 + "KB" : bs/1048576 + "MB").append("）"); } catch (NumberFormatException ignored) {}
                    }
                    fb.append(" | file_id=").append(f.get("file_id")).append("\n");
                }
                fb.append("用 query_file action=summarize file_id=xxx 让副AI读取总结。需要原文细节时用 action=extract。");
                ctx.pendingFilesHint(fb.toString());
            }

            // 关键词提取：供 recall_memory 工具的 autoKeywords 使用
            // 不再自动注入记忆到 prompt —— 关键词匹配 ≠ 语义相关，LLM 自己判断什么时候该查
            List<String> hanlpKeywords = extractKeywords(userPrompt);

            // 所有动态上下文（别称、@ 用户、群成员、文件、节奏等）准备完后再构建 prompt，
            // 否则这些信息只写进 ctx，却不会真正进入发给模型的 system prompt。
            String overridePrompt = runtimeConfig.get("system_prompt_override");
            String promptPatch = runtimeConfig.get("system_prompt_patch");
            ctx.promptPatch(promptPatch);

            String systemPrompt;
            if (overridePrompt != null && !overridePrompt.isBlank()) {
                systemPrompt = promptBuilder.build(overridePrompt, ctx);
            } else {
                systemPrompt = promptBuilder.buildRuleBook(getRuleSet(), ctx);
            }

            logger.debug("已构建 AI system prompt，sessionId={}, chars={}", sessionId, systemPrompt.length());

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            java.time.format.DateTimeFormatter shortTs = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
            List<Message> historySnapshot;
            synchronized (history) {
                historySnapshot = List.copyOf(history);
            }
            int snapshotStart = Math.max(0, historySnapshot.size() - 4);
            for (int i = snapshotStart; i < historySnapshot.size(); i++) {
                Message msg = historySnapshot.get(i);
                String role = "user".equals(msg.role) ? "user" : "assistant";

                String content = msg.content;
                if (content.length() > 600) {
                    content = content.substring(0, 600) + "...";
                }

                // 给 history 注入 [MM-dd HH:mm] 时间戳前缀，让 LLM 知道每条消息的时序
                String timePrefix = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(msg.timestamp),
                        java.time.ZoneId.of("Asia/Shanghai")
                ).format(shortTs);
                String messageContent = "[" + timePrefix + "] " + content;
                messages.add(buildUserMessage(messageContent, imageDataUris));
            }

            // PR4: suppress 模式下 user message 不在 history 中，手动追加到 messages 数组
            if (suppress) {
                messages.add(buildUserMessage(userPrompt, imageDataUris));
            }

            String url = this.baiLianBaseUrl;
            String apiKey = this.baiLianApiKey;
            String modelName = this.bailianChatModel;

            // 构建工具列表及 OpenAI 原生 function calling specs
            LongTermMemoryRepository ltmRepo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
            RecallMemoryTool recallMemoryTool = new RecallMemoryTool(ltmRepo);
            recallMemoryTool.setAutoKeywords(hanlpKeywords);

            EvolutionRecordRepository evoRepo = new EvolutionRecordRepository(DatabaseConfig.getDataSource());

            boolean isAdmin = String.valueOf(BotConfig.getAdminQq()).equals(userId);

            final List<Tool> availableTools = new ArrayList<>(Arrays.asList(
                    new WeatherTool(userAliasRepo),
                    new UserAffinityTool(userAffinityRepo),
                    new UserAliasTool(userAliasRepo, String.valueOf(BotConfig.getBotQq()),
                            userId, groupId, atUserIds),
                    new SendPrivateTool(botInstance, userId),
                    new PokeTool(botInstance),
                    new VoiceTool(botInstance, ttsService),
                    new RankTool(),
                    new ReminderTool(),
                    new LuckTool(),
                    new ProfessionTool(),
                    new ProfessionPKTool(),
                    new UserProfileTool(profileRepo),
                    new MemoryTool(botMemory),
                    new KnowledgeBaseTool(knowledgeService),
                    new LearnKnowledgeTool(knowledgeService, userId),
                    new SendGroupTool(botInstance, userId),
                    new SendFileTool(botInstance),
                    new QueryFileTool(botInstance, this, userId, sessionId),
                    new SearchHistoryTool(ltmRepo),
                    new RememberFactTool(ltmRepo, userId, groupId),
                    new EstablishFactTool(ltmRepo),
                    recallMemoryTool,
                    new ScheduleEventTool(ltmRepo, userId, groupId),
                    new SendStatusTool(botInstance, groupId, userId),
                    new WebSearchTool(),
                    new SanjiaoTool(botInstance, groupId),
                    new EggGroupSearchTool(eggGroupDataCenter),
                    new TravelingMerchantTool(merchantApiService != null ? merchantApiService : new MerchantApiService()),
                    new MerchantSubscribeTool(merchantRepo != null ? merchantRepo : new MerchantRepository()),
                    new AwaitReplyTool(botInstance, this, groupId, userId, sessionId),
                    new QueryLifeTool(lifeEngine),
                    new LogEventTool(new EventLogRepository(DatabaseConfig.getDataSource())),
                    new ScheduleRecurringTaskTool(new RecurringTaskRepository(DatabaseConfig.getDataSource())),
                    new StickerTool(botInstance),
                    new LinkPreviewTool(),
                    new WebFetchTool(),
                    new DigestTool(),
                    new SearchDigestTool(),
                    new EvolutionHistoryTool(evoRepo),
                    new QueryImagesTool(new MessageRepository())
            ));
            // 归儿专属工具（code/audit/shell/self_evolve），非管理员不发，省 token
            if (isAdmin) {
                availableTools.addAll(Arrays.asList(
                        new ShellTool(shellService != null ? shellService : new ServerAdminService(), userId),
                        new SelfEvolveTool(userId, evoRepo),
                        new RestartBotTool(userId),
                        new UpdateConfigTool(runtimeConfig, userId),
                        new WhitelistTool(userId),
                        new ReadCodeTool(),
                        new CreateFileTool(userId),
                        new AuditTool(),
                        new AuditAgentTool()
                ));
            }
            // Phase 3: 工作记忆存在时，set_belief 退场，由 set_working_memory 接管
            if (wm == null) {
                availableTools.add(new SetBeliefTool(
                        beliefRepo != null ? beliefRepo : new BeliefRepository(DatabaseConfig.getDataSource()),
                        groupId, userId));
            }
            availableTools.add(new SetWorkingMemoryTool(
                    new WorkingMemoryRepository(DatabaseConfig.getDataSource()),
                    new ThreadRepository(DatabaseConfig.getDataSource()), groupId, BOT_QQ));

            List<Map<String, Object>> toolSpecs = availableTools.stream()
                    .map(Tool::getFunctionSpec)
                    .collect(Collectors.toList());

            // 热重载：工具描述可从运行时配置覆盖
            for (Map<String, Object> spec : toolSpecs) {
                Object fnObj = spec.get("function");
                if (fnObj instanceof Map<?, ?> fn) {
                    String name = (String) fn.get("name");
                    if (name != null) {
                        String overrideDesc = runtimeConfig.get("tool_desc_" + name);
                        if (overrideDesc != null && !overrideDesc.isBlank()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> fnMap = (Map<String, Object>) fn;
                            fnMap.put("description", overrideDesc);
                        }
                    }
                }
            }

            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);
            requestBodyObj.put("max_tokens", 1024);
            addReasoningEffort(requestBodyObj, baiLianReasoningEffort);
            requestBodyObj.put("tools", toolSpecs);
            requestBodyObj.put("tool_choice", "auto");

            String requestBody = objectMapper.writeValueAsString(requestBodyObj);
            logger.debug("发送 AI 请求，model={}, sessionId={}, chars={}",
                    modelName, sessionId, requestBody.length());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                    .build();

            HttpResponse<String> response = BailianRetryExecutor.executeHttp(
                    "百炼 API 主请求",
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()),
                    this.bailianMaxRetries
            );

            if (response.statusCode() != 200) {
                logger.warn("Gemini API HTTP 错误 {}: {}", response.statusCode(), response.body());
                ErrorMonitorService.reportMainApiError(response.statusCode(), response.body());
                throw new RuntimeException("AI 服务暂时不可用 (HTTP " + response.statusCode() + ")");
            }

            JsonNode root = objectMapper.readTree(response.body());


            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("Gemini API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，缺少 message 字段");
                throw new RuntimeException("AI 回复格式错误");
            }

            JsonNode messageNode = firstChoice.get("message");
            String reply = messageNode.path("content").asText().trim();
            if ("null".equals(reply) || messageNode.path("content").isNull()) reply = "";
            logger.debug("AI raw reply (first 200 chars): {}", reply.length() > 200 ? reply.substring(0, 200) + "..." : reply);

            // === 多轮工具调用循环（OpenAI 原生 function calling，最多6轮）===
            JsonNode lastMessage = firstChoice.get("message");
            int toolRound = 0;
            int maxToolRounds = 20;
            int toolCalls = 0;

            while (toolRound < maxToolRounds) {
                toolRound++;

                boolean hasToolCalls = lastMessage.has("tool_calls")
                        && lastMessage.get("tool_calls").isArray()
                        && !lastMessage.get("tool_calls").isEmpty();

                if (!hasToolCalls) {
                    // 模型直接返回文本 —— 正常结束
                    String content = lastMessage.path("content").asText();
                    if ("null".equals(content) || lastMessage.path("content").isNull()) content = "";
                    if (!content.isEmpty()) {
                        reply = content;
                    }
                    break;
                }

                // === 有工具调用 ===
                List<ToolResult> toolResults = new ArrayList<>();

                // 将 assistant 消息（含 tool_calls）加入对话历史
                ObjectNode assistantMsg = MAPPER.createObjectNode();
                assistantMsg.put("role", "assistant");
                String asstContent = lastMessage.path("content").asText();
                if ("null".equals(asstContent) || lastMessage.path("content").isNull()) {
                    assistantMsg.putNull("content");
                } else {
                    assistantMsg.put("content", asstContent);
                }
                assistantMsg.set("tool_calls", lastMessage.get("tool_calls"));
                messages.add(MAPPER.convertValue(assistantMsg, Map.class));

                // 遍历 tool_calls 逐一执行
                ArrayNode toolCallsArray = (ArrayNode) lastMessage.get("tool_calls");
                for (JsonNode tc : toolCallsArray) {
                    String callId = tc.path("id").asText();
                    String toolName = tc.path("function").path("name").asText();
                    String argsJson = tc.path("function").path("arguments").asText();

                    Tool tool = availableTools.stream()
                            .filter(t -> t.getName().equals(toolName))
                            .findFirst().orElse(null);

                    if (tool != null) {
                        Map<String, Object> args;
                        try {
                            args = objectMapper.readValue(argsJson, Map.class);
                        } catch (Exception e) {
                            logger.warn("解析工具 {} 参数失败: {}", toolName, e.getMessage());
                            // 审计：参数解析失败
                            ToolAuditService.recordStatic(toolName, userId, groupId, sessionId,
                                    argsJson, null, false, false, "参数解析错误: " + e.getMessage(), 0);
                            Map<String, Object> errMsg = new HashMap<>();
                            errMsg.put("role", "tool");
                            errMsg.put("tool_call_id", callId);
                            errMsg.put("content", "参数解析错误: " + e.getMessage());
                            messages.add(errMsg);
                            continue;
                        }

                        long execStart = System.currentTimeMillis();
                        String result;
                        try {
                            result = tool.execute(args);
                        } catch (Exception e) {
                            long latency = System.currentTimeMillis() - execStart;
                            ToolAuditService.recordStatic(toolName, userId, groupId, sessionId,
                                    argsJson, null, false, false, e.toString(), latency);
                            throw e;
                        }
                        long latency = System.currentTimeMillis() - execStart;
                        // 第三阶段 3.3：启发式判断 rejected。
                        // 工具在权限/限流被拒时返回的中文提示以"拒绝"/"不可用"/"超限"等开头
                        boolean rejected = result != null && (
                                result.startsWith("拒绝") ||
                                result.contains("不可用") ||
                                result.contains("超限") ||
                                result.contains("白名单") ||
                                result.contains("黑名单"));
                        boolean success = !rejected;
                        ToolAuditService.recordStatic(toolName, userId, groupId, sessionId,
                                argsJson, result, rejected, success, null, latency);

                        toolCalls++;
                        WebDashboardListener.recordToolCall(toolName);
                        logger.info("🔧 [原生工具] {} args={} → {}", toolName, args,
                                result.length() > 120 ? result.substring(0, 120) + "..." : result);
                        toolResults.add(new ToolResult(toolName, result));

                        if (groupId != null) {
                            Object uid = args.getOrDefault("target_user_id",
                                    args.getOrDefault("user_id", ""));
                            botMemory.record(groupId, BotMemoryService.EntryType.TOOL_CALLED,
                                    uid != null ? String.valueOf(uid) : "",
                                    toolName + ": " + (result.length() > 80 ? result.substring(0, 80) + "..." : result));
                        }

                        // 工具结果截断到500字，避免源码等大段内容撑爆上下文
                        String trimmedResult = result.length() > 500
                                ? result.substring(0, 500) + "\n...[已截断，原始共" + result.length() + "字符]"
                                : result;

                        Map<String, Object> toolResultMsg = new HashMap<>();
                        toolResultMsg.put("role", "tool");
                        toolResultMsg.put("tool_call_id", callId);
                        toolResultMsg.put("content", trimmedResult);
                        messages.add(toolResultMsg);
                    } else {
                        logger.warn("模型调用了未知工具: {}", toolName);
                        Map<String, Object> unknownMsg = new HashMap<>();
                        unknownMsg.put("role", "tool");
                        unknownMsg.put("tool_call_id", callId);
                        unknownMsg.put("content", "未知工具: " + toolName);
                        messages.add(unknownMsg);
                    }
                }

                // 已达最大轮次 → 工具结果不再拼入回复，避免源码/日志泄露到聊天中
                if (toolRound >= maxToolRounds) {
                    logger.info("已达最大工具调用轮次 {}", maxToolRounds);
                    reply = "唔……查是查到了但是说不完啦，大概就这样~";
                    break;
                }

                // 构造 follow-up 请求（继续带 tools）
                Map<String, Object> nextBody = new HashMap<>();
                nextBody.put("model", modelName);
                nextBody.put("messages", messages);
                nextBody.put("max_tokens", 1024);
                addReasoningEffort(nextBody, baiLianReasoningEffort);
                nextBody.put("tools", toolSpecs);
                nextBody.put("tool_choice", "auto");

                String nextBodyJson = objectMapper.writeValueAsString(nextBody);
                JsonNode nextMsg = null;
                try {
                    HttpResponse<String> nextResp = BailianRetryExecutor.executeHttp(
                            "百炼 API 工具第" + toolRound + "轮",
                            () -> {
                                HttpRequest nextReq = HttpRequest.newBuilder()
                                        .uri(URI.create(url))
                                        .header("Authorization", "Bearer " + apiKey)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(nextBodyJson))
                                        .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                                        .build();
                                return httpClient.send(nextReq, HttpResponse.BodyHandlers.ofString());
                            },
                            this.bailianMaxRetries
                    );
                    if (nextResp.statusCode() == 200) {
                        JsonNode sr = objectMapper.readTree(nextResp.body());
                        JsonNode sc = sr.path("choices");
                        if (sc.isArray() && !sc.isEmpty()) {
                            nextMsg = sc.get(0).path("message");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("工具第{}轮回调失败: {}", toolRound, e.getMessage());
                }

                if (nextMsg != null) {
                    lastMessage = nextMsg;
                } else {
                    // 回调失败 → 用非 send_status 结果兜底
                    String fallback = toolResults.stream()
                            .filter(tr -> !"send_status".equals(tr.name))
                            .map(tr -> tr.result)
                            .reduce((a, b) -> a + "；" + b).orElse("");
                    reply = fallback.isEmpty() ? "唔……查是查到了但是脑子有点转不过来，你再说一遍？" : fallback;
                    break;
                }
            } // end multi-round while

        // === long JSON 提取 + 重试（最多2次修正） ===
        boolean isLongJsonAttempt = reply.contains("\"long\"") && reply.contains("{");
        for (int longRetry = 0; longRetry < 3; longRetry++) {
            if (reply.isEmpty()) break;

            boolean extracted = false;
            if (isLongJsonAttempt) {
                try {
                    JsonNode longJson = objectMapper.readTree(reply);
                    String longContent = longJson.path("long").asText();
                    if (!longContent.isEmpty()) {
                        reply = longContent;
                        extracted = true;
                        logger.debug("Long reply extracted: {} chars", reply.length());
                    }
                } catch (Exception e) {
                    // JSON 解析失败，用正则兜底提取
                    logger.warn("Long reply JSON 解析失败，尝试正则提取");
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"long\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                            .matcher(reply);
                    if (m.find()) {
                        String val = m.group(1)
                                .replace("\\n", "\n")
                                .replace("\\t", "\t")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                        if (!val.isEmpty()) {
                            reply = val;
                            extracted = true;
                            logger.debug("正则提取 long 内容成功: {} chars", reply.length());
                        }
                    }
                }

                if (extracted) {
                    reply = reply.replaceAll("【.*?】", "").trim();
                }
            }

            if (!isLongJsonAttempt || extracted) {
                // 清理 AI 偶尔输出的 JSON 代码块和裸 JSON（long 提取成功后跳过此步）
                reply = reply.replaceAll("```json\\s*\\{[^}]*\\}\\s*```", "");
                reply = reply.replaceAll("```\\s*\\{[^}]*\\}\\s*```", "");
                reply = reply.replaceAll("\\{\\s*\"[^\"]+\"\\s*:\\s*\"[^\"]*\"[^}]*\\}", "");
                reply = reply.replaceAll("\\{\\s*\"[^\"]+\"\\s*:\\s*[^,}]+[^}]*\\}", "");
                reply = reply.replaceAll("【.*?】", "").trim();
            }

            // 修复 AI 输出的畸形 CQ 码
            reply = reply.replaceAll("\\[\\s*CQ:", "[CQ:").replaceAll("\\s*\\]", "]");

            if (!reply.trim().isEmpty() && !reply.trim().matches("[,\\s]+")) {
                break; // 有内容，不重试
            }

            // reply 为空，且是 long JSON 解析失败 → 重试
            if (isLongJsonAttempt && !extracted && longRetry < 2) {
                logger.warn("Long JSON 提取完全失败，第{}次重试AI...", longRetry + 1);
                messages.add(Map.of("role", "assistant", "content", reply));
                messages.add(Map.of("role", "user", "content",
                        "你的上一条回复格式有误，无法解析。请直接用纯文本重新输出内容，不要用JSON包裹。不要输出```json代码块。"));
                try {
                    Map<String, Object> retryBody = new HashMap<>();
                    retryBody.put("model", modelName);
                    retryBody.put("messages", messages);
                    retryBody.put("max_tokens", 1024);
                    addReasoningEffort(retryBody, baiLianReasoningEffort);
                    HttpRequest retryReq = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(retryBody)))
                            .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                            .build();
                    HttpResponse<String> retryResp = httpClient.send(retryReq, HttpResponse.BodyHandlers.ofString());
                    if (retryResp.statusCode() == 200) {
                        JsonNode retryRoot = objectMapper.readTree(retryResp.body());
                        JsonNode retryChoices = retryRoot.path("choices");
                        if (retryChoices.isArray() && !retryChoices.isEmpty()) {
                            String newReply = retryChoices.get(0).path("message").path("content").asText().trim();
                            if ("null".equals(newReply)) newReply = "";
                            if (!newReply.isEmpty()) {
                                reply = newReply;
                                isLongJsonAttempt = reply.contains("\"long\"") && reply.contains("{");
                                continue;
                            }
                        }
                    }
                } catch (Exception retryEx) {
                    logger.warn("Long JSON 重试调用失败: {}", retryEx.getMessage());
                }
                break; // 重试也失败了，退出
            }
            break;
        }

        // 最终兜底
        if (reply.trim().isEmpty() || reply.trim().matches("[,\\s]+")) {
            reply = "嗯...再问一次吧";
        }

            if (!suppress) {
                synchronized (history) {
                    history.add(new Message("assistant", reply));
                }

                if (groupId != null) {
                    recordUserInteraction(groupId, userId, reply);

                    if (!reply.equals("抱歉，刚才走神了...") &&
                            !reply.equals("嗯...再问一次吧") &&
                            !reply.trim().isEmpty()) {

                        Deque<Long> msgHistory = botMessageHistory.computeIfAbsent(
                                groupId, k -> new ConcurrentLinkedDeque<>());
                        synchronized (msgHistory) {
                            long now = System.currentTimeMillis();
                            msgHistory.removeIf(ts -> now - ts > 60_000);

                            if (msgHistory.size() >= MAX_MESSAGES_PER_MINUTE) {
                                logger.debug("糖果熊在群 {} 发言已达上限，跳过回复", groupId);
                                return GenerationResult.silent(toolCalls, 0);
                            }

                            msgHistory.addLast(now);
                        }
                    }
                }
            }

            // NO_REPLY 检测
            if (allowSilence && reply.trim().equals("<NO_REPLY>")) {
                logger.info("🤫 模型选择沉默: sessionId={}", sessionId);
                return GenerationResult.silent(toolCalls, 0);
            }

            return GenerationResult.reply(reply.isEmpty() ? "嗯...再问一次吧" : reply, toolCalls, 0);

        } catch (Exception e) {
            logger.error("AI 调用失败", e);
            return GenerationResult.error("抱歉，刚才走神了...");
        }
    }

    static Map<String, Object> buildUserMessage(String text, List<String> imageDataUris) {
        // imageDataUris 保留参数兼容旧调用方，但主聊天永远不发送原始图片。
        return Map.of("role", "user", "content", text);
    }


    /**
     * 用视觉模型描述图片内容，返回文本描述注入到对话上下文
     */
    public String describeImages(List<String> imageDataUris) {
        if (imageDataUris == null || imageDataUris.isEmpty()) return "";
        if (!BotConfig.isVisionEnabled()) {
            return "[用户发送了" + imageDataUris.size() + "张图片]";
        }

        String visionUrl = BotConfig.getVisionBaseUrl();
        String visionKey = BotConfig.getVisionApiKey();
        String visionModel = BotConfig.getVisionModel();

        if (visionKey == null || visionKey.isBlank()) {
            logger.warn("视觉模型 API Key 未配置，跳过图片识别");
            return "[用户发送了" + imageDataUris.size() + "张图片]";
        }

        StringBuilder result = new StringBuilder();
        result.append("[用户发送了").append(imageDataUris.size()).append("张图片]");
        int limit = Math.min(imageDataUris.size(), 3);

        for (int i = 0; i < limit; i++) {
            try {
                List<Map<String, Object>> content = new ArrayList<>();
                content.add(Map.of("type", "text", "text", "请描述这张图片，用中文，控制在50字以内。重点关注：1) 主体（人物/动物/物体） 2) 情绪与动作（是否夸张、拟人化、搞笑、可爱） 3) 是否有文字/字幕/水印 4) 整体风格（真实照片/二次元/卡通/梗图）。直接描述，不要加前缀。"));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageDataUris.get(i))));

                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "user", "content", content));

                Map<String, Object> body = new HashMap<>();
                body.put("model", visionModel);
                body.put("messages", messages);
                // MiMo-V2.5 是 reasoning model：先思考再回答。150 tokens 不够，思考就耗光预算。
                // 设大点 + 强制 max_completion_tokens（如果模型支持）保证输出内容。
                body.put("max_tokens", 800);
                // body.put("max_completion_tokens", 800);  // 某些 OpenAI-style API 用这名

                String requestBody = objectMapper.writeValueAsString(body);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(visionUrl))
                        .header("Authorization", "Bearer " + visionKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofMillis(BotConfig.getVisionTimeoutMs()))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    String desc = root.path("choices").get(0).path("message").path("content").asText("");
                    if (!desc.isBlank()) {
                        logger.debug("视觉识别结果 [{}]: {}", visionModel, desc.trim());
                        result.append("\n图片").append(i + 1).append("内容：").append(desc.trim());
                    } else {
                        logger.warn("视觉模型返回空内容 [{}] body: {}", visionModel, resp.body());
                    }
                } else {
                    logger.warn("视觉模型 HTTP {}: {}", resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                logger.warn("图片识别失败: {}", e.getMessage());
            }
        }
        return result.toString();
    }

    /** 一次视觉请求完成表情包判定、情绪关键词提取和审计描述。 */
    public String classifyStickerImages(List<String> imageDataUris) {
        if (imageDataUris == null || imageDataUris.isEmpty() || !BotConfig.isVisionEnabled()) return "";

        String visionUrl = BotConfig.getVisionBaseUrl();
        String visionKey = BotConfig.getVisionApiKey();
        String visionModel = BotConfig.getVisionModel();
        if (visionKey == null || visionKey.isBlank()) return "";

        String prompt = "请直接判断这张图片（多帧按一个整体）是否应该作为聊天表情包入库，"
                + "并提取将来模型调用 send_sticker 时会输入的中文情绪/场景关键词。\n"
                + "应用/网页/小说详情页/作品介绍/商品页/聊天记录/社交平台页面截图，默认不是表情包；"
                + "纯文字截图、表格、新闻图、普通插画、作品宣传页也不是。\n"
                + "不要因为有二次元人物、文字、R-18 标签或暧昧内容就判定为表情包。\n"
                + "只有表达情绪、反应、玩梗、搞笑转发或明显适合聊天复用才是表情包。\n"
                + "keywords 只能表达适合回应的情绪或场景，不能写人物、动物、颜色、作品、类型、标签、文字内容或画面元素。\n"
                + "keywords 只能从以下词选择：开心、笑、哈哈、无语、哭、难过、生气、怒、安慰、加油、得意、嘲讽、"
                + "震惊、惊讶、吃瓜、嫌弃、尴尬、害羞、滑稽、崩溃、委屈、比心、笑哭、裂开、可爱、萌、高冷、治愈、"
                + "调皮、害怕、困、累、饿、懵、问号、再见、OK、谢谢、偷笑、白眼、打死你、赞、厉害、牛、爱、喜欢。\n"
                + "不是表情包时 keywords 必须为空数组。只输出严格 JSON，不要 Markdown："
                + "{\"isSticker\":false,\"keywords\":[],\"description\":\"一句话说明图片用途，供审计，不作为检索关键词\"}";

        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt));
            int limit = Math.min(imageDataUris.size(), 3);
            for (int i = 0; i < limit; i++) {
                String uri = imageDataUris.get(i);
                if (uri != null && !uri.isBlank()) {
                    content.add(Map.of("type", "image_url", "image_url", Map.of("url", uri)));
                }
            }
            if (content.size() == 1) return "";

            Map<String, Object> body = new HashMap<>();
            body.put("model", visionModel);
            body.put("messages", List.of(Map.of("role", "user", "content", content)));
            body.put("max_tokens", 800);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(visionUrl))
                    .header("Authorization", "Bearer " + visionKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofMillis(BotConfig.getVisionTimeoutMs()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warn("表情包视觉分类 HTTP {}: {}", resp.statusCode(), resp.body());
                return "";
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String result = root.path("choices").path(0).path("message").path("content").asText("");
            if (!result.isBlank()) logger.debug("表情包视觉分类结果 [{}]: {}", visionModel, result.trim());
            return result.trim();
        } catch (Exception e) {
            logger.warn("表情包视觉分类失败: {}", e.getMessage());
            return "";
        }
    }

    /** 仅在配置非空时注入，保持未配置时与旧请求完全兼容。 */
    private static void addReasoningEffort(Map<String, Object> body, String effort) {
        if (effort != null && !effort.isBlank()) {
            body.put("reasoning_effort", effort.trim());
        }
    }

    /** 简单调用聊天模型，返回纯文本响应（无工具、无会话、无状态注入） */
    public String generateRaw(String prompt) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));

            Map<String, Object> body = new HashMap<>();
            body.put("model", bailianChatModel);
            body.put("messages", messages);
            body.put("max_tokens", 8192);
            body.put("temperature", 0.8);

            String jsonBody = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baiLianBaseUrl))
                    .header("Authorization", "Bearer " + baiLianApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(response.body());

            String content = root.path("choices").get(0).path("message").path("content").asText("");
            return content != null ? content.trim() : "";
        } catch (Exception e) {
            logger.warn("generateRaw 失败: {}", e.getMessage());
            return "";
        }
    }

    public String generateForAgent(String userPrompt, List<Tool> tools) {
        logger.info("🤖 Agent AI 调用: prompt=[{}]", userPrompt);

        long startTime = System.currentTimeMillis();

        try {
            // 构建 messages：纯任务导向
            List<Map<String, String>> messages = new ArrayList<>();

            // ⭐ 关键：Agent 的 system prompt（中立、指令明确）
            String systemPrompt = """
            你是一个高效、准确的智能助手，专注于回答用户的问题或执行指定任务。
            - 回答应简洁、事实准确
            - 若调用了工具，请基于工具结果直接作答
            - 不要添加无关语气词、拟人化表达或文艺修饰
            - 如果不知道答案，直接说"无法提供相关信息"
            """;
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));

            String url = this.agentBaseUrl;
            String apiKey = this.agentApiKey;
            String modelName = this.agentModel;

            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);
            addReasoningEffort(requestBodyObj, agentReasoningEffort);

            String requestBody = objectMapper.writeValueAsString(requestBodyObj);
            logger.info("➡️ 向 Agent API 发送请求 (Model: {})", modelName);
            logger.debug("请求体: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(this.agentTimeoutMs))
                    .build();

            logger.info("⏳ 等待 API 响应...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("⬅️ API 响应状态码: {}, 耗时: {}ms", response.statusCode(), elapsed);

            if (response.statusCode() != 200) {
                logger.error("❌ Gemini API HTTP 错误 {}: {}", response.statusCode(), response.body());
                ErrorMonitorService.reportMainApiError(response.statusCode(), response.body());

                // 如果是余额不足或其他错误，记录详细错误
                if (response.statusCode() == 402) {
                    logger.error("💡 Gemini API 余额不足，请充值或更换 API Key");
                    throw new RuntimeException("Gemini API 余额不足，请联系管理员充值或更换 API Key");
                }

                throw new RuntimeException("Agent AI 服务 HTTP 错误: " + response.statusCode());
            }

            // 解析 JSON 响应（OpenAI 格式）
            JsonNode root = objectMapper.readTree(response.body());

            logger.debug("Agent API 响应: {}", response.body());

            // 检查错误
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            // 提取回复内容
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("Gemini API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，缺少 message 字段");
                throw new RuntimeException("AI 回复格式错误");
            }

            String content = firstChoice.path("message").path("content").asText().trim();
            if ("null".equals(content) || firstChoice.path("message").path("content").isNull()) content = "";

            // 清理 Markdown 代码块标记
            if (content.startsWith("```")) {
                // 移除开头的 ```json 或 ```
                int firstNewLine = content.indexOf('\n');
                if (firstNewLine != -1) {
                    content = content.substring(firstNewLine + 1);
                }
                // 移除结尾的 ```
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3).trim();
                }
            }

            logger.info("✅ AI 响应成功，内容长度: {} 字符", content.length());
            return content;


        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("❌ Agent AI 调用失败 (耗时: {}ms)", elapsed, e);
            throw new RuntimeException("AI 处理失败: " + e.getMessage(), e);
        }
    }

    // ===== 消息分段：优先 AI 自定分隔，兜底机械切分 =====
    public List<String> splitIntoShortMessages(String reply) {
        if (reply == null || reply.trim().isEmpty()) {
            return Arrays.asList("嗯...再问一次吧");
        }
        reply = reply.trim();

        // XML 标签分隔（推荐，GPT 系列模型稳定性远高于 |---|）
        java.util.regex.Matcher msgMatcher = java.util.regex.Pattern.compile("<msg>(.*?)</msg>", java.util.regex.Pattern.DOTALL).matcher(reply);
        if (msgMatcher.find()) {
            List<String> parts = new ArrayList<>();
            msgMatcher.reset();
            while (msgMatcher.find()) {
                String part = msgMatcher.group(1).trim();
                if (!part.isEmpty()) parts.add(part);
            }
            if (!parts.isEmpty()) return parts;
        }

        // 旧格式兜底（|---| 分隔符，向后兼容旧 Prompt）
        if (reply.contains("|---|")) {
            return Arrays.stream(reply.split("\\|---\\|"))
                    .map(String::trim)
                    .map(s -> s.replaceAll("\\n{2,}", "\n"))
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }

        // 兜底：AI 没用分隔符但有空行时，按空行切分段落
        if (reply.contains("\n\n")) {
            List<String> allParts = new ArrayList<>();
            String[] paragraphs = reply.split("\\n\\s*\\n");
            for (String para : paragraphs) {
                para = para.trim();
                if (para.isEmpty()) continue;
                allParts.addAll(splitParagraphIntoSentences(para));
            }
            if (allParts.size() > 10) {
                return new ArrayList<>(allParts.subList(0, 10));
            }
            return allParts.isEmpty() ? Arrays.asList(reply) : allParts;
        }

        // 单个换行切分：仅当切出 ≤5 条时生效，避免结构化内容（排行榜/表格）逐行刷屏
        if (reply.contains("\n")) {
            List<String> lines = new ArrayList<>();
            for (String line : reply.split("\\n")) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
            if (lines.size() <= 5) {
                return lines;
            }
        }

        // 提取开头的 CQ 码，避免切分时截断
        String cqPrefix = "";
        java.util.regex.Matcher cqMatcher = java.util.regex.Pattern.compile("^(\\[CQ:[^\\]]+\\]\\s*)+").matcher(reply);
        if (cqMatcher.find()) {
            cqPrefix = cqMatcher.group();
            reply = reply.substring(cqMatcher.end());
        }


        // 包含代码块标记 → 不按标点拆分，避免代码被切碎刷屏
        if (reply.contains("│") || reply.startsWith("📄")) {
            return Arrays.asList(reply);
        }

        // 只按句末标点拆分（不再按 \n 拆分，避免排行榜等结构化内容逐行切分刷屏）
        String[] sentences = reply.split("(?<=[。！？；~?!…])(?![。！？；~?!…])");
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean first = true;

        for (String sent : sentences) {
            sent = sent.trim();
            if (sent.isEmpty()) continue;

            String candidate = first ? cqPrefix + sent : sent;
            first = false;

            // 累积到合理长度再切分，保持排行榜等结构化内容完整
            if (current.length() + candidate.length() <= 600) {
                if (current.length() > 0) current.append("\n");
                current.append(candidate);
            } else {
                if (current.length() > 0) parts.add(current.toString());
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) parts.add(current.toString());

        final int MAX_PARTS = 10;
        if (parts.size() > MAX_PARTS) {
            return new ArrayList<>(parts.subList(0, MAX_PARTS));
        }
        return parts.isEmpty() ? Arrays.asList(cqPrefix + reply) : parts;
    }

    /** 将单个段落按句末标点切分为合理长度的消息片段 */
    private List<String> splitParagraphIntoSentences(String para) {
        // 包含代码块标记（read_code 输出的行号前缀）→ 不按标点拆分，避免代码被切碎刷屏
        if (para.contains("│") || para.startsWith("📄")) {
            return Arrays.asList(para);
        }
        List<String> result = new ArrayList<>();
        String[] sentences = para.split("(?<=[。！？；~?!…])(?![。！？；~?!…])");
        StringBuilder current = new StringBuilder();
        for (String sent : sentences) {
            sent = sent.trim();
            if (sent.isEmpty()) continue;
            if (current.length() + sent.length() <= 600) {
                if (current.length() > 0) current.append("\n");
                current.append(sent);
            } else {
                if (current.length() > 0) result.add(current.toString());
                current = new StringBuilder(sent);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result.isEmpty() ? Arrays.asList(para) : result;
    }

    /** 将时间戳转为相对时间标签，如 "今天 14:30"、"昨天 09:15" */
    /** 从 SceneState 中找到 bot 参与的当前 Thread ID，否则返回最高权重 Thread ID。 */
    private Long findCurrentThreadId(SceneState scene) {
        if (scene == null || scene.isEmpty()) return null;
        String botId = String.valueOf(BOT_QQ);
        for (ConversationThread t : scene.activeThreads()) {
            if (t.getParticipants().contains(botId)) return t.getId();
        }
        return scene.activeThreads().get(0).getId();
    }

    private static String formatRelativeTime(long timestamp) {
        java.time.LocalDateTime msgTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp), ZoneId.of("Asia/Shanghai"));
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"));
        java.time.LocalDate msgDate = msgTime.toLocalDate();
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");
        if (msgDate.equals(today)) return "今天 " + msgTime.format(tf);
        if (msgDate.equals(today.minusDays(1))) return "昨天 " + msgTime.format(tf);
        if (msgDate.equals(today.minusDays(2))) return "前天 " + msgTime.format(tf);
        return msgTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    /** 用 HanLP 从文本提取关键词 */
    private List<String> extractKeywords(String text) {
        try {
            String clean = text.replaceAll("[\\p{Punct}\\s]+", " ").trim();
            if (clean.isEmpty()) return Collections.emptyList();
            List<String> kw = HanLP.extractKeyword(clean, 5);
            if (kw == null) return Collections.emptyList();
            return kw.stream().filter(k -> k != null && !k.isBlank()).limit(5).collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("HanLP关键词提取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ===== 记录方法 =====

    public void recordUserInteraction(String groupId, String userId, String fullBotReply) {
        String key = groupId + "_" + userId;
        userThreads.put(key, new UserThread(System.currentTimeMillis(), fullBotReply));
    }

    public boolean isWithinFollowUpWindow(String groupId, String userId) {
        String key = groupId + "_" + userId;
        UserThread thread = userThreads.get(key);
        return thread != null && System.currentTimeMillis() - thread.lastInteraction < 120_000;
    }

    public void recordGroupContext(String groupId, String userId, String nick, String msg, String type) {
        groupContexts.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>())
                .addLast(new ContextEvent(System.currentTimeMillis(), type, msg, userId, nick));

        Deque<ContextEvent> deque = groupContexts.get(groupId);
        if (deque != null) {
            deque.removeIf(e -> System.currentTimeMillis() - e.timestamp > 300_000);
        }
    }

    public void recordBotAction(String groupId, String userId, String nick, String feature, String detail) {
        String msg = "糖果熊 为 " + nick + "(" + userId + ") 执行了【" + feature + "】: " + detail;
        recordGroupContext(groupId, userId, nick, msg, "bot_action");
    }

    // ===== 速率控制（public — AIHandler/StateStore 调用） =====

    public boolean canReact(String groupId) {
        Deque<Long> history = groupReactionHistory.computeIfAbsent(
                groupId, k -> new ConcurrentLinkedDeque<>());
        synchronized (history) {
            history.removeIf(ts -> System.currentTimeMillis() - ts > 300_000);
            return history.size() < 10;
        }
    }

    public void recordReaction(String groupId) {
        Deque<Long> history = groupReactionHistory.computeIfAbsent(
                groupId, k -> new ConcurrentLinkedDeque<>());
        synchronized (history) {
            history.addLast(System.currentTimeMillis());
        }
    }

    // ===== 群消息记录 =====
    public void addGroupMessage(String groupId, String message) {
        recordGroupContext(groupId, "unknown", "someone", message, "user_message");
    }
    private static final int PUBLIC_HISTORY_MAX_MESSAGES = 30;
    private static final long PUBLIC_HISTORY_MAX_AGE_MS = 20 * 60_000L;
    private static final int PUBLIC_CONTEXT_CHAR_BUDGET = 1_600;
    private static final int PUBLIC_MESSAGE_CHAR_LIMIT = 280;

    // 存储每个群最近一小段完整发言（含发言人）；SceneState 负责更长跨度的主题压缩。
    private final Map<String, Deque<PublicMessage>> publicGroupHistory = new ConcurrentHashMap<>();

    public static class PublicMessage {
        public final String messageId;
        public final String userId;
        public final String nickname;
        public final String content;
        public final List<Long> atUserIds;
        public final Long replyToMessageId;
        public final long timestamp;

        public PublicMessage(String messageId, String userId, String nickname, String content) {
            this(messageId, userId, nickname, content, Collections.emptyList(), null);
        }

        public PublicMessage(String messageId, String userId, String nickname, String content,
                             List<Long> atUserIds, Long replyToMessageId) {
            this.messageId = messageId == null ? "" : messageId;
            this.userId = userId;
            this.nickname = nickname;
            this.content = content;
            this.atUserIds = atUserIds == null ? List.of() : List.copyOf(atUserIds);
            this.replyToMessageId = replyToMessageId;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // 提供方法供 AIHandler 调用
    public void recordPublicGroupMessage(String groupId, String userId, String nickname, String message) {
        recordPublicGroupMessage(groupId, userId, nickname, message, "");
    }

    /**
     * 按触发原因选择群背景范围。追问/回复机器人时，当前用户会话已经包含所需上下文，
     * 再注入整段群聊只会增加无关消息和重复信息。
     */
    static int publicContextLimit(ConversationEvent event) {
        if (event == null) return 0;
        return switch (event) {
            case PROBABILISTIC, COGNITIVE_CONTINUE, COGNITIVE_INTERJECTION -> 8;
            case MENTION, PASSIVE_TRIGGER -> 6;
            case FOLLOW_UP, AI_COMMENTED, AWAIT_REPLY, NOTHING -> 0;
        };
    }

    /** 构建有限的群背景；当前轮消息通过 message_id 排除，避免重复注入。 */
    static String buildPublicGroupContext(Deque<PublicMessage> recent, int limit, Set<String> excludedIds) {
        return buildPublicGroupContext(recent, limit, excludedIds, PUBLIC_CONTEXT_CHAR_BUDGET);
    }

    /**
     * 保留最新、最相关的原文，并用字符预算阻止一条超长消息挤掉整个群聊背景。
     * 更长跨度的主题信息由已经注入 Prompt 的 SceneState 表达。
     */
    static String buildPublicGroupContext(Deque<PublicMessage> recent, int limit,
                                          Set<String> excludedIds, int maxChars) {
        if (recent == null || recent.isEmpty() || limit <= 0) return "";
        Set<String> excluded = excludedIds == null ? Collections.emptySet() : excludedIds;
        List<PublicMessage> list = new ArrayList<>(recent);
        List<PublicMessage> selected = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0 && selected.size() < limit; i--) {
            PublicMessage message = list.get(i);
            if (!excluded.contains(message.messageId)) {
                selected.add(message);
            }
        }
        Collections.reverse(selected);
        String header = "【群内最近讨论】（仅作背景；较长话题以群聊场景为准）\n";
        int budget = Math.max(header.length() + 1, maxChars);
        List<String> rendered = new ArrayList<>();
        int used = header.length();
        for (int i = selected.size() - 1; i >= 0; i--) {
            PublicMessage m = selected.get(i);
            String content = truncateContextText(m.content, PUBLIC_MESSAGE_CHAR_LIMIT);
            String line = "[" + formatRelativeTime(m.timestamp) + "] "
                    + m.nickname + "(" + m.userId + ")：" + content + "\n";
            if (used + line.length() > budget) break;
            rendered.add(line);
            used += line.length();
        }
        if (rendered.isEmpty()) return "";
        Collections.reverse(rendered);
        return (header + String.join("", rendered)).trim();
    }

    private static String truncateContextText(String content, int maxChars) {
        if (content == null) return "";
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) return compact;
        return compact.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    public void recordPublicGroupMessage(String groupId, String userId, String nickname, String message, String messageId) {
        recordPublicGroupMessage(groupId, userId, nickname, message, messageId, Collections.emptyList(), null);
    }

    /** Metadata-aware overload used by the group cognition pipeline; legacy overloads remain intact. */
    public void recordPublicGroupMessage(String groupId, String userId, String nickname, String message,
                                         String messageId, List<Long> atUserIds, Long replyToMessageId) {
        if (groupId == null || message.trim().isEmpty()) return;

        // 过滤机器人自己的消息（避免重复）
        if (userId.equals(String.valueOf(BOT_QQ))) return;

        Deque<PublicMessage> history = publicGroupHistory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());

        // 清理过期消息；更久的主题由持久化 SceneState 表示。
        long now = System.currentTimeMillis();
        history.removeIf(msg -> now - msg.timestamp > PUBLIC_HISTORY_MAX_AGE_MS);

        if (history.size() >= PUBLIC_HISTORY_MAX_MESSAGES) {
            history.pollFirst();
        }

        history.offerLast(new PublicMessage(messageId, userId, nickname, message, atUserIds, replyToMessageId));
    }
    /** 记录糖果熊自己的群发言到公开历史，使 LLM 能看到自己上一轮说了什么 */
    public void recordBotOwnGroupMessage(String groupId, String message) {
        if (groupId == null || message.trim().isEmpty()) return;

        Deque<PublicMessage> history = publicGroupHistory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        history.removeIf(msg -> now - msg.timestamp > PUBLIC_HISTORY_MAX_AGE_MS);
        if (history.size() >= PUBLIC_HISTORY_MAX_MESSAGES) {
            history.pollFirst();
        }
        history.offerLast(new PublicMessage("", String.valueOf(BOT_QQ), "糖果熊", message));
    }

    public Deque<PublicMessage> getPublicGroupHistory(String groupId) {
        return publicGroupHistory.get(groupId);
    }

    /** Stable bounded snapshot for concurrent cognition calls. */
    public List<PublicMessage> getPublicGroupHistorySnapshot(String groupId, int limit) {
        Deque<PublicMessage> history = publicGroupHistory.get(groupId);
        if (history == null || history.isEmpty() || limit <= 0) return List.of();
        List<PublicMessage> copy = new ArrayList<>(history);
        int start = Math.max(0, copy.size() - limit);
        return List.copyOf(copy.subList(start, copy.size()));
    }
}
