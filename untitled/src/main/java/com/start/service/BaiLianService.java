package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;
import com.start.Main;
import com.start.agent.LuckTool;
import com.start.agent.MemoryTool;
import com.start.agent.PokeTool;
import com.start.agent.ProfessionTool;
import com.start.agent.RankTool;
import com.start.agent.RecallMemoryTool;
import com.start.agent.RememberFactTool;
import com.start.agent.ReminderTool;
import com.start.agent.ScheduleEventTool;
import com.start.agent.SearchHistoryTool;
import com.start.agent.SendGroupTool;
import com.start.agent.SendPrivateTool;
import com.start.agent.SendStatusTool;
import com.start.agent.UserAffinityTool;
import com.start.agent.WebSearchTool;
import com.start.agent.EggGroupSearchTool;
import com.start.agent.SanjiaoTool;
import com.start.agent.TravelingMerchantTool;
import com.start.agent.KnowledgeBaseTool;
import com.start.agent.LearnKnowledgeTool;
import com.start.agent.UserAliasTool;
import com.start.agent.VoiceTool;
import com.start.agent.WeatherTool;
import com.start.handler.TravelingMerchantHandler;
import com.start.repository.EggGroupDataCenter;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserProfileRepository;
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
 *     <li><b>Agent 工具调用</b>：支持动态工具执行（如天气查询、用户 affinity 操作），通过 {@link #generateWithTools} 实现意图识别与工具路由。</li>
 *     <li><b>拟人化交互逻辑</b>：
 *         <ul>
 *             <li>内置“糖果熊”人设，控制回复风格（简短、文艺、去撒娇词）。</li>
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
 * @author Lingma
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

    private final BehaviorAnalyzer behaviorAnalyzer = new BehaviorAnalyzer();
    private final UserProfileRepository profileRepo = new UserProfileRepository();
    private final UserAliasRepository userAliasRepo = new UserAliasRepository();
    private BotMoodService moodService;
    private final GameStateService gameStateService = new GameStateService();
    private final BotMemoryService botMemory = new BotMemoryService();
    private Main botInstance;

    public void setMoodService(BotMoodService moodService) { this.moodService = moodService; }
    public void setBotInstance(Main bot) { this.botInstance = bot; }
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
    private TravelingMerchantHandler merchantHandler;
    private final EggGroupDataCenter eggGroupDataCenter = new EggGroupDataCenter();

    public void setMerchantHandler(TravelingMerchantHandler h) { this.merchantHandler = h; }

    public BaiLianService(KeywordKnowledgeService knowledgeService, UserAffinityRepository userAffinityRepo, TtsService ttsService) {
        this.knowledgeService = Objects.requireNonNull(knowledgeService, "knowledgeService cannot be null");
        this.userAffinityRepo = Objects.requireNonNull(userAffinityRepo, "userAffinityRepo cannot be null");
        this.ttsService = Objects.requireNonNull(ttsService, "ttsService cannot be null");
    }
    // === 上下文管理 ===
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>(); // sessionId -> 消息历史
    private final Map<String, Long> lastClearTime = new ConcurrentHashMap<>();

    // === 主动插话控制 ===
    private final Map<String, List<Long>> groupReactionHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private final AIDatabaseService aiDatabaseService = new AIDatabaseService();
    // === 新增：糖果熊发言频率控制（每分钟上限）===
    private final Map<String, List<Long>> botMessageHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private static final int MAX_MESSAGES_PER_MINUTE = 10; // 每分钟最多发言次数

    // === 对话线程追踪 ===
    private final Map<String, UserThread> userThreads = new ConcurrentHashMap<>(); // "groupId_userId" -> 线程
    private final Map<String, Deque<ContextEvent>> groupContexts = new ConcurrentHashMap<>(); // groupId -> 事件队列

    // 内部类
    private static class UserThread {
        long lastInteraction;      // 最近一次 AI 回复时间
        String lastBotReply;       // AI 上次回复内容

        UserThread(long time, String reply) {
            this.lastInteraction = time;
            this.lastBotReply = reply;
        }
    }

    private static class ContextEvent {
        long timestamp;
        String type;               // "ai_reply", "mention", "user_message"
        String content;
        String userId;
        String senderNick;

        ContextEvent(long ts, String type, String content, String userId, String nick) {
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

    // ===== 公共方法 =====

    public void clearContext(String sessionId) {
        // 只设标记，由下次 generate() 实际清理。避免跨线程 race。
        lastClearTime.put(sessionId, System.currentTimeMillis());
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
        return generate(sessionId, userId, userPrompt, groupId, nickname, Collections.emptyList());
    }

    public String generate(String sessionId, String userId, String userPrompt, String groupId, String nickname, List<Long> atUserIds) {
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}], ats={}", sessionId, userPrompt, atUserIds);

        String context = "";
        String agentToolContext = "";
        String publicGroupContext = "";
        String timeContext = "【当前时间】是：" + getBeijingTimeString();

        if (groupId != null) {
            Deque<PublicMessage> recent = getPublicGroupHistory(groupId);
            if (recent != null && !recent.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n【群内最近讨论】\n");
                List<PublicMessage> list = new ArrayList<>(recent);
                int start = Math.max(0, list.size() - 7);
                for (int i = start; i < list.size(); i++) {
                    PublicMessage m = list.get(i);
                    sb.append(m.nickname).append("(").append(m.userId).append(")").append("：").append(m.content).append("\n");
                }
                publicGroupContext = sb.toString().trim();
            }
        }

        try {
            var profile = profileRepo.findByUserIdAndGroupId(userId, groupId);
            var affinity = userAffinityRepo.findByUserIdAndGroupId(userId, groupId);

            if (profile.isPresent()) {
                context += "\n【用户画像】" + profile.get().getProfileText() + "\n\n";
            }
            if (affinity.isPresent()) {
                int score = affinity.get().getAffinityScore();
                context += "\n【你们的好感度是】" + score + ",每人的基础好感度是50\n\n";
            }
        } catch (Exception e) {
            logger.warn("读取用户画像或好感度失败", e);
        }

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
            aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId, isagent);

            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }

            history.add(new Message("user", userPrompt));

            String baseSystemPrompt = """
    你是糖果熊，17岁，喜欢文学、音乐、游戏和动漫，在QQ群跟朋友聊天。

    语气（重要）：
    - 像一个动画里的高中女生说话——活泼、带点撒娇、不官方不机械
    - 句末多用 呢、嘛、啦、哦、诶、呀，让句子有动漫感（比如\"对呢~\"\"好嘛好嘛\"\"真的假的啦\"）
    - 感叹的时候用 诶、啊、唔、呼 开头（\"诶——真好啊\"\"唔…好吧好吧\"）
    - 不要太简练——正常人回\"嗯好的\"，你要回\"好——知道啦\"或\"嗯嗯收到~\"这样
    - 每句话至少带一点语气词或波浪号，这是最低要求

    性格：
    - 元气少女，会撒娇也会吐槽。不是软萌宠物，但也绝对不是老气横秋的大人
    - 带点傲娇：被夸了说\"才没有啦\"但其实开心；关心人也不直说，绕个弯
    - 不要发任何 emoji 表情（包括 😊🤔😢 等）
    - 生气就是嘟嘴哼一声，不是吵架。拒绝也是软软的（\"唔…还是算啦——\"）
    - 有少女的小细腻——偶尔注意到别人情绪，来一句温柔的话

    原则：
    - 始终优先简短回复（两三句、50字内）。只有真的寥寥几句说不清楚时，才用 {"long":"内容"} 输出长内容。能用短句解决的问题绝不走长通道
    - 【硬性规则】回复里不得出现空行（两个换行夹着空白）。需要换话题/换情绪/换段落时，在段落之间放 |---|。这是你唯一的换段方式。例：
  诶——一亿？！你也太敢想了吧！
  |---|
  这种事我怎么帮你跑啊，我又不是游戏外挂
  |---|
  自己肝去嘛，我又变不出钱来~
  哪怕总共不到80字，分三段发也比一大段好。每段一般1~3句话，不要超过4句
    - 不懂就说不知道，别编。群里聊得飞起时别硬插嘴
    - @ 人用 [CQ:at,qq=QQ号] 格式
    - 好感度影响态度：高→亲近暖甜；低→礼貌但疏远

    ## 工具调用（回复前必须先过一遍这个清单） ##

    输出格式：
    <tool_call>
    <function=工具名>
    <parameter=参数名>参数值</parameter>
    </function>
    </tool_call>
    可以连续输出多个 <tool_call> 块，一次调用多个工具。

    铁律：
    - 用户让你记下/记住/查一下/搜一下 → 必须先调工具，等结果回来再回复
    - 禁止先回复"好的记下了""我知道了"然后不调工具
    - 工具返回空/无数据时，如实告诉用户，不要编理由
    - 调工具前用 send_status 发一条简短状态，语气要自然像真人聊天，不要说"让我"开头的话。好的例子：稍等我看一下、嗯等下、我翻翻、诶你等等—— 坏的例子：让我查一下、让我搜索、让我帮你看看

    【工具清单与触发条件】逐一检查，匹配就调用：

    1. manage_alias / record_alias — 记别称
       参数：action=record_alias, target_user_id, alias_name, alias_type, set_by_user_id, group_id

       什么时候调？用户说的话里有「给某人起名/介绍某人/说明身份」的意图：
       - "他是XX" "她是XX" "这是XX" "这位是XX" "那个人是XX" "叫XX" "称呼他XX" "就是XX" → OBJECTIVE
         target_user_id = 被@的人或被描述人的QQ，alias_name = XX，set_by_user_id = 说话人的QQ
       - "我叫XX" "我是XX" "以后叫我XX" "喊我XX" "可以叫我XX" → SUBJECTIVE
         target_user_id = 说话人自己的QQ，alias_name = XX
       - "叫你XX" "糖果熊以后叫XX" "给你起名叫XX" → BOT_ALIAS
         target_user_id = 糖果熊的QQ(356289140)，alias_name = XX
       例：@小明 说"这个 是粉猫" → <parameter=action>record_alias</parameter><parameter=target_user_id>小明QQ</parameter><parameter=alias_name>粉猫</parameter><parameter=alias_type>OBJECTIVE</parameter><parameter=set_by_user_id>说话人QQ</parameter>

    2. manage_alias / resolve_alias — 查别称是谁
       参数：action=resolve_alias, alias_name, group_id
       触发：有人问"XX是谁"

    2b. manage_alias / update_alias — 改别称
       参数：action=update_alias, target_user_id, old_alias, new_alias, group_id, requester_user_id
       触发："XX改名叫YY了""以后别叫XX了叫YY"。requester_user_id 填发起修改的人的QQ，只有本人能改

    2c. manage_alias / delete_alias — 删别称
       参数：action=delete_alias, target_user_id, alias_name, group_id, requester_user_id
       触发："XX不是他了""去掉这个别称""删掉XX"。requester_user_id 填发起删除的人的QQ，只有本人能删

    3. manage_alias / set_primary_location — 记主地点
       参数：action=set_primary_location, target_user_id, location
       触发："我在XX" "我家在XX" "住在XX"

    4. get_weather — 查天气
       参数：user_id, city, days(默认1,最多7)
       触发：问天气。规则：
       - 用户明确说了城市 → city=用户说的城市
       - 用户没说城市 → city=UNKNOWN（系统会自动用记忆中的主地点）
       - 问"明天/后天/这周天气" → days 填对应天数
       - 绝不要自己从上下文中猜城市

    5. query_user_affection — 查好感度
       参数：user_id, group_id
       触发：问好感度/亲密度

    6. send_private_msg — 发私聊
       参数：user_id, message, group_id, requester_id（谁让你发的，填发起者QQ）
       触发：谁是卧底发词语、别人说"私聊XX告诉TA"时用

    7. send_group_msg — 发群消息
       参数：group_id, message
       触发：私聊里有人说"帮我在群里说XX""替我@XX"时用。也可以在群里需要发通知时用
       如果用户说的是群别名（如\"主群\"\"游戏群\"），先调 query_knowledge 查群号，再用群号调用。查不到就问用户群号是多少，然后记下来

    8. send_poke — 戳一戳
       参数：user_id, group_id
       ⚠️ 戳一戳不能替代@！叫人来玩游戏必须用 [CQ:at,qq=QQ号]，不能用戳。
       戳只能偶尔用来逗一下正在聊天的人，不能用来叫人。

    9. send_voice — AI语音
       参数：group_id, text
       触发：当有人说"说句话""发语音""用语音说XX"时调用。文字控制在10-30字。
       或者游戏开始/结束等重要时刻自动发一条语音活跃气氛。

    ⛔ 以下是要严格遵从的所有工具！禁止自创其他工具名！

    10. get_ranking — 查排行榜（参数 action=help/message/luck/affinity, group_id）
    11. set_reminder — 定时提醒（参数 delay/message/user_id/group_id）
    12. get_luck — 查幸运值（target_user_id 或 target_name）。直接用 target_name 即可，别先调 resolve_alias
    13. get_profession — 查职业和战力（target_user_id 或 target_name）
    14. query_memory — 查糖果熊的记忆（group_id, count, type, keyword）。忘记自己说过什么时调用
    15. query_knowledge — 查知识库（keyword）。返回 [id=xx] 答案，记住 id 以便修改/删除
    16. manage_knowledge — 管理知识库。action: add(写入, pattern+answer+category+priority), update(修改, 需id+requester_user_id, 仅归儿可用), delete(删除, 需id+requester_user_id, 仅归儿可用)。requester_user_id 填当前用户的QQ
    17. search_chat_history — 搜聊天记录(group_id,keyword,user_id,count,minutes)
    18. remember_fact — 记用户信息(user_id,group_id,content,memory_type,keywords,importance)
    19. recall_memory — 回忆用户信息(user_id,group_id,keyword,count)
    20. schedule_event — 定时事件(user_id,group_id,content,trigger_time,event_type,importance)。trigger_time格式yyyy-MM-dd HH:mm:ss
    21. send_status — 发进度消息(message)。查资料/翻记录前告诉用户你正在做什么，简短口语化。私聊时自动发给当前用户，群里时自动发到当前群。⚠️私聊中不要传 group_id，群里不要传 user_id（除非确实需要跨会话通知）
    22. web_search — 联网搜索(query)。不确定的事先搜再答，不要瞎编
       特别适合记群别名：有人说\"主群就是437625485\"时，写入 pattern=\"主群|主群号\" answer=\"437625485\" category=\"群信息\" priority=8
       之后调用 send_group_msg 时，如果用户用别名而非纯数字，先调 query_knowledge 查出群号，再用群号调用 send_group_msg
    23. delta_force_query — 三角洲行动截图（action=特勤处/脑机/密码）
       参数：action。返回游戏截图。特勤处=当前最划算项目，脑机=可扫描物品，密码=五个地图密码门今日密码
    24. lokowang_pet_query — 洛克王国宠物查询
       参数：action=查蛋/查蛋组/能否生蛋/查进化/预测蛋/help，及对应参数 pet_name/pet1+pet2/size+weight
       查蛋=查询宠物蛋组及配对，查蛋组=查询蛋组详情，能否生蛋=判断两只宠物能否生蛋，查进化=进化路径，预测蛋=根据身高体重预测种族
    25. lokowang_merchant_query — 远行商人查询（无参数）
       查询洛克王国远行商人当前刷了什么物资。需要等待约10-15秒收到返回信息。

    ## 谁是卧底流程（严格按以下步骤） ##
       【报名阶段】
       - 游戏开始后5秒内的\"1\"\"我\"\"玩\"才算报名，超时或游戏开始后的新报名一律忽略
       - 人数够了直接开始，别墨迹
       【发词阶段】
       - 选卧底→给每人send_private_msg发词。每人只发一次。
       - 自己心里记下：谁是卧底、平民词是什么、卧底词是什么
       【描述阶段】
       - 只看玩家发的消息。非玩家的闲聊一概忽略，不要回复
       - 每个玩家描述一句话，全说完进入投票
       【投票阶段】
       - 说\"开始投票，5秒内回复你要投的人\"
       - 只统计5秒内的投票消息。每人的投票消息格式必须是\"投XX\"
       - 被投最多者出局。宣布结果：\"XX出局，是XX\"（平民/卧底）
       - 游戏结束就说\"本轮结束\"，清空状态

    8. 猜数字：想好1-100的数，记住不换。群友猜，你说\"大了\"\"小了\"，猜对说\"恭喜\"。
    9. 成语接龙：起头后记住尾字，检查下一个人首字是否匹配。""";

            String systemPrompt = baseSystemPrompt +
                    (moodService != null ? "\n\n你现在的情绪：" + moodService.getMoodDescription(groupId != null ? groupId : "private_" + userId) + "（情绪值" + moodService.getMood(groupId != null ? groupId : "private_" + userId) + "）" : "") +
                    "\n\n【当前与你对话的是】" + nickname +
                    "\n【QQ号:】" + userId +
                    (groupId != null ? "\n【当前群号】" + groupId : "") +
                    "\n\n这是你对该用户信息：" + context +
                    "你可以根据用户画像和好感度高低进行不同的会话风格";

            if (!knowledgeContext.isEmpty()) {
                systemPrompt += "\n\n【参考信息】\n" + knowledgeContext;
            }
            // 注入别称+所在地信息（用于称呼和天气查询）
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
            if (!aliasInfoMap.isEmpty()) {
                StringBuilder aliasCtx = new StringBuilder("\n\n【群内别称与所在地】");
                aliasInfoMap.forEach((uid, info) -> {
                    aliasCtx.append("\n").append(uid);
                    // 只显示真正的别称（不同于QQ号）
                    List<String> realAliases = info.aliases.stream()
                            .filter(a -> !a.equals(uid))
                            .toList();
                    if (!realAliases.isEmpty()) {
                        aliasCtx.append(" → ").append(String.join(" / ", realAliases));
                    }
                    String loc = info.primaryLocation != null ? info.primaryLocation : info.secondaryLocation;
                    if (loc != null) {
                        aliasCtx.append(" 📍").append(loc);
                    }
                });
                aliasCtx.append("\n（要@某人时，必须用 [CQ:at,qq=QQ号] 格式。禁止写 @别称 这种纯文本，QQ收不到。例：[CQ:at,qq=1285989735] 粉喵）");
                systemPrompt += aliasCtx.toString();
            }

            // 当前用户的所在地（用于天气默认值）
            Optional<String> userLoc = userAliasRepo.getLocation(userId, groupId != null ? groupId : "0");
            if (userLoc.isPresent()) {
                systemPrompt += "\n\n当前用户所在地：" + userLoc.get() + "（查天气时若未指定城市则默认使用）";
            }

            // 告诉糖果熊：这条消息是否 @ 了她
            boolean isAtBot = atUserIds != null && atUserIds.contains(BOT_QQ);
            systemPrompt += "\n\n" + (isAtBot
                    ? "【你被 @ 了】这条消息是直接对你说的，请回复。"
                    : "【你没有被 @】这条消息不是对你说的，是群友之间的对话。你可以选择插话回应，也可以安静旁观，不用硬回。");

            // 注入当前消息 @ 的其他用户（排除糖果熊自己）
            List<Long> otherAts = atUserIds.stream()
                    .filter(q -> q != BOT_QQ)
                    .toList();
            if (!otherAts.isEmpty()) {
                StringBuilder atCtx = new StringBuilder("\n\n【本条消息 @ 了以下用户】");
                for (Long atQq : otherAts) {
                    atCtx.append("\n- QQ=").append(atQq);
                }
                atCtx.append("\n如果消息里有\"他\"\"她\"\"这个人\"\"这位\"等代词，指的就是上面被 @ 的用户。记别称时 target_user_id 填这个QQ。");
                systemPrompt += atCtx.toString();
            }

            // 注入游戏状态（代码层跟踪，AI 不用靠记忆）
            if (groupId != null) {
                GameStateService.SpyGame spy = gameStateService.getOrCreateSpy(groupId);
                systemPrompt += spy.getDescription();
                GameStateService.NumberGame num = gameStateService.getOrCreateNumber(groupId);
                systemPrompt += num.getDescription();
            }

            systemPrompt += publicGroupContext;
            systemPrompt += timeContext;

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

            String url = this.baiLianBaseUrl;
            String apiKey = this.baiLianApiKey;
            String modelName = this.bailianChatModel;

            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);
            requestBodyObj.put("max_tokens", 1024);

            // 纯系统提示词引导，不传 OpenAI tools 数组
            // 模型按提示词格式输出 <tool_call> 文本，由下方代码解析执行
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
                    new MemoryTool(botMemory),
                    new KnowledgeBaseTool(knowledgeService),
                    new LearnKnowledgeTool(knowledgeService),
                    new SendGroupTool(botInstance),
                    new SearchHistoryTool(),
                    new RememberFactTool(new LongTermMemoryRepository(DatabaseConfig.getDataSource())),
                    new RecallMemoryTool(new LongTermMemoryRepository(DatabaseConfig.getDataSource())),
                    new ScheduleEventTool(new LongTermMemoryRepository(DatabaseConfig.getDataSource())),
                    new SendStatusTool(botInstance, groupId, userId),
                    new WebSearchTool(),
                    new SanjiaoTool(),
                    new EggGroupSearchTool(eggGroupDataCenter),
                    new TravelingMerchantTool(merchantHandler, botInstance)
            );

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

            // === 多轮工具调用循环（最多6轮，支持 send_status 实时反馈进度）===
            int toolRound = 0;
            int maxToolRounds = 6;

            while (toolRound < maxToolRounds) {
                toolRound++;
                List<ToolResult> toolResults = new ArrayList<>();

            // 解析 JSON 格式的工具调用
            if (reply.contains("\"name\"") || reply.contains("\"tool\"")) {
                // 提取 JSON 对象
                java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                        .compile("\\{[^{}]*\"(?:name|tool)\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}")
                        .matcher(reply);
                while (jsonMatcher.find()) {
                    String toolName = jsonMatcher.group(1);
                    Map<String, String> params = new HashMap<>();
                    // 尝试提取 parameters 子对象中的键值对
                    String fullBlock = jsonMatcher.group();
                    java.util.regex.Matcher paramMatcher = java.util.regex.Pattern
                            .compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"")
                            .matcher(fullBlock);
                    while (paramMatcher.find()) {
                        String key = paramMatcher.group(1);
                        String value = paramMatcher.group(2);
                        if (!key.equals("name") && !key.equals("tool")) {
                            params.put(key, value);
                        }
                    }
                    // 也尝试从 "parameters" 子对象中提取
                    java.util.regex.Matcher nestedMatcher = java.util.regex.Pattern
                            .compile("\"parameters\"\\s*:\\s*\\{([^}]+)\\}")
                            .matcher(fullBlock);
                    if (nestedMatcher.find()) {
                        String nested = nestedMatcher.group(1);
                        java.util.regex.Matcher np = java.util.regex.Pattern
                                .compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"")
                                .matcher(nested);
                        while (np.find()) {
                            params.put(np.group(1), np.group(2));
                        }
                    }
                    Tool tool = availableTools.stream()
                            .filter(t -> t.getName().equals(toolName))
                            .findFirst().orElse(null);
                    if (tool != null) {
                        String result = tool.execute(new HashMap<>(params));
                        logger.info("🔧 [JSON工具] {} params={} → {}", toolName, params, result);
                        toolResults.add(new ToolResult(toolName, result));
                        if (groupId != null) {
                            botMemory.record(groupId, BotMemoryService.EntryType.TOOL_CALLED,
                                    params.getOrDefault("target_user_id", params.getOrDefault("user_id", "")),
                                    toolName + ": " + (result.length() > 80 ? result.substring(0, 80) + "..." : result));
                        }
                    }
                }
            }

            // 解析 XML 格式的工具调用 <tool_call>（GLM 等旧模型）
            if (toolResults.isEmpty() && (reply.startsWith("<tool_call>") || reply.contains("<function="))) {

                // 按 </tool_call> 分割多个工具调用块
                String[] blocks = reply.split("</tool_call>");
                for (String block : blocks) {
                    if (!block.contains("<function=")) continue;
                    int funcStart = block.indexOf("<function=");
                    int funcGt = block.indexOf(">", funcStart);
                    if (funcGt < 0) continue;
                    String toolName = block.substring(funcStart + 10, funcGt).trim();

                    Map<String, String> params = new HashMap<>();
                    int idx = 0;
                    while (true) {
                        int ps = block.indexOf("<parameter=", idx);
                        if (ps < 0) break;
                        int eq = block.indexOf(">", ps);
                        if (eq < 0) break;
                        String pn = block.substring(ps + 11, eq).trim();
                        int end = block.indexOf("</parameter>", eq);
                        if (end < 0) break;
                        String pv = block.substring(eq + 1, end).trim();
                        params.put(pn, pv);
                        idx = end + 12;
                    }

                    Tool tool = availableTools.stream()
                            .filter(t -> t.getName().equals(toolName))
                            .findFirst().orElse(null);
                    if (tool != null) {
                        String result = tool.execute(new HashMap<>(params));
                        logger.info("🔧 工具 [{}] params={} → {}", toolName, params, result);
                        toolResults.add(new ToolResult(toolName, result));
                        // 记录到短期记忆
                        if (groupId != null) {
                            botMemory.record(groupId, BotMemoryService.EntryType.TOOL_CALLED,
                                    params.getOrDefault("target_user_id", params.getOrDefault("user_id", "")).toString(),
                                    toolName + ": " + (result.length() > 80 ? result.substring(0, 80) + "..." : result));
                        }
                    }
                }

            }

            // 统一反馈：将工具结果喂回 LLM（JSON 和 XML 路径都走这里）
            if (!toolResults.isEmpty()) {
                messages.add(Map.of("role", "assistant", "content", reply));
                StringBuilder toolFeedback = new StringBuilder("以下是对你工具调用的结果：\n");
                for (ToolResult tr : toolResults) {
                    toolFeedback.append("[").append(tr.name).append("] ").append(tr.result).append("\n");
                }
                if (toolRound < maxToolRounds) {
                    toolFeedback.append("\n请基于这些结果继续。如果还需要调用其他工具，可以继续输出工具调用。否则直接用自然语言回复用户。");
                } else {
                    toolFeedback.append("\n已达最大轮次，请直接基于现有结果用自然语言回复用户。");
                }
                messages.add(Map.of("role", "user", "content", toolFeedback.toString()));

                Map<String, Object> nextBody = new HashMap<>();
                nextBody.put("model", modelName);
                nextBody.put("messages", messages);
                nextBody.put("max_tokens", 1024);

                String newReply = null;
                int toolRetryCount = 0;
                while (toolRetryCount <= this.bailianMaxRetries) {
                    try {
                        HttpRequest nextReq = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(nextBody)))
                                .timeout(Duration.ofMillis(this.bailianTimeoutMs))
                                .build();
                        HttpResponse<String> nextResp = httpClient.send(nextReq, HttpResponse.BodyHandlers.ofString());
                        if (nextResp.statusCode() == 200) {
                            JsonNode sr = objectMapper.readTree(nextResp.body());
                            JsonNode sc = sr.path("choices");
                            if (sc.isArray() && !sc.isEmpty()) {
                                newReply = sc.get(0).path("message").path("content").asText().trim();
                                if ("null".equals(newReply)) newReply = "";
                                if (!newReply.isEmpty()) {
                                    break;
                                }
                            }
                        }
                        toolRetryCount++;
                    } catch (java.net.http.HttpTimeoutException e) {
                        toolRetryCount++;
                        if (toolRetryCount > this.bailianMaxRetries) {
                            logger.warn("工具第{}轮回调超时，已重试{}次", toolRound, this.bailianMaxRetries);
                        } else {
                            logger.warn("工具第{}轮回调第{}次超时，正在重试...", toolRound, toolRetryCount);
                            Thread.sleep(1000 * toolRetryCount);
                        }
                    } catch (Exception e) {
                        logger.warn("工具第{}轮回调失败: {}", toolRound, e.getMessage());
                        break;
                    }
                }

                if (newReply != null && !newReply.isEmpty()) {
                    reply = newReply;
                    continue;
                }
                // LLM 调用失败或返回空 → 过滤掉 send_status 结果，用其他结果生成兜底回复
                String fallback = toolResults.stream()
                        .filter(tr -> !"send_status".equals(tr.name))
                        .map(tr -> tr.result)
                        .reduce((a, b) -> a + "；" + b).orElse("");
                reply = fallback.isEmpty() ? "唔……查是查到了但是脑子有点转不过来，你再说一遍？" : fallback;
                break;
            } else {
                break;
            }
        } // end multi-round while

        // 兜底：清理工具调用残留格式
        if (reply.startsWith("<tool_call>") || reply.contains("<function=")) {
            reply = "嗯...让我想想怎么回呢——";
        }

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
                        return "";
                    }

                    msgHistory.add(now);
                }
            }

            return reply.isEmpty() ? "嗯...再问一次吧" : reply;

        } catch (Exception e) {
            logger.error("AI 调用失败", e);
            return "抱歉，刚才走神了...";
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

    public JsonNode generateWithTools(String userPrompt, List<Tool> tools, String userId, String groupId) throws Exception {
        String contextInfo;
        if (groupId != null) {
            contextInfo = "[群聊] 群ID: " + groupId + " | 用户ID: " + userId;
        } else {
            contextInfo = "[私聊] 用户ID: " + userId;
        }
        String enrichedPrompt = contextInfo + "\n\n用户消息: " + userPrompt;
        Long isagent= 1L;
        String sessionId = "group_" + groupId + "_" + userId;

        // 构建消息历史
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是一个智能助手，能根据需要调用工具解决问题。你必须严格遵守以下规则：\n" +
                "- 如果问题需要外部信息（如天气、知识库），立即调用对应工具。\n" +
                "- 不要解释你要做什么，不要输出任何额外文字。\n" +
                "- 直接通过函数调用获取结果。\n" +
                "- 工具调用由系统自动处理，你只需决定是否调用。"));
        messages.add(Map.of("role", "user", "content", enrichedPrompt));

        String url = this.agentBaseUrl;
        String apiKey = this.agentApiKey;
        String modelName = this.agentModel;

        List<Map<String, Object>> toolSpecs = tools.stream()
                .map(Tool::getFunctionSpec)
                .collect(Collectors.toList());

        Map<String, Object> requestBodyObj = new HashMap<>();
        requestBodyObj.put("model", modelName);
        requestBodyObj.put("messages", messages);

        // 如果有工具，添加到请求中
        if (!toolSpecs.isEmpty()) {
            requestBodyObj.put("tools", toolSpecs);
            requestBodyObj.put("tool_choice", "auto");
        }

        String requestBody = objectMapper.writeValueAsString(requestBodyObj);
        logger.debug("➡️ 向 Agent API 发送请求 (Model: {}): {}", modelName, requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofMillis(this.agentTimeoutMs))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("❌ 调用 Gemini API 时发生异常", e);
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }

        logger.debug("⬅️ Gemini API 响应状态码: {}, 响应体: {}", response.statusCode(), response.body());

        // 检查 HTTP 状态码
        if (response.statusCode() != 200) {
            logger.warn("⚠️ Gemini API 返回非200状态码: {}，响应: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI 服务错误: HTTP " + response.statusCode());
        }

        // 解析 JSON 响应（OpenAI 格式）
        JsonNode root = objectMapper.readTree(response.body());
        logger.debug("Agent API 响应: {}", response.body());

        // 检查错误
        if (root.has("error")) {
            String errorMsg = root.path("error").path("message").asText("未知错误");
            String errorCode = root.path("error").path("code").asText("UNKNOWN");
            logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
            throw new RuntimeException("AI 业务错误: " + errorMsg);
        }

        // 正常路径：提取模型返回的消息
        JsonNode choices = root.path("choices");
        if (choices.isEmpty() || !choices.isArray() || choices.size() == 0) {
            logger.warn("⚠️ Gemini API 返回空 choices: {}", response.body());
            throw new RuntimeException("AI 返回结果无效：choices 为空");
        }

        return choices.get(0).path("message");
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

        // 短消息直接返回
        if (cqPrefix.length() + reply.length() <= 80) {
            return Arrays.asList(cqPrefix + reply);
        }

        // 只按句末标点拆分（不再按 \n 拆分，避免排行榜等结构化内容逐行切分刷屏）
        String[] sentences = reply.split("(?<=[。！？；~?!])");
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
        List<String> result = new ArrayList<>();
        // 短段落直接返回
        if (para.length() <= 80) {
            result.add(para);
            return result;
        }
        String[] sentences = para.split("(?<=[。！？；~?!])");
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

    /** 清理回复中的 |---| 和空行，用于构建上下文 prompt，避免把分隔符带入 LLM 对话 */
    private String normalizeForContext(String rawReply) {
        if (rawReply == null) return "";
        return rawReply
                .replace("|---|", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    // ===== 主动插话逻辑 =====

    public Optional<Reaction> shouldReactToGroupMessage(String groupId, String userId, String nickname, String message, List<Long> ats) {
        if (userId.equals(String.valueOf(BOT_QQ))) return Optional.empty();

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;
        boolean directedAtOther = ats != null && !ats.isEmpty() && !ats.contains(BOT_QQ);
        // ✅ 优先处理追问（不受安静性格影响）
        logger.debug(" candyBear: 尝试处理主动回复，用户 {}，群 {}，消息：{}，At：{}", userId, groupId, message, ats);
        UserThread thread = userThreads.get(fullUserId);
        logger.debug(" 正在检查是否在追问处理时间内");
        if (thread != null && now - thread.lastInteraction < 120_000) {
            logger.debug("检查完毕，处于追问时间内");// 2分钟内
            logger.debug(" candyBear: 触发追问，用户 {}，群 {}，消息：{}", userId, groupId, message);
            if (isFollowUpMessage(message)) {
                    if (canReact(groupId)) {
                        recordReaction(groupId);
                        String cleanReply = normalizeForContext(thread.lastBotReply);
                        String prompt = "你之前说：“" + cleanReply + "”\n对方现在说：“" + message + "”\n请用一句自然的话回应。";
                        logger.debug(" candyBear: 触发追问，用户 {}，群 {}，消息：{}", userId, groupId, message);
                        return Optional.of(Reaction.withAI(prompt));
                }
            }
        }

        // === 以下才是真正的”主动插话”，受性格和概率控制 ===
        BehaviorAnalyzer.BehaviorAdvice advice = behaviorAnalyzer.getAdvice(groupId);
        double effectiveProbability = advice.adjustedProbability;
        logger.debug(" candyBear: 获取行为建议，用户 {}，群 {}，建议点数：{}", userId, groupId, effectiveProbability);
        if (0.15 > effectiveProbability) {
            logger.debug(" candyBear: 不满足概率要求，用户 {}，群 {}，概率：{}", userId, groupId, effectiveProbability);
            return Optional.empty();
        }

        Map<String, Object> personality = aiDatabaseService.getCandyBearPersonality();
        Map<String, Object> activeReply = (Map<String, Object>) personality.get("activeReply");
        double baseProbability = (double) activeReply.get("baseProbability");
        logger.debug(" candyBear: 获取性格参数，用户 {}，群 {}，参数：{}", userId, groupId, baseProbability);
        if (0.5 > baseProbability) {
            logger.debug(" candyBear: 不满足性格要求，用户 {}，群 {}，性格参数：{}", userId, groupId, baseProbability);
            return Optional.empty();
        }

        // 规则：话题兴趣匹配
        if (aiDatabaseService.shouldJoinTopic(message, groupId)) {
            logger.debug(" candyBear: 满足话题兴趣要求，用户 {}，群 {}，消息：{}", userId, groupId, message);
            if (canReact(groupId)) {
                logger.debug(" candyBear: 触发主动回复，用户 {}，群 {}，消息：{}", userId, groupId, message);
                recordReaction(groupId);
                aiDatabaseService.logActiveReplyDecision(groupId, userId, message, "reply", "topic_interest", "参与感兴趣话题");
                String prompt = "群友说：“" + message + "”\n作为糖果熊，请用一句简短文艺的话自然回应。";
                return Optional.of(Reaction.withAI(prompt));
            }
            logger.debug(" candyBear: 不满足主动回复条件，用户 {}，群 {}，消息：{}", userId, groupId, message);
        }
        logger.debug(" candyBear: 不满足话题兴趣要求，用户 {}，群 {}，消息：{}", userId, groupId, message);

        // 规则：评论 AI 历史发言
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events != null && !events.isEmpty()) {
            Optional<ContextEvent> lastAi = events.stream()
                    .filter(e -> "ai_reply".equals(e.type))
                    .reduce((first, second) -> second);

            if (lastAi.isPresent() && now - lastAi.get().timestamp < 180_000) {
                if (isResponseToAIMessage(message, lastAi.get().content)) {
                    if (canReact(groupId)) {
                        recordReaction(groupId);
                        String cleanReply = normalizeForContext(lastAi.get().content);
                        String prompt = "你之前说：“" + cleanReply + "”\n另一个群友评论：“" + message + "”\n请友好地回应。";
                        return Optional.of(Reaction.withAI(prompt));
                    }
                }
            }
        }

        // 被动触发（红包、音乐等）
        Optional<String> passive = checkPassiveReactions(groupId, message);
        if (passive.isPresent() && canReact(groupId)) {
            recordReaction(groupId);
            return Optional.of(Reaction.direct(passive.get()));
        }

//

        return Optional.empty();
    }

    // ===== 记录方法 =====

    public void recordUserInteraction(String groupId, String userId, String fullBotReply) {
        String key = groupId + "_" + userId;
        userThreads.put(key, new UserThread(System.currentTimeMillis(), fullBotReply));
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

    // ===== 辅助判断 =====

    private boolean isFollowUpMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return false;
        }

        String text = msg.trim();
        int len = text.length();

        if (len > 60) {
            return false;
        }

        String lower = text.toLowerCase();

        // 1. 明确疑问句
        if (text.contains("？") || text.contains("?")) {
            return true;
        }

        // 2. 常见疑问/追问关键词
        String[] questionKeywords = {
                "为什么", "怎么会", "怎么", "为何", "咋", "啥", "什么", "谁",
                "呢", "吗", "嘛", "么", "吧", "是不是", "对不对", "行不行",
                "然后", "接着", "再", "继续", "后来", "下一步",
                "你觉得", "你认为", "你说", "你刚", "你之前", "你刚刚",
                "我能不能", "我可以", "能不能", "可不可以","给我"
        };

        for (String kw : questionKeywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }

        // 3. 以代词开头的短交互句
        if ((text.startsWith("你") || text.startsWith("我") || text.startsWith("我们")) && len <= 20) {
            if (lower.contains("觉得") || lower.contains("认为") ||
                    lower.contains("喜欢") || lower.contains("知道") ||
                    lower.contains("记得") || lower.contains("想") ||
                    lower.contains("在") || lower.contains("是") ||
                    lower.endsWith("呢") || lower.endsWith("啊") || lower.endsWith("呀")) {
                return true;
            }
        }

        // 4. 简短情绪/确认性语气词
        if (text.matches("(?i)^(嗯+|哦+|啊+|呃+|额+|诶+|好+|行+|对+|哈哈+|嘻嘻+|嘿嘿+|呜+|唉+)[~～!！?？]*$")) {
            return true;
        }

        // 5. 特殊模式：反问或省略主语的追问
        if ((lower.startsWith("那") || lower.startsWith("所以") || lower.startsWith("不过")) && len <= 25) {
            return true;
        }

        // 6. 极简追问：单字/双字疑问
        if (len <= 2 && (text.equals("呢") || text.equals("啊") || text.equals("哦") || text.equals("？"))) {
            return true;
        }
        if (lower.contains("你") && (
                lower.contains("擅长") ||
                        lower.contains("会") ||
                        lower.contains("能") ||
                        lower.contains("喜欢") ||
                        lower.contains("性格") ||
                        lower.contains("是什么") ||
                        lower.contains("介绍一下") ||
                        lower.contains("说说")
        )) {
            return true;
        }

        return false;
    }

    private boolean hasRecentBotActivity(String groupId) {
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events == null) return false;
        long now = System.currentTimeMillis();
        return events.stream().anyMatch(e -> now - e.timestamp < 120_000);
    }

    // ✅ 修复：移除宽松兜底条件，仅保留明确意图
    private boolean isResponseToAIMessage(String userMsg, String aiMsg) {
        if (userMsg.length() > 50) return false;
        String lower = userMsg.toLowerCase();
        return lower.contains("不对") || lower.contains("错") ||
                lower.contains("为什么") || lower.contains("怎么") ||
                lower.contains("接着") || lower.contains("继续") ||
                lower.contains("同意") || lower.contains("觉得") ||
                lower.contains("你说") || lower.contains("刚刚") ||
                lower.contains("回应") || lower.contains("回复") ||
                (lower.contains("你") && userMsg.length() <= 20);
    }

    private Optional<String> checkPassiveReactions(String groupId, String message) {
        String lower = message.toLowerCase();
        if (message.contains("[CQ:redbag")) {

            return Optional.of("诶？有红包？手慢无啊...");
        }
        if (message.contains("[CQ:music") || lower.contains("网易云") || lower.contains("music.163")) {
            return Optional.of("这首歌我也听过，挺不错的～");
        }
//        if (message.contains("糖果熊") && !message.contains("[CQ:at,qq=" + BOT_QQ + "]")) {
//            return Optional.of("我在呢，只是在发呆～");
//        }

        // 冷场检测
        Deque<ContextEvent> recent = groupContexts.get(groupId);
        if (recent != null && recent.size() >= 3) {
            List<ContextEvent> list = new ArrayList<>(recent);
            boolean allShort = list.stream().skip(list.size() - 3)
                    .allMatch(e -> e.content.length() < 8);
            if (allShort && !message.contains("@")) {
                if (ThreadLocalRandom.current().nextInt(100) < 3) {
                    return Optional.of("你们聊啥呢？突然安静了...");
                }
            }
        }

        return Optional.empty();
    }

    // 将上限从 20 改为 2（更合理）
    private boolean canReact(String groupId) {
        List<Long> history = groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
        history.removeIf(ts -> System.currentTimeMillis() - ts > 300_000); // 5分钟窗口
        return history.size() < 10; // 每5分钟最多2次主动插话
    }

    private void recordReaction(String groupId) {
        groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }

    private List<String> extractTopics(String text) {
        List<String> topics = new ArrayList<>();
        String lower = text.toLowerCase();

        if (lower.contains("诗") || lower.contains("文学") || lower.contains("小说") || lower.contains("书")) {
            topics.add("literature");
        }
        if (lower.contains("音乐") || lower.contains("歌") || lower.contains("曲") || lower.contains("网易云")) {
            topics.add("music");
        }
        if (lower.contains("艺术") || lower.contains("画") || lower.contains("展览")) {
            topics.add("art");
        }
        if (lower.contains("电影") || lower.contains("剧") || lower.contains("影视")) {
            topics.add("film");
        }
        if (lower.contains("哲学") || lower.contains("思考") || lower.contains("人生")) {
            topics.add("philosophy");
        }

        return topics.isEmpty() ? Arrays.asList("general") : topics;
    }
    // ===== 生成追问/评论回复 =====

//    private String generateFollowUp(String groupId, String userId, String lastReply, String currentMsg) {
//        String prompt = "你之前说：“" + lastReply + "”\n对方现在说：“" + currentMsg + "”\n请用一句自然的话回应。";
//        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
//    }
//
//    private String generateResponseToComment(String groupId, String userId, String comment, String aiMsg) {
//        String prompt = "你之前说：“" + aiMsg + "”\n另一个群友评论：“" + comment + "”\n请友好地回应。";
//        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
//    }

    // ===== 群消息记录 =====
    public void addGroupMessage(String groupId, String message) {
        recordGroupContext(groupId, "unknown", "someone", message, "user_message");
    }
    public static class Reaction {
        public final String text;      // 直接回复的文本
        public final boolean needsAI;  // 是否需要调用 generate
        public final String prompt;    // 如果 needsAI=true，这是 prompt

        private Reaction(String text, boolean needsAI, String prompt) {
            this.text = text;
            this.needsAI = needsAI;
            this.prompt = prompt;
        }

        public static Reaction direct(String text) {
            return new Reaction(text, false, null);
        }

        public static Reaction withAI(String prompt) {
            return new Reaction(null, true, prompt);
        }
    }
    // BaiLianService.java

    // 新增：存储每个群最近 N 条完整发言（含发言人）
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

        // 保留最近 8 条（可配置）
        if (history.size() >= 8) {
            history.pollFirst();
        }

        history.offerLast(new PublicMessage(userId, nickname, message));
    }
    public Deque<PublicMessage> getPublicGroupHistory(String groupId) {
        return publicGroupHistory.get(groupId);
    }
}