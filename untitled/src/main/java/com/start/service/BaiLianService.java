package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;
import com.start.Main;
import com.start.agent.social.LuckTool;
import com.start.agent.MemoryTool;
import com.start.agent.social.PokeTool;
import com.start.agent.social.ProfessionPKTool;
import com.start.agent.social.ProfessionTool;
import com.start.agent.social.RankTool;
import com.start.agent.RecallMemoryTool;
import com.start.agent.RememberFactTool;
import com.start.agent.ReminderTool;
import com.start.agent.ScheduleEventTool;
import com.start.agent.SearchHistoryTool;
import com.start.agent.AwaitReplyTool;
import com.start.agent.QueryLifeTool;
import com.start.agent.SendGroupTool;
import com.start.agent.SendPrivateTool;
import com.start.agent.SendStatusTool;
import com.start.agent.social.UserAffinityTool;
import com.start.agent.WebSearchTool;
import com.start.agent.EggGroupSearchTool;
import com.start.agent.social.SanjiaoTool;
import com.start.agent.social.MerchantSubscribeTool;
import com.start.agent.social.TravelingMerchantTool;
import com.start.agent.KnowledgeBaseTool;
import com.start.repository.MerchantRepository;
import com.start.agent.LearnKnowledgeTool;
import com.start.agent.social.UserAliasTool;
import com.start.agent.VoiceTool;
import com.start.agent.WeatherTool;
import com.start.agent.ShellTool;
import com.start.agent.ScheduleRecurringTaskTool;
import com.start.agent.StickerTool;
import com.start.agent.LinkPreviewTool;
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
import com.start.repository.MessageRepository;
import com.start.repository.RecurringTaskRepository;
import com.start.service.EggGroupDataCenter;
import com.hankcs.hanlp.HanLP;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserProfileRepository;
import com.start.repository.BotMemoryRepository;
import com.start.repository.EvolutionRecordRepository;
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
import java.util.concurrent.ThreadLocalRandom;
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
    private final MemoryService memoryService = new MemoryService();
    public void setMoodService(BotMoodService moodService) { this.moodService = moodService; }
    public void setLifeEngine(CandyBearLifeEngine e) { this.lifeEngine = e; }
    public void setBotInstance(Main bot) { this.botInstance = bot; }
    public void setConversationMetrics(ConversationMetrics m) { this.metrics = m; }
    public GameStateService getGameStateService() { return gameStateService; }
    public BotMemoryService getBotMemory() { return botMemory; }

    private final String baiLianApiKey = BotConfig.getBaiLianApiKey();
    private final String baiLianBaseUrl = BotConfig.getBaiLianBaseUrl();
    private final String bailianChatModel = BotConfig.getBaiLianChatModel();
    private final int bailianTimeoutMs = BotConfig.getBaiLianTimeoutMs();
    private final int bailianMaxRetries = BotConfig.getBaiLianMaxRetries();

    private final String agentApiKey = BotConfig.getAgentApiKey();
    private final String agentBaseUrl = BotConfig.getAgentBaseUrl();
    private final String agentModel = BotConfig.getAgentModel();

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
        memoryService.register(new LongTermMemoryProvider());
    }
    // === 上下文管理 ===
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>(); // sessionId -> 消息历史
    private final Map<String, Long> lastClearTime = new ConcurrentHashMap<>();

    // === 主动插话控制（与 ConversationStateStore 共享） ===
    final Map<String, List<Long>> groupReactionHistory = new ConcurrentHashMap<>();
    private final AIDatabaseService aiDatabaseService = new AIDatabaseService();


    public AIDatabaseService getAiDatabaseService() { return aiDatabaseService; }

    /** 创建与 BaiLianService 共享状态的 StateStore */
    public com.start.runtime.conversation.ConversationStateStore createSharedStateStore() {
        return new com.start.runtime.conversation.InMemoryConversationStateStore(
                userThreads, groupContexts, pendingAwaits, groupReactionHistory);
    }
    // === 新增：糖果熊发言频率控制（每分钟上限）===
    private final Map<String, List<Long>> botMessageHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private static final int MAX_MESSAGES_PER_MINUTE = 10; // 每分钟最多发言次数

    // === 对话线程追踪（与 ConversationStateStore 共享） ===
    final Map<String, UserThread> userThreads = new ConcurrentHashMap<>();
    final Map<String, Deque<ContextEvent>> groupContexts = new ConcurrentHashMap<>();
    private final ThreadLocal<String> pendingImageData = new ThreadLocal<>(); // AIHandler 设置图片数据，generate() 消费
    private final ThreadLocal<Boolean> suppressSessionWrite = ThreadLocal.withInitial(() -> false); // PR4: 控制 generate() 是否写 sessions
    private final ThreadLocal<String> deferredImgData = new ThreadLocal<>(); // PR4: 延迟提交的图片数据

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

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
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

    /** 设 true 时 generate() 不写 sessions/DB，需调用方事后调 commitGeneration() 写入 */
    public void setSuppressSessionWrite(boolean suppress) {
        suppressSessionWrite.set(suppress);
    }

    /**
     * 延迟提交：在生成回复并发送成功后调用，持久化会话历史、DB 记录和追踪数据。
     * 用于配合 setSuppressSessionWrite(true) 的 generate() 调用。
     */
    public void commitGeneration(String sessionId, String userId, String userPrompt,
                                  String reply, String groupId) {
        String imgData = deferredImgData.get();
        deferredImgData.remove();
        if (imgData != null && !imgData.isEmpty()) {
            aiDatabaseService.recordUserMessageWithImages(sessionId, userId, userPrompt, groupId, 1L, imgData);
        } else {
            aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId, 1L);
        }

        List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        if (lastClearTime.containsKey(sessionId)) {
            history.clear();
            lastClearTime.remove(sessionId);
        }
        history.add(new Message("user", userPrompt));
        history.add(new Message("assistant", reply));

        if (groupId != null) {
            recordUserInteraction(groupId, userId, reply);
            recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");

            List<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
            long now = System.currentTimeMillis();
            msgHistory.removeIf(ts -> now - ts > 60_000);
            if (msgHistory.size() < MAX_MESSAGES_PER_MINUTE) {
                msgHistory.add(now);
            }
        }
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

    /** 基于 ConversationSession 的生成入口。 */
    public GenerationResult generate(com.start.runtime.conversation.ConversationSession session) {
        return generate(session.sessionId(), session.userId(), session.userPrompt(),
                session.groupId(), session.nickname(), session.atUserIds(), session.allowSilence());
    }

    /** 带沉默权的生成方法。allowSilence=true 时模型可以输出 <NO_REPLY> 选择沉默。 */
    public GenerationResult generate(String sessionId, String userId, String userPrompt, String groupId, String nickname, List<Long> atUserIds, boolean allowSilence) {
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}], ats={}, allowSilence={}", sessionId, userPrompt, atUserIds, allowSilence);

        String context = "";
        String agentToolContext = "";
        String publicGroupContext = "";
        String timeContext = "【当前时间】是：" + getBeijingTimeString()
            + "\n调用 search_chat_history / recall_memory 查日期时，用 yyyy-MM-dd 格式：今天=" + getTodayDateStr()
            + "，昨天=" + getYesterdayDateStr() + "，前天=" + getDayBeforeYesterdayStr();

        if (groupId != null) {
            Deque<PublicMessage> recent = getPublicGroupHistory(groupId);
            if (recent != null && !recent.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n【群内最近讨论】（带时间戳，帮你判断话题是今天/昨天/前天的）\n");
                List<PublicMessage> list = new ArrayList<>(recent);
                int start = Math.max(0, list.size() - 10);
                for (int i = start; i < list.size(); i++) {
                    PublicMessage m = list.get(i);
                    String timeLabel = formatRelativeTime(m.timestamp);
                    sb.append("[").append(timeLabel).append("] ");
                    sb.append(m.nickname).append("(").append(m.userId).append(")").append("：").append(m.content).append("\n");
                }
                publicGroupContext = sb.toString().trim();
            }
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
            Long isagent = 1L;
            String imgData = pendingImageData.get();
            pendingImageData.remove();
            boolean suppress = suppressSessionWrite.get();
            if (!suppress) {
                if (imgData != null && !imgData.isEmpty()) {
                    aiDatabaseService.recordUserMessageWithImages(sessionId, userId, userPrompt, groupId, isagent, imgData);
                } else {
                    aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId, isagent);
                }
            } else {
                deferredImgData.set(imgData); // 保存图片数据，供 commitGeneration() 使用
            }

            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }

            if (!suppress) {
                history.add(new Message("user", userPrompt));
            }

            // === 构建 PromptContext ===
            PromptContext ctx = new PromptContext()
                    .nickname(nickname).userId(userId).groupId(groupId)
                    .isGuier(String.valueOf(BotConfig.getAdminQq()).equals(userId))
                    .userProfileText(context)
                    .knowledgeContext(knowledgeContext)
                    .atUserIds(atUserIds).botQq(BOT_QQ);

            if (moodService != null) {
                String gk = groupId != null ? groupId : "private_" + userId;
                ctx.moodDescription(moodService.getMoodDescription(gk));
            }

            String overridePrompt = runtimeConfig.get("system_prompt_override");
            String promptPatch = runtimeConfig.get("system_prompt_patch");
            ctx.promptPatch(promptPatch);

            String systemPrompt;
            if (overridePrompt != null && !overridePrompt.isBlank()) {
                systemPrompt = promptBuilder.build(overridePrompt, ctx);
            } else {
                systemPrompt = promptBuilder.buildRuleBook(getRuleSet(), ctx);
            }

            // 别称
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

            // 主动记忆召回（通过 MemoryService 统一查询）
            List<String> hanlpKeywords = extractKeywords(userPrompt);
            String memoryCtx = memoryService.queryForPrompt(userId, groupId, hanlpKeywords);
            if (!memoryCtx.isEmpty()) {
                ctx.memoryRecallContext(memoryCtx);
                logger.info("主动记忆召回: 关键词={}", hanlpKeywords);
            }

            logger.debug("完整请求:{}", systemPrompt);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            int start = Math.max(0, history.size() - 4);
            for (int i = start; i < history.size(); i++) {
                Message msg = history.get(i);
                String role = "user".equals(msg.role) ? "user" : "assistant";

                String content = msg.content;
                if (content.length() > 600) {
                    content = content.substring(0, 600) + "...";
                }

                messages.add(Map.of("role", role, "content", content));
            }

            // PR4: suppress 模式下 user message 不在 history 中，手动追加到 messages 数组
            if (suppress) {
                messages.add(Map.of("role", "user", "content", userPrompt));
            }

            String url = this.baiLianBaseUrl;
            String apiKey = this.baiLianApiKey;
            String modelName = this.bailianChatModel;

            // 构建工具列表及 OpenAI 原生 function calling specs
            LongTermMemoryRepository ltmRepo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
            RecallMemoryTool recallMemoryTool = new RecallMemoryTool(ltmRepo);
            recallMemoryTool.setAutoKeywords(hanlpKeywords);

            EvolutionRecordRepository evoRepo = new EvolutionRecordRepository(DatabaseConfig.getDataSource());

            final List<Tool> availableTools = Arrays.asList(
                    new WeatherTool(userAliasRepo),
                    new UserAffinityTool(userAffinityRepo),
                    new UserAliasTool(userAliasRepo, String.valueOf(BotConfig.getBotQq())),
                    new SendPrivateTool(botInstance),
                    new PokeTool(botInstance),
                    new VoiceTool(botInstance, ttsService),
                    new RankTool(),
                    new ReminderTool(),
                    new LuckTool(),
                    new ProfessionTool(),
                    new ProfessionPKTool(),
                    new MemoryTool(botMemory),
                    new KnowledgeBaseTool(knowledgeService),
                    new LearnKnowledgeTool(knowledgeService),
                    new SendGroupTool(botInstance),
                    new SendFileTool(botInstance),
                    new QueryFileTool(botInstance, this, userId, sessionId),
                    new SearchHistoryTool(ltmRepo),
                    new RememberFactTool(ltmRepo),
                    recallMemoryTool,
                    new ScheduleEventTool(ltmRepo),
                    new SendStatusTool(botInstance, groupId, userId),
                    new WebSearchTool(),
                    new SanjiaoTool(botInstance, groupId),
                    new EggGroupSearchTool(eggGroupDataCenter),
                    new TravelingMerchantTool(merchantApiService != null ? merchantApiService : new MerchantApiService()),
                    new MerchantSubscribeTool(merchantRepo != null ? merchantRepo : new MerchantRepository()),
                    new AwaitReplyTool(botInstance, this, groupId, userId, sessionId),
                    new QueryLifeTool(lifeEngine),
                    new ShellTool(shellService != null ? shellService : new ServerAdminService(), userId),
                    new ScheduleRecurringTaskTool(new RecurringTaskRepository(DatabaseConfig.getDataSource())),
                    new StickerTool(botInstance),
                    new LinkPreviewTool(),
                    new SelfEvolveTool(userId, evoRepo),
                    new RestartBotTool(userId),
                    new UpdateConfigTool(runtimeConfig, userId),
                    new ReadCodeTool(),
                    new CreateFileTool(userId),
                    new AuditTool(),
                    new AuditAgentTool(),
                    new DigestTool(),
                    new SearchDigestTool(),
                    new EvolutionHistoryTool(evoRepo),
                    new QueryImagesTool(new MessageRepository())
            );

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
            requestBodyObj.put("tools", toolSpecs);
            requestBodyObj.put("tool_choice", "auto");

            String requestBody = objectMapper.writeValueAsString(requestBodyObj);
            logger.debug("请求 Gemini API (Model: {}): {}", modelName, requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                    .build();

            HttpResponse<String> response = null;
            int retryCount = 0;
            int maxRetries = this.bailianMaxRetries;
            
            while (retryCount <= maxRetries) {
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (java.net.http.HttpTimeoutException e) {
                    retryCount++;
                    if (retryCount > maxRetries) {
                        logger.warn("Gemini API 重试{}次后仍超时", maxRetries);
                        throw e;
                    }
                    logger.warn("Gemini API 第{}次超时，正在重试...", retryCount);
                    Thread.sleep(1000 * retryCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("请求被中断", e);
                }
            }

            if (response == null) {
                throw new RuntimeException("AI 服务请求失败：响应为空");
            }

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
                            Map<String, Object> errMsg = new HashMap<>();
                            errMsg.put("role", "tool");
                            errMsg.put("tool_call_id", callId);
                            errMsg.put("content", "参数解析错误: " + e.getMessage());
                            messages.add(errMsg);
                            continue;
                        }

                        String result = tool.execute(args);
                        toolCalls++;
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
                nextBody.put("tools", toolSpecs);
                nextBody.put("tool_choice", "auto");

                String nextBodyJson = objectMapper.writeValueAsString(nextBody);
                JsonNode nextMsg = null;
                int toolRetryCount = 0;

                while (toolRetryCount <= this.bailianMaxRetries) {
                    try {
                        HttpRequest nextReq = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(nextBodyJson))
                                .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                                .build();
                        HttpResponse<String> nextResp = httpClient.send(nextReq, HttpResponse.BodyHandlers.ofString());
                        if (nextResp.statusCode() == 200) {
                            JsonNode sr = objectMapper.readTree(nextResp.body());
                            JsonNode sc = sr.path("choices");
                            if (sc.isArray() && !sc.isEmpty()) {
                                nextMsg = sc.get(0).path("message");
                                break;
                            }
                        }
                        toolRetryCount++;
                    } catch (java.net.http.HttpTimeoutException e) {
                        toolRetryCount++;
                        if (toolRetryCount > this.bailianMaxRetries) {
                            logger.warn("工具第{}轮回调超时，已重试{}次", toolRound, this.bailianMaxRetries);
                        } else {
                            logger.warn("工具第{}轮回调第{}次超时，正在重试...", toolRound, toolRetryCount);
                            Thread.sleep(1000L * toolRetryCount);
                        }
                    } catch (Exception e) {
                        logger.warn("工具第{}轮回调失败: {}", toolRound, e.getMessage());
                        break;
                    }
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
                history.add(new Message("assistant", reply));

                if (groupId != null) {
                    recordUserInteraction(groupId, userId, reply);
                    recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");

                    if (!reply.equals("抱歉，刚才走神了...") &&
                            !reply.equals("嗯...再问一次吧") &&
                            !reply.trim().isEmpty()) {

                        List<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
                        long now = System.currentTimeMillis();

                        msgHistory.removeIf(ts -> now - ts > 60_000);

                        if (msgHistory.size() >= MAX_MESSAGES_PER_MINUTE) {
                            logger.debug("糖果熊在群 {} 发言已达上限，跳过回复", groupId);
                            return GenerationResult.silent(toolCalls, 0);
                        }

                        msgHistory.add(now);
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
                content.add(Map.of("type", "text", "text", "请非常简短地描述这张图片的内容，用中文，控制在30字以内。不要加前缀如'这张图片'，直接说内容。"));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageDataUris.get(i))));

                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "user", "content", content));

                Map<String, Object> body = new HashMap<>();
                body.put("model", visionModel);
                body.put("messages", messages);
                body.put("max_tokens", 150);

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

    /** 简单调用聊天模型，返回纯文本响应（无工具、无会话、无状态注入） */
    public String generateRaw(String prompt) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));

            Map<String, Object> body = new HashMap<>();
            body.put("model", bailianChatModel);
            body.put("messages", messages);
            body.put("max_tokens", 512);
            body.put("temperature", 0.8);

            String jsonBody = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baiLianBaseUrl))
                    .header("Authorization", "Bearer " + baiLianApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(30))
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

        // AI 自己决定的分段（|---| 分隔符）
        if (reply.contains("|---|")) {
            return Arrays.stream(reply.split("\\|---\\|"))
                    .map(String::trim)
                    .map(s -> s.replaceAll("\\n{2,}", "\n"))  // 清理段内残留空行
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }

        // 兜底：AI 没用 |---| 但有空行时，按空行切分段落
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
    private String formatRelativeTime(long timestamp) {
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

    public void setPendingImageData(String json) { pendingImageData.set(json); }

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
        List<Long> history = groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
        history.removeIf(ts -> System.currentTimeMillis() - ts > 300_000);
        return history.size() < 10;
    }

    public void recordReaction(String groupId) {
        groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }

    // ===== 群消息记录 =====
    public void addGroupMessage(String groupId, String message) {
        recordGroupContext(groupId, "unknown", "someone", message, "user_message");
    }
    // 存储每个群最近 N 条完整发言（含发言人）
    private final Map<String, Deque<PublicMessage>> publicGroupHistory = new ConcurrentHashMap<>();

    public static class PublicMessage {
        public final String userId;
        public final String nickname;
        public final String content;
        public final long timestamp;

        public PublicMessage(String userId, String nickname, String content) {
            this.userId = userId;
            this.nickname = nickname;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // 提供方法供 AIHandler 调用
    public void recordPublicGroupMessage(String groupId, String userId, String nickname, String message) {
        if (groupId == null || message.trim().isEmpty()) return;

        // 过滤机器人自己的消息（避免重复）
        if (userId.equals(String.valueOf(BOT_QQ))) return;

        Deque<PublicMessage> history = publicGroupHistory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());

        // 清理过期消息（比如 10 分钟前的）
        long now = System.currentTimeMillis();
        history.removeIf(msg -> now - msg.timestamp > 10 * 60_000);

        // 保留最近 10 条
        if (history.size() >= 10) {
            history.pollFirst();
        }

        history.offerLast(new PublicMessage(userId, nickname, message));
    }
    public Deque<PublicMessage> getPublicGroupHistory(String groupId) {
        return publicGroupHistory.get(groupId);
    }
}