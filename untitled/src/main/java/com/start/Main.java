package com.start;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.handler.CPTracker;
import com.start.handler.HandlerRegistry;
import com.start.repository.GroupMessageStatsRepository;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.RecurringTaskRepository;
import com.start.util.MessageUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.start.repository.UserAffinityRepository;
import com.start.service.*;
import com.start.runtime.trace.WebDashboardListener;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
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

    /** 重连调度器，复用单线程避免每次断连泄漏线程池。 */
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Reconnect-Scheduler");
        t.setDaemon(true);
        return t;
    });

    // ===== 白名单配置 =====
    // 注意：白名单检查改用 WhitelistService（运行时可改），不再用 static final 缓存。
    // 开关仍由 BotConfig.isPrivateWhitelistEnabled() 提供（只读启动快照）。

    // ===== 核心服务实例（依赖注入） =====

    /** 用户相关操作服务（如查询、更新用户状态等）。 */
    UserService userService;

    /** 消息持久化与查询服务。 */
    MessageService messageService;


    /** AI 知识库与向量检索服务。 */
    AIDatabaseService aiDatabaseService;

    /** 百炼大模型调用服务（阿里云 DashScope）。 */
    BaiLianService baiLianService;

    /** TTS 语音合成服务。 */
    TtsService ttsService;

    /** 用户亲密度存储仓库，用于个性化推荐与互动。 */
    final UserAffinityRepository userAffinityRepo = new UserAffinityRepository(DatabaseConfig.getDataSource());

    /** 长期记忆存储仓库，用于定时事件触发。 */
    final LongTermMemoryRepository longTermMemoryRepo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());

    /** 周期任务存储仓库，用于工具联动（定时取出 prompt 发给 LLM 自由执行）。 */
    final RecurringTaskRepository recurringTaskRepo = new RecurringTaskRepository(DatabaseConfig.getDataSource());

    /** 关键词知识库服务，支持基于关键词的快速问答匹配。 */
    KeywordKnowledgeService keywordKnowledgeService;

    // ===== 事件处理器与辅助组件 =====

    /** 事件处理器注册中心，用于动态绑定不同消息类型的处理逻辑。 */
    HandlerRegistry handlerRegistry;

    /** 统一调度对话和后台生成任务，避免绕过群级串行约束。 */
    GroupSerialExecutor conversationExecutor;

    /** 运行时事件总线，AIHandler 触发事件，Listener 消费。 */
    com.start.runtime.ConversationRuntime conversationRuntime;

    /** 防刷检测器，防止高频消息攻击或滥用。 */
    SpamDetector spamDetector;

    /** 证据优先画像与媒体解析任务 worker。 */
    EvidenceAnalysisWorker evidenceAnalysisWorker;

    /** 原始证据与派生产物的容量感知保留清理。 */
    EvidenceRetentionService evidenceRetentionService;

    /** 糖果熊分群情绪系统，持久化到 group_mood 表。 */
    BotMoodService moodService;

    /** 封装 OneBot WebSocket API 调用的服务，支持异步请求。 */
    OneBotWsService oneBotWsService;

    /** 自动异常监控服务 —— 定时扫描日志 ERROR，触发 LLM 自审自修。 */
    ErrorMonitorService errorMonitorService;

    /** 由 BotBootstrap 启动的 Dashboard，用于统一释放 HTTP 资源。 */
    WebDashboardListener dashboardListener;

    // ===== 异步请求管理 =====

    /**
     * 存储待处理的 OneBot API 请求，通过 echo 字段关联请求与响应。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;


    // ===== 构造函数：初始化核心服务 =====

    /**
     * 构造 Main 实例并初始化所有依赖服务。
     *
     * @param serverUri WebSocket 服务器 URI
     */
    public Main(URI serverUri) {
        super(serverUri);
        DatabaseConfig.initConnectionPool();
        BotBootstrap.wireServices(this);
    }

    // ===== 初始化方法：启动后台任务与绑定服务 =====

    /**
     * 初始化防刷、画像、代理等高级功能，并启动定时任务。
     */
    public void init() {
        BotBootstrap.startBackgroundTasks(this);
    }

    // ===== 健康状态（5.2 健康指标） =====
    private final BotHealth health = new BotHealth();
    public BotHealth getHealth() { return health; }

    /**
     * 全局健康状态：WebSocket 连接、最近重连时间、pending 请求数。
     * 写时由 Main.onOpen/onClose/attemptReconnect 维护，读时由 Dashboard 通过
     * Supplier<BotHealth> 拉取快照。
     *
     * 注意：作为 Main 的 non-static inner class，可以直接访问外层 pendingRequests。
     */
    public final class BotHealth {
        private volatile boolean webSocketConnected = false;
        private volatile long lastReconnectAt = 0;
        private volatile long lastDisconnectAt = 0;

        public void setWebSocketConnected(boolean v) { this.webSocketConnected = v; }
        public boolean isWebSocketConnected() { return webSocketConnected; }
        public long getLastReconnectAt() { return lastReconnectAt; }
        public long getLastDisconnectAt() { return lastDisconnectAt; }
        public int getPendingRequestCount() { return pendingRequests.size(); }
    }

    // ===== WebSocket 生命周期回调 =====

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("✅ 已连接 NapCat WebSocket");
        health.setWebSocketConnected(true);
        health.lastReconnectAt = System.currentTimeMillis();
        // 异步拉取预加载群的成员昵称写入数据库
        new Thread(() -> seedGroupNicknames()).start();
    }

    private void seedGroupNicknames() {
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; } // 等连接稳定
        for (Long groupId : BotConfig.getAllowedGroups()) {
            String gid = String.valueOf(groupId);
            try {
                var params = MAPPER.createObjectNode();
                params.put("group_id", Long.parseLong(gid));
                var future = callOneBotApi("get_group_member_list", params);
                var resp = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (resp != null && resp.has("data")) {
                    int count = 0;
                    for (JsonNode m : resp.path("data")) {
                        String uid = String.valueOf(m.path("user_id").asLong());
                        String card = m.path("card").asText();
                        String nick = m.path("nickname").asText();
                        String name = !card.isEmpty() ? card : nick;
                        if (!name.isEmpty() && !"未知用户".equals(name) && uid.length() > 4) {
                            this.userService.getOrCreateUser(uid, name);
                            count++;
                        }
                    }
                    logger.info("📋 群 {} 昵称已写入: {} 人", gid, count);
                }
            } catch (Exception e) { logger.warn("群 {} 昵称拉取失败: {}", gid, e.getMessage()); }
        }
    }

    @Override
    public void onMessage(String message) {
        logger.debug("📡 收到 OneBot 事件，payload={} chars", message != null ? message.length() : 0);

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

            if ("notice".equals(event.path("post_type").asText())) {
                handleNoticeEvent(event);
                return;
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

            // 判断是否在白名单内（走 WhitelistService，热生效）
            if ("group".equals(messageType)) {
                long groupId = event.path("group_id").asLong();
                if (WhitelistService.getInstance().isAllowedGroup(groupId)) {
                    isAllowed = true;
                } else {
                    logger.debug("🚫 忽略非白名单群消息 | group_id={}", groupId);
                }
            } else if ("private".equals(messageType)) {
                if (!BotConfig.isPrivateWhitelistEnabled()) {
                    isAllowed = true;
                    logger.debug("💬 接受私聊（白名单未启用）| user_id={}", userId);
                } else {
                    if (WhitelistService.getInstance().isAllowedPrivateUser(userId)) {
                        isAllowed = true;
                        logger.debug("💬 接受白名单私聊 | user_id={}", userId);
                    } else {
                        logger.debug("🚫 忽略非白名单私聊 | user_id={}", userId);
                    }
                }
            }

            if (isAllowed) {
                // 记录群消息统计+昵称（每条都计）
                if ("group".equals(messageType)) {
                    String gid = String.valueOf(event.path("group_id").asLong());
                    String uid = String.valueOf(userId);
                    GroupMessageStatsRepository.recordMessage(gid, uid);
                    // 更新用户昵称（从群名片/QQ昵称）
                    String card = event.path("sender").path("card").asText();
                    String nick = event.path("sender").path("nickname").asText();
                    String displayName = !card.isEmpty() ? card : nick;
                    if (!displayName.isEmpty() && !"未知用户".equals(displayName)) {
                        this.userService.getOrCreateUser(uid, displayName);
                    }
                    // 记录 @ 互动 → CP 追踪
                    List<Long> ats = com.start.util.MessageUtil.extractAts(event.path("message"));
                    for (Long atQq : ats) {
                        if (atQq != selfId) {
                            CPTracker.recordInteraction(gid, uid, String.valueOf(atQq));
                        }
                    }
                }
                String rawMessage = event.path("raw_message").asText();
                if ("private".equals(messageType)) {

                    // 👇 关键：通知提醒服务收到回复
                    ReminderService.getInstance().onPrivateMessageReceived(userId);

                    // ... 其他逻辑（如 dispatch）...
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

                // OneBot 入站事件是用户消息的唯一写入口：保留协议原文，同时存检索用的纯文本。
                try {
                    String saveSessionId;
                    String saveGroupId = null;
                    boolean isPrivate = "private".equals(messageType);
                    if (isPrivate) {
                        saveSessionId = "private_" + userId;
                    } else {
                        saveGroupId = String.valueOf(event.path("group_id").asLong());
                        saveSessionId = "group_" + saveGroupId + "_" + userId;
                    }
                    String plainMessage = MessageUtil.extractPlainText(event.path("message")).trim();
                    String sourceEventKey = MessageService.sourceEventKey(
                            messageType, saveGroupId, String.valueOf(userId), event.path("message_id").asText());
                    Long replyProviderMessageId = MessageUtil.extractReplyId(event.path("message"));
                    messageService.saveInboundUserMessage(saveSessionId, String.valueOf(userId), saveGroupId,
                            rawMessage, plainMessage, isPrivate, sourceEventKey,
                            event.path("message_id").asText(null), event.toString(), event.path("message"),
                            replyProviderMessageId != null ? String.valueOf(replyProviderMessageId) : null);
                } catch (Exception e) {
                    logger.warn("保存消息失败: {}", e.getMessage());
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
        health.setWebSocketConnected(false);
        health.lastDisconnectAt = System.currentTimeMillis();
        failPendingRequests(new IllegalStateException("OneBot WebSocket 已断开"));
        if (shuttingDown) {
            return;
        }
        reconnectScheduler.schedule(this::attemptReconnect, 5, TimeUnit.SECONDS);
    }

    /** 补充已入库 OneBot 入站消息的视觉描述，不再插入第二条用户消息。 */
    public void attachInboundImageData(String messageType, String groupId, String userId,
                                       String messageId, String imageData) {
        if (messageService == null) return;
        String key = MessageService.sourceEventKey(messageType, groupId, userId, messageId);
        messageService.attachImageData(key, imageData);
    }

    /**
     * 递归重连机制：失败后指数退避（此处简化为固定 10 秒）。
     */
    public void reconnect() {
        attemptReconnect();
    }

    private void attemptReconnect() {
        try {
            logger.info("🔄 尝试重连...");
            super.reconnect();
            logger.info("✅ 重连成功");
            health.setWebSocketConnected(true);
            health.lastReconnectAt = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("⚠️ 重连失败，10秒后再次尝试...", e);
            reconnectScheduler.schedule(this::attemptReconnect, 10, TimeUnit.SECONDS);
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
        return callOneBotApi(action, params, 10);
    }

    private CompletableFuture<JsonNode> callOneBotApi(String action, JsonNode params, long timeoutSeconds) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OneBot WebSocket 未连接，无法调用: " + action));
        }

        String echo = "req_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000000);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(echo, future);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("action", action);
        request.set("params", params);
        request.put("echo", echo);

        try {
            this.send(request.toString());
        } catch (RuntimeException e) {
            pendingRequests.remove(echo, future);
            future.completeExceptionally(e);
            return future;
        }
        logger.debug("📤 发送 OneBot API 请求: action={}, echo={}", action, echo);

        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((response, error) -> pendingRequests.remove(echo, future))
                .exceptionally(t -> {
                    logger.warn("⏰ OneBot API 调用失败或超时: action={}, echo={}, reason={}",
                            action, echo, t.toString());
                    return null;
                });
    }

    private void failPendingRequests(Throwable cause) {
        pendingRequests.forEach((echo, future) -> {
            if (pendingRequests.remove(echo, future)) {
                future.completeExceptionally(cause);
            }
        });
    }

    /**
     * 通过 OneBot API 发送消息并等待回执确认。
     * 成功条件：WebSocket 写入成功 + OneBot 回执 status=ok 且 retcode=0。
     * 失败场景：连接断开 / 回执超时 / 回执 retcode 非零。
     *
     * <p>同步阻塞最长 5 秒，正常 OneBot 真实回执延迟 100-500ms，
     * 5s 是兜底而非预期值。调用方应在 GroupSerialExecutor worker 中按群级串行执行。
     */
    private JsonNode sendWithReceipt(String action, ObjectNode params) {
        return sendWithReceipt(action, params, 5);
    }

    private JsonNode sendWithReceipt(String action, ObjectNode params, int timeoutSeconds) {
        try {
            CompletableFuture<JsonNode> future = callOneBotApi(action, params, Math.max(10, timeoutSeconds * 2L));
            JsonNode response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response == null) {
                logger.warn("⚠️ OneBot API {} 回执为空（超时或连接异常）", action);
                return null;
            }
            String status = response.path("status").asText();
            int retcode = response.path("retcode").asInt();
            if (!"ok".equals(status) || retcode != 0) {
                String msg = response.path("msg").asText();
                String wording = response.path("wording").asText();
                logger.warn("⚠️ OneBot API {} 回执失败: status={}, retcode={}, msg={}, wording={}",
                        action, status, retcode, msg, wording);
                return null;
            }
            return response;
        } catch (java.util.concurrent.TimeoutException e) {
            logger.warn("⏰ OneBot API {} 回执超时（{}s）", action, timeoutSeconds);
            return null;
        } catch (Exception e) {
            logger.error("❌ OneBot API {} 发送异常: {}", action, e.toString());
            return null;
        }
    }

    // ===== 消息发送便捷方法 =====

    /**
     * 根据原始消息类型（群/私聊）自动选择发送方式。
     *
     * @deprecated 请使用 {@link #sendReply(JsonNode, String, String)} 显式传入 sessionId。
     *             旧方法保留是为兼容：群场景下写入 group_*_bot sessionId（已有数据兼容）。
     *             AI 对话应走 sendReply(msg, reply, sessionId) 重载。
     */
    @Deprecated
    public boolean sendReply(JsonNode msg, String reply) {
        // 旧行为：群写 group_{g}_bot，私聊写 private_{u}（与之前一致，保留兼容）
        String legacySessionId;
        if ("group".equals(msg.path("message_type").asText())) {
            legacySessionId = com.start.service.SessionId.groupBotReply(
                    String.valueOf(msg.path("group_id").asLong()));
        } else {
            legacySessionId = com.start.service.SessionId.privateChat(
                    String.valueOf(msg.path("user_id").asLong()));
        }
        return sendReply(msg, reply, legacySessionId);
    }

    /**
     * 第二阶段 2.2：发回复并显式指定 sessionId 持久化。
     * AIHandler 在 replyWithAI 里拿到真实 sessionId 后调用此重载，
     * 用户消息和 AI 回复写入同一个 session，getConversationContext 能查到完整对话。
     */
    public boolean sendReply(JsonNode msg, String reply, String sessionId) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送回复，payload={} chars", traceId, reply != null ? reply.length() : 0);
        try {
            String msgType = msg.path("message_type").asText();

            ObjectNode params = MAPPER.createObjectNode();
            if ("group".equals(msgType)) {
                params.put("group_id", msg.path("group_id").asLong());
            } else {
                params.put("user_id", msg.path("user_id").asLong());
            }
            params.put("message", reply);

            JsonNode receipt = sendWithReceipt("send_" + msgType + "_msg", params);
            if (receipt == null) {
                return false;
            }
            // ✅ 仅在 OneBot 确认送达后才持久化
            try {
                if (this.messageService != null) {
                    boolean privateMessage = "private".equals(msgType);
                    String targetGroup = privateMessage ? null : String.valueOf(msg.path("group_id").asLong());
                    this.messageService.saveAIReply(sessionId, targetGroup, reply, null, privateMessage,
                            receiptMessageId(receipt), receipt.toString(), msg.path("message_id").asText(null));
                }
            } catch (Exception e) {
                logger.warn("回复已发送，但消息持久化失败: {}", e.getMessage());
            }
            logger.debug("📤 已发送回复，payload={} chars", reply != null ? reply.length() : 0);
            return true;
        } catch (Exception e) {
            logger.error("❌ 发送回复失败", e);
            return false;
        }
    }

    @Deprecated
    public boolean sendPrivateReply(long userId, String reply) {
        return sendPrivateReply(userId, reply, com.start.service.SessionId.privateChat(String.valueOf(userId)));
    }

    @Deprecated
    public boolean sendPrivateReply(long userId, long groupId, String reply) {
        return sendPrivateReply(userId, groupId, reply, com.start.service.SessionId.privateChat(String.valueOf(userId)));
    }

    /** 第二阶段 2.2：发私聊并显式指定 sessionId 持久化 */
    public boolean sendPrivateReply(long userId, String reply, String sessionId) {
        return sendPrivateReply(userId, 0, reply, sessionId);
    }

    /** 第二阶段 2.2：发私聊并显式指定 sessionId 持久化（带 groupId 用于建立临时会话） */
    public boolean sendPrivateReply(long userId, long groupId, String reply, String sessionId) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送私聊，payload={} chars", traceId, reply != null ? reply.length() : 0);
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("user_id", userId);
            if (groupId > 0) params.put("group_id", groupId);
            params.put("message", reply);
            JsonNode receipt = sendWithReceipt("send_private_msg", params,
                    isMediaPayload(reply) ? 30 : 5);
            if (receipt == null) {
                return false;
            }
            // ✅ 仅在 OneBot 确认送达后才持久化
            try {
                if (this.messageService != null) {
                    this.messageService.saveAIReply(sessionId, null, reply, null, true,
                            receiptMessageId(receipt), receipt.toString(), null);
                }
            } catch (Exception e) {
                logger.warn("私聊已发送，但消息持久化失败: {}", e.getMessage());
            }
            logger.debug("📤 已发送私聊，payload={} chars", reply != null ? reply.length() : 0);
            return true;
        } catch (Exception e) {
            logger.error("❌ 发送私聊失败", e);
            return false;
        }
    }

    @Deprecated
    public boolean sendGroupReply(long groupId, String reply) {
        return sendGroupReply(groupId, reply, com.start.service.SessionId.groupBotReply(String.valueOf(groupId)));
    }

    /** 第二阶段 2.2：发群聊并显式指定 sessionId 持久化 */
    public boolean sendGroupReply(long groupId, String reply, String sessionId) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送群聊回复，payload={} chars", traceId, reply != null ? reply.length() : 0);
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("group_id", groupId);
            params.put("message", reply);
            JsonNode receipt = sendWithReceipt("send_group_msg", params,
                    isMediaPayload(reply) ? 30 : 5);
            if (receipt == null) {
                return false;
            }
            // ✅ 仅在 OneBot 确认送达后才持久化
            try {
                if (this.messageService != null) {
                    this.messageService.saveAIReply(sessionId,
                            String.valueOf(groupId), reply, null, false,
                            receiptMessageId(receipt), receipt.toString(), null);
                }
            } catch (Exception e) {
                logger.warn("群消息已发送，但消息持久化失败: {}", e.getMessage());
            }
            logger.debug("📤 已发送群聊回复，payload={} chars", reply != null ? reply.length() : 0);
            try {
                if (this.baiLianService != null) {
                    String gid = String.valueOf(groupId);
                    this.baiLianService.recordGroupContext(
                            gid, "candybear", "糖果熊", reply, "bot_reply");
                    this.baiLianService.recordBotOwnGroupMessage(gid, reply);
                    this.baiLianService.getBotMemory().record(
                            String.valueOf(groupId), BotMemoryService.EntryType.SAID, null,
                            reply.length() > 100 ? reply.substring(0, 100) + "..." : reply);
                }
            } catch (Exception e) {
                logger.warn("群消息已发送，但运行时上下文记录失败: {}", e.getMessage());
            }
            return true;
        } catch (Exception e) {
            logger.error("❌ 发送群聊回复失败", e);
            return false;
        }
    }

    private static boolean isMediaPayload(String reply) {
        return reply != null && (reply.contains("[CQ:image") || reply.contains("[CQ:video"));
    }

    private void handleNoticeEvent(JsonNode event) {
        String noticeType = event.path("notice_type").asText();
        if (!"group_recall".equals(noticeType) && !"friend_recall".equals(noticeType)) return;
        String providerMessageId = event.path("message_id").asText("");
        if (providerMessageId.isBlank() || messageService == null) return;
        String groupId = event.hasNonNull("group_id") ? String.valueOf(event.path("group_id").asLong()) : null;
        String actorId = event.path("operator_id").asText(event.path("user_id").asText(""));
        int affected = messageService.handleRetraction(providerMessageId, groupId, actorId,
                MessageService.eventHash(event.toString()));
        logger.info("OneBot 撤回事件已处理: message_id={}, group_id={}, affected={}", providerMessageId, groupId, affected);
    }

    private static String receiptMessageId(JsonNode receipt) {
        if (receipt == null) return null;
        String value = receipt.path("data").path("message_id").asText("").trim();
        return value.isEmpty() ? null : value;
    }

    // ===== Getter 方法 =====

    public BaiLianService getBaiLianService() { return this.baiLianService; }

    public OneBotWsService getOneBotWsService() {
        return oneBotWsService;
    }

    // ===== 程序入口 =====

    /**
     * 主方法：创建机器人实例，连接 WebSocket 并初始化服务。
     */
    public static void main(String[] args) throws Exception {
        // 必须先检查配置，再初始化数据库/服务并连接 OneBot，避免缺配置时产生外部副作用。
        BotBootstrap.validateConfiguration();
        Main bot = new Main(new URI(BotConfig.getWsUrl()));
        Runtime.getRuntime().addShutdownHook(new Thread(bot::shutdown, "Bot-Shutdown"));
        bot.connect();
        bot.init();
        // 保持主线程运行
        while (!bot.isClosed()) {
            Thread.sleep(1000);
        }
    }

    /** 释放机器人持有的网络、调度和数据库资源。可重复调用。 */
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        reconnectScheduler.shutdownNow();

        if (evidenceAnalysisWorker != null) evidenceAnalysisWorker.close();
        if (evidenceRetentionService != null) evidenceRetentionService.close();
        failPendingRequests(new IllegalStateException("机器人正在关闭"));
        if (conversationExecutor != null) conversationExecutor.shutdown();
        if (dashboardListener != null) dashboardListener.stop();
        ToolAuditService.shutdown();
        try {
            if (!isClosed()) close();
        } catch (Exception e) {
            logger.debug("关闭 OneBot WebSocket 失败: {}", e.getMessage());
        }
        DatabaseConfig.close();
    }

    /** 计算到下一个凌晨 3:00 的毫秒数 */
    static long millisUntilNext3AM() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(3).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return java.time.Duration.between(now, next).toMillis();
    }

    /** 从 cron 表达式计算下次触发时间。支持 "mm HH * * *"（每天）和 "mm HH * * D"（每周D）格式。 */
    static LocalDateTime computeNextFireFromCron(String cronExpr) {
        if (cronExpr == null) return null;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliest = null;

        for (String cron : cronExpr.split(";")) {
            String[] fields = cron.trim().split("\\s+");
            if (fields.length < 5) continue;
            try {
                int minute = Integer.parseInt(fields[0]);
                int hour = Integer.parseInt(fields[1]);
                int dayOfWeek = Integer.parseInt(fields[4]);

                if (dayOfWeek == 0 || fields[4].equals("*")) {
                    // 每天
                    LocalDateTime candidate = LocalDateTime.of(LocalDate.now(),
                            LocalTime.of(hour, minute));
                    if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
                    if (earliest == null || candidate.isBefore(earliest)) earliest = candidate;
                } else {
                    // 每周特定日 (1=Mon, 7=Sun)
                    int todayDow = now.getDayOfWeek().getValue();
                    int daysUntil = (dayOfWeek - todayDow + 7) % 7;
                    LocalDateTime candidate = LocalDateTime.of(LocalDate.now().plusDays(daysUntil),
                            LocalTime.of(hour, minute));
                    if (!candidate.isAfter(now)) candidate = candidate.plusDays(7);
                    if (earliest == null || candidate.isBefore(earliest)) earliest = candidate;
                }
            } catch (NumberFormatException ignored) {}
        }
        return earliest;
    }

}
