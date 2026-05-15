package com.start;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.handler.HandlerRegistry;
import com.start.repository.MessageRepository;
import com.start.repository.UserAffinityRepository;
import com.start.service.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


/**
 * 主机器人入口类，负责 WebSocket 连接、事件分发、服务初始化及消息处理。
 * 该类继承自 WebSocket 客户端（假设为 org.java_websocket.client.WebSocketClient 子类），
 * 并实现了 OneBot 协议的事件监听与响应机制。
 */
public class Main extends WebSocketClient {

    // ===== 日志与工具 =====

    /** 日志记录器，用于输出调试、信息及错误日志。 */
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** JSON 序列化/反序列化工具，用于解析 OneBot 事件和构造 API 请求。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===== 白名单配置 =====

    /** 允许交互的群聊 ID 集合，由 BotConfig 提供。 */
    private static final Set<Long> ALLOWED_GROUPS = BotConfig.getAllowedGroups();

    /** 允许私聊的用户 ID 集合（若启用私聊白名单）。 */
    private static final Set<Long> ALLOWED_PRIVATE_USERS = BotConfig.getAllowedPrivateUsers();

    // ===== 核心服务实例（依赖注入） =====

    /** 用户相关操作服务（如查询、更新用户状态等）。 */
    private final UserService userService;

    /** 消息持久化与查询服务。 */
    private final MessageService messageService;

    /** 对话上下文管理服务，用于维护多轮对话状态。 */
    private ConversationService conversationService;

    /** 人格化回复生成服务，根据用户历史调整语气与风格。 */
    private PersonalityService personalityService;

    /** AI 知识库与向量检索服务。 */
    private final AIDatabaseService aiDatabaseService;

    /** 百炼大模型调用服务（阿里云 DashScope）。 */
    private final BaiLianService baiLianService;

    /** 用户亲密度存储仓库，用于个性化推荐与互动。 */
    private final UserAffinityRepository userAffinityRepo = new UserAffinityRepository();

    /** 关键词知识库服务，支持基于关键词的快速问答匹配。 */
    private final KeywordKnowledgeService keywordKnowledgeService;

    /** 智能代理服务，整合大模型、知识库与用户画像。 */
    private final AgentService agentService;

    // ===== 事件处理器与辅助组件 =====

    /** 事件处理器注册中心，用于动态绑定不同消息类型的处理逻辑。 */
    private HandlerRegistry handlerRegistry;

    /** 防刷检测器，防止高频消息攻击或滥用。 */
    private SpamDetector spamDetector;

    /** 用户画像服务，定期分析用户行为并更新画像标签。 */
    private UserPortraitService portraitService;

    /** 糖果熊情绪系统。 */
    private final BotMoodService moodService = new BotMoodService();

    /** 封装 OneBot WebSocket API 调用的服务，支持异步请求。 */
    private final OneBotWsService oneBotWsService;

    // ===== 异步请求管理 =====

    /**
     * 存储待处理的 OneBot API 请求，通过 echo 字段关联请求与响应。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();


    // ===== 构造函数：初始化核心服务 =====

    /**
     * 构造 Main 实例并初始化所有依赖服务。
     *
     * @param serverUri WebSocket 服务器 URI
     */
    public Main(URI serverUri) {
        super(serverUri);
        // 初始化数据库连接池
        DatabaseConfig.initConnectionPool();

        // 初始化 WebSocket API 封装服务（传入当前 Main 实例以支持发送请求）
        this.oneBotWsService = new OneBotWsService(this);

        // 初始化基础服务
        this.userService = new UserService();
        this.messageService = new MessageService();
        this.conversationService = new ConversationService();
        this.personalityService = new PersonalityService();
        this.aiDatabaseService = new AIDatabaseService();

        // 初始化知识库服务（需数据源）
        this.keywordKnowledgeService = new KeywordKnowledgeService(DatabaseConfig.getDataSource());

        // 初始化大模型服务
        this.baiLianService = new BaiLianService(this.keywordKnowledgeService, this.userAffinityRepo);
        this.baiLianService.setMoodService(this.moodService);
        this.baiLianService.setBotInstance(this);
        this.agentService = new AgentService(this.baiLianService, this.keywordKnowledgeService, this.userAffinityRepo);

        // 初始化事件处理器注册中心
        this.handlerRegistry = new HandlerRegistry(this.agentService, this.baiLianService);

        // 设置 DashScope API Key（来自配置文件，不使用环境变量）
        if (BotConfig.getBaiLianApiKey() != null && !BotConfig.getBaiLianApiKey().isBlank()) {
            System.setProperty("dashscope.api-key", BotConfig.getBaiLianApiKey());
        }
    }

    // ===== 初始化方法：启动后台任务与绑定服务 =====

    /**
     * 初始化防刷、画像、代理等高级功能，并启动定时任务。
     */
    public void init() {
        // 初始化防刷检测器
        this.spamDetector = new SpamDetector(this);
        logger.info("🛡️ SpamDetector 初始化完成");

        logger.info("🧠 BaiLianService 已绑定 KeywordKnowledgeService");

        logger.info("🤖 Agent 已启用");

        // 初始化用户画像服务
        this.portraitService = new UserPortraitService(this.baiLianService, new MessageRepository());

        // 立即执行一次画像更新（可选，加速首次响应）
        this.portraitService.runUpdateTask();
        logger.info("👤 用户画像首次更新完成");

        // 启动后台定时任务：每 10 分钟更新一次用户画像
        Thread timerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10 * 60 * 1000); // 10 分钟
                    this.portraitService.runUpdateTask();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("❌ 用户画像更新任务异常", e);
                }
            }
        }, "UserPortrait-Update-Thread");
        timerThread.setDaemon(true); // 设为守护线程，主程序退出时自动终止
        timerThread.start();

        logger.info("👤 用户画像系统已启动");

        // 初始化定时提醒服务
        ReminderService reminderService = ReminderService.getInstance();
        reminderService.setBotInstance(this); // 注入 Main 实例
        reminderService.setEnabled(true); // 默认开启，可通过命令控制
        logger.info("⏰ 私聊提醒服务已初始化");
    }

    // ===== WebSocket 生命周期回调 =====

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("✅ 已连接 NapCat WebSocket");
    }

    @Override
    public void onMessage(String message) {
        logger.debug("📡 原始事件: {}", message);

        try {
            JsonNode event = MAPPER.readTree(message);
            long userId1 = event.path("user_id").asLong();
            long selfId1 = event.path("self_id").asLong(); // OneBot 事件自带 self_id
            logger.debug("👤 user_id={}, self_id={}", userId1, selfId1);

            // ✅ 优先处理带 echo 的 API 响应（异步调用返回）
            if (event.has("echo")) {
                String echo = event.get("echo").asText();
                CompletableFuture<JsonNode> future = pendingRequests.remove(echo);
                if (future != null) {
                    future.complete(event);
                    return; // 不继续处理业务逻辑
                }
            }

            // 仅处理 message 类型事件
            if (!"message".equals(event.path("post_type").asText())) {
                return;
            }

            // ✅ 过滤掉机器人自己发送的消息
            long selfId = event.path("self_id").asLong();
            long userId = event.path("user_id").asLong();
            if (userId == selfId) {
                logger.debug("🚫 忽略机器人自己的消息 | user_id={}", userId);
                return;
            }

            String messageType = event.path("message_type").asText();
            boolean isAllowed = false;

            // 判断是否在白名单内
            if ("group".equals(messageType)) {
                long groupId = event.path("group_id").asLong();
                if (ALLOWED_GROUPS.contains(groupId)) {
                    isAllowed = true;
                } else {
                    logger.debug("🚫 忽略非白名单群消息 | group_id={}", groupId);
                }
            } else if ("private".equals(messageType)) {
                if (!BotConfig.isPrivateWhitelistEnabled()) {
                    isAllowed = true;
                    logger.debug("💬 接受私聊（白名单未启用）| user_id={}", userId);
                } else {
                    if (ALLOWED_PRIVATE_USERS.contains(userId)) {
                        isAllowed = true;
                        logger.debug("💬 接受白名单私聊 | user_id={}", userId);
                    } else {
                        logger.debug("🚫 忽略非白名单私聊 | user_id={}", userId);
                    }
                }
            }

            if (isAllowed) {
                String rawMessage = event.path("raw_message").asText();
                if ("private".equals(messageType)) {

                    // 👇 关键：通知提醒服务收到回复
                    ReminderService.getInstance().onPrivateMessageReceived(userId);

                    // ... 其他逻辑（如 dispatch）...
                }
                
                // ✅ 优先处理远行商人响应
                if (this.handlerRegistry.handleMerchantResponse(event, this)) {
                    logger.debug("✅ 已处理远行商人响应，跳过常规分发");
                    return;
                }
                
                // 执行防刷检测（仅群聊）
                if ("group".equals(messageType)) {
                    long groupId = event.path("group_id").asLong();
                    if (this.spamDetector != null) {
                        this.spamDetector.checkAndInterrupt(String.valueOf(groupId), userId, rawMessage);
                    } else {
                        logger.warn("⚠️ SpamDetector 未初始化，跳过防刷检测");
                    }
                }

                // 分发事件给注册的处理器
                this.handlerRegistry.dispatch(event, this);
            }

        } catch (Exception e) {
            logger.error("❌ 处理消息失败", e);
            try {
                String msgType = null;
                long groupId = 0;
                long userId = 0;
                try {
                    JsonNode event = MAPPER.readTree(message);
                    msgType = event.path("message_type").asText();
                    groupId = event.path("group_id").asLong();
                    userId = event.path("user_id").asLong();
                } catch (Exception ignored) {}
                String fallback = "出了点小问题，等下再试～";
                if ("group".equals(msgType) && groupId > 0) {
                    sendGroupReply(groupId, fallback);
                } else if ("private".equals(msgType) && userId > 0) {
                    sendPrivateReply(userId, fallback);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("❌ 连接断开 (code={}, remote={}), 5秒后重连...", code, remote);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.schedule(this::reconnect, 5, TimeUnit.SECONDS);
    }

    /**
     * 递归重连机制：失败后指数退避（此处简化为固定 10 秒）。
     */
    public void reconnect() {
        try {
            logger.info("🔄 尝试重连...");
            this.connect();
            logger.info("✅ 重连成功");
        } catch (Exception e) {
            logger.error("⚠️ 重连失败，10秒后再次尝试...", e);
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.schedule(this::reconnect, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("🔥 WebSocket 发生错误", ex);
    }

    // ===== OneBot API 调用封装 =====

    /**
     * 通过 WebSocket 异步调用 OneBot API。
     *
     * @param action API 动作名（如 send_group_msg）
     * @param params 参数对象
     * @return 返回一个 CompletableFuture，可在后续处理响应
     */
    public CompletableFuture<JsonNode> callOneBotApi(String action, JsonNode params) {
        String echo = "req_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000000);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(echo, future);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("action", action);
        request.set("params", params);
        request.put("echo", echo);

        this.send(request.toString());
        logger.debug("📤 发送 OneBot API 请求: action={}, echo={}", action, echo);

        return future.orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(t -> {
                    logger.warn("⏰ OneBot API 调用失败或超时: action={}, echo={}", action, echo, t);
                    return null;
                });
    }

    // ===== 消息发送便捷方法 =====

    /**
     * 根据原始消息类型（群/私聊）自动选择发送方式。
     */
    public void sendReply(JsonNode msg, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送回复: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            String msgType = msg.path("message_type").asText();
            action.put("action", "send_" + msgType + "_msg");

            ObjectNode params = action.putObject("params");
            if ("group".equals(msgType)) {
                params.put("group_id", msg.path("group_id").asLong());
            } else {
                params.put("user_id", msg.path("user_id").asLong());
            }
            params.put("message", reply);

            this.send(action.toString());
            logger.debug("📤 已发送回复: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送回复失败", e);
        }
    }

    public void sendPrivateReply(long userId, String reply) {
        sendPrivateReply(userId, 0, reply);
    }

    /** 带 group_id 的私聊，非好友需要 group_id 建立临时会话 */
    public void sendPrivateReply(long userId, long groupId, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送私聊: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            action.put("action", "send_private_msg");
            ObjectNode params = action.putObject("params");
            params.put("user_id", userId);
            if (groupId > 0) params.put("group_id", groupId);
            params.put("message", reply);
            this.send(action.toString());
            logger.debug("📤 已发送私聊: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送私聊失败", e);
        }
    }

    public void sendGroupReply(long groupId, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送群聊回复: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            action.put("action", "send_group_msg");
            ObjectNode params = action.putObject("params");
            params.put("group_id", groupId);
            params.put("message", reply);
            this.send(action.toString());
            logger.debug("📤 已发送群聊回复: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送群聊回复失败", e);
        }
    }

    // ===== Getter 方法 =====

    public OneBotWsService getOneBotWsService() {
        return oneBotWsService;
    }

    // ===== 程序入口 =====

    /**
     * 主方法：创建机器人实例，连接 WebSocket 并初始化服务。
     */
    public static void main(String[] args) throws Exception {
        Main bot = new Main(new URI(BotConfig.getWsUrl()));
        bot.connect();
        bot.init();
        // 保持主线程运行
        while (!bot.isClosed()) {
            Thread.sleep(1000);
        }
    }

}