package com.start.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.ManualMessageReplayService;
import com.start.service.StickerIngestService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web 可观测性面板，提供实时决策链路、群聊指标和系统健康。
 * 内嵌 HTTP 服务器，不依赖外部容器。不改 Runtime 一行代码。
 */
public class WebDashboardListener implements RuntimeListener {

    private static final Logger logger = LoggerFactory.getLogger(WebDashboardListener.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int DEFAULT_PORT = 8765;
    private static final int DEFAULT_REPLAY_PORT = 8766;
    private static final int MAX_DECISIONS = 300;
    private static final String REPLAY_SCOPE =
            "primary model and rulebook with replay transcript; no auto-trigger, live profile/knowledge lookup, or tools";

    // —— 内部数据记录 ——
    static final class DecisionEntry {
        final long timestamp;
        final String groupId;
        final String userId;
        final String eventType;
        final String decision;   // REPLY / SILENT / ERROR
        final String reason;
        final int toolCalls;
        final int tokensUsed;
        final long latencyMs;
        final long generation;
        final long revision;
        final List<String> inputMessageIds;
        final String replyToMessageId;
        final List<String> evidenceMessageIds;

        DecisionEntry(long timestamp, String groupId, String userId, String eventType,
                      String decision, String reason, int toolCalls, int tokensUsed,
                      long latencyMs, long generation, long revision,
                      List<String> inputMessageIds, String replyToMessageId,
                      List<String> evidenceMessageIds) {
            this.timestamp = timestamp;
            this.groupId = groupId;
            this.userId = userId;
            this.eventType = eventType;
            this.decision = decision;
            this.reason = reason;
            this.toolCalls = toolCalls;
            this.tokensUsed = tokensUsed;
            this.latencyMs = latencyMs;
            this.generation = generation;
            this.revision = revision;
            this.inputMessageIds = inputMessageIds == null ? List.of() : List.copyOf(inputMessageIds);
            this.replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
            this.evidenceMessageIds = evidenceMessageIds == null ? List.of() : List.copyOf(evidenceMessageIds);
        }
    }

    static final class GroupSummary {
        final String groupId;
        final AtomicLong messages = new AtomicLong();
        final AtomicLong replies = new AtomicLong();
        final AtomicLong silent = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong totalTokens = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
        volatile long lastActive;

        GroupSummary(String groupId) { this.groupId = groupId; }
    }

    private static volatile WebDashboardListener instance;

    private final ConcurrentLinkedDeque<DecisionEntry> decisions = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GroupSummary> groups = new ConcurrentHashMap<>();
    private final AtomicLong totalMessages = new AtomicLong();
    private final AtomicLong totalReplies = new AtomicLong();
    private final AtomicLong totalSilent = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final long startTime = System.currentTimeMillis();

    private HttpServer server;
    private final int port;
    private final String host;
    private final String token;   // null = 不需要鉴权
    private final boolean replayOnly;
    private volatile java.util.function.Supplier<com.start.service.GroupSerialExecutor.ExecutorMetrics> executorMetricsProvider;
    private volatile java.util.function.Supplier<com.start.Main.BotHealth> healthProvider;
    private volatile ManualMessageReplayService replayService;

    public WebDashboardListener() {
        this(false);
    }

    public WebDashboardListener(boolean replayOnly) {
        this.replayOnly = replayOnly;
        String hostProperty = replayOnly ? "dashboard.replay.host" : "dashboard.host";
        String hostEnv = replayOnly ? "DASHBOARD_REPLAY_HOST" : "DASHBOARD_HOST";
        String portProperty = replayOnly ? "dashboard.replay.port" : "dashboard.port";
        String portEnv = replayOnly ? "DASHBOARD_REPLAY_PORT" : "DASHBOARD_PORT";
        String tokenProperty = replayOnly ? "dashboard.replay.token" : "dashboard.token";
        String tokenEnv = replayOnly ? "DASHBOARD_REPLAY_TOKEN" : "DASHBOARD_TOKEN";
        this.host = System.getProperty(hostProperty,
                System.getenv().getOrDefault(hostEnv, "127.0.0.1"));
        int defaultPort = replayOnly ? DEFAULT_REPLAY_PORT : DEFAULT_PORT;
        this.port = Integer.parseInt(System.getProperty(portProperty,
                System.getenv().getOrDefault(portEnv, String.valueOf(defaultPort))));
        String t = System.getProperty(tokenProperty, System.getenv(tokenEnv));
        this.token = (t != null && !t.isBlank()) ? t : null;
    }

    // ===== 公开 API =====

    /** 启动内嵌 HTTP 服务器（守护线程，不阻止 JVM 退出）。 */
    public void start() {
        if (server != null) return;
        if (token == null && !isLoopbackHost(host)) {
            logger.error("WebDashboard 拒绝启动：非本机监听必须配置 DASHBOARD_TOKEN");
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "dashboard-http");
                t.setDaemon(true);
                return t;
            }));
            if (!replayOnly) {
                server.createContext("/", this::serveHtml);
                server.createContext("/api/decisions", this::serveDecisions);
                server.createContext("/api/groups", this::serveGroups);
                server.createContext("/api/system", this::serveSystem);
                server.createContext("/api/stickers", this::serveStickers);
            }
            server.createContext("/api/replay", this::serveReplay);
            server.start();
            instance = this;
            logger.info("WebDashboard 已启动: http://{}:{}{}", host, port,
                    token != null ? " (Token 鉴权已启用)" : "");
        } catch (IOException e) {
            logger.error("WebDashboard 启动失败", e);
        }
    }

    /** 停止 HTTP 服务器。 */
    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            instance = null;
        }
        ManualMessageReplayService replay = replayService;
        if (replay != null) replay.close();
    }

    /** 获取当前实例，供 EventBus 注册。 */
    public static WebDashboardListener getInstance() { return instance; }

    /**
     * 注入 GroupSerialExecutor 指标提供者。BotBootstrap 在 start() 前调用，
     * 让 /api/system 端点能拿到线程池实时指标。
     */
    public void setExecutorMetricsProvider(
            java.util.function.Supplier<com.start.service.GroupSerialExecutor.ExecutorMetrics> provider) {
        this.executorMetricsProvider = provider;
    }

    /**
     * 注入 BotHealth 状态提供者。/api/system 会暴露 WebSocket 连接、
     * pending 请求数、最近重连/断开时间、迁移结果、调度结果等。
     */
    public void setHealthProvider(
            java.util.function.Supplier<com.start.Main.BotHealth> provider) {
        this.healthProvider = provider;
    }

    public void setReplayService(ManualMessageReplayService replayService) {
        this.replayService = replayService;
    }

    // ===== RuntimeListener 实现 =====

    @Override
    public void onEvent(RuntimeEvent e) {
        if (e instanceof RuntimeEvent.MessageReceived m) {
            recordMessage(m.groupId(), m.userId());
        }
    }

    /** 静态记录决策（供 AIHandler 等非 Runtime 路径直接调用）。 */
    public static void recordDecision(String groupId, String userId, String eventType,
                                       String decision, String reason, int toolCalls,
                                       int tokensUsed, long latencyMs) {
        recordDecision(groupId, userId, eventType, decision, reason, toolCalls, tokensUsed,
                latencyMs, 0, 0, List.of(), "", List.of());
    }

    public static void recordDecision(String groupId, String userId, String eventType,
                                      String decision, String reason, int toolCalls,
                                      int tokensUsed, long latencyMs,
                                      List<String> inputMessageIds, String replyToMessageId,
                                      List<String> evidenceMessageIds) {
        recordDecision(groupId, userId, eventType, decision, reason, toolCalls, tokensUsed,
                latencyMs, 0, 0, inputMessageIds, replyToMessageId, evidenceMessageIds);
    }

    public static void recordDecision(String groupId, String userId, String eventType,
                                      String decision, String reason, int toolCalls,
                                      int tokensUsed, long latencyMs, long generation, long revision,
                                      List<String> inputMessageIds, String replyToMessageId,
                                      List<String> evidenceMessageIds) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.addDecision(new DecisionEntry(System.currentTimeMillis(), groupId, userId,
                eventType, decision, reason, toolCalls, tokensUsed, latencyMs, generation, revision,
                inputMessageIds, replyToMessageId, evidenceMessageIds));
        GroupSummary gs = inst.groups.computeIfAbsent(groupId, k -> new GroupSummary(k));
        switch (decision) {
            case "REPLY" -> { inst.totalReplies.incrementAndGet(); gs.replies.incrementAndGet(); }
            case "SILENT" -> { inst.totalSilent.incrementAndGet(); gs.silent.incrementAndGet(); }
            case "ERROR"  -> { inst.totalErrors.incrementAndGet(); gs.errors.incrementAndGet(); }
        }
        if (tokensUsed > 0) gs.totalTokens.addAndGet(tokensUsed);
        if (latencyMs > 0) gs.totalLatencyMs.addAndGet(latencyMs);
    }

    /** 静态记录消息（供 AIHandler 调用）。 */
    public static void recordMessage(String groupId, String userId) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.totalMessages.incrementAndGet();
        GroupSummary gs = inst.groups.computeIfAbsent(groupId, k -> new GroupSummary(k));
        gs.messages.incrementAndGet();
        gs.lastActive = System.currentTimeMillis();
    }

    /** 静态记录工具调用次数。 */
    public static void recordToolCall(String toolName) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
    }

    // ===== 内部方法 =====

    private void addDecision(DecisionEntry e) {
        decisions.addLast(e);
        while (decisions.size() > MAX_DECISIONS) {
            decisions.pollFirst();
        }
    }

    // ===== HTTP 处理器 =====

    /** 鉴权检查。未配置 token 时直接放行。 */
    private boolean checkAuth(HttpExchange ex) throws IOException {
        if (token == null) return true;
        String supplied = ex.getRequestHeaders().getFirst("X-Dashboard-Token");
        if (supplied == null) supplied = parseQuery(ex, "token");
        if (token.equals(supplied)) return true;
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(401, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return false;
    }

    private static boolean isLoopbackHost(String value) {
        return "localhost".equalsIgnoreCase(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "[::1]".equals(value);
    }

    private void serveHtml(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        byte[] bytes = HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void serveDecisions(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        int limit = parseQueryInt(ex, "limit", 100);
        ArrayNode arr = mapper.createArrayNode();
        List<DecisionEntry> snapshot = new ArrayList<>(decisions);
        int skip = Math.max(0, snapshot.size() - limit);
        int idx = 0;
        for (DecisionEntry d : snapshot) {
            if (idx++ < skip) continue;
            ObjectNode o = mapper.createObjectNode();
            o.put("time", Instant.ofEpochMilli(d.timestamp)
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            o.put("groupId", d.groupId);
            o.put("userId", d.userId != null ? d.userId : "");
            o.put("event", d.eventType);
            o.put("decision", d.decision);
            o.put("reason", d.reason);
            o.put("toolCalls", d.toolCalls);
            o.put("tokensUsed", d.tokensUsed);
            o.put("latencyMs", d.latencyMs);
            o.put("generation", d.generation);
            o.put("revision", d.revision);
            ArrayNode inputIds = o.putArray("inputMessageIds");
            d.inputMessageIds.forEach(inputIds::add);
            o.put("replyToMessageId", d.replyToMessageId);
            ArrayNode evidenceIds = o.putArray("evidenceMessageIds");
            d.evidenceMessageIds.forEach(evidenceIds::add);
            arr.add(o);
        }
        sendJson(ex, arr.toString());
    }

    private void serveGroups(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        ArrayNode arr = mapper.createArrayNode();
        List<GroupSummary> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparingLong(g -> -g.lastActive));
        for (GroupSummary g : sorted) {
            ObjectNode o = mapper.createObjectNode();
            o.put("groupId", g.groupId);
            o.put("messages", g.messages.get());
            o.put("replies", g.replies.get());
            o.put("silent", g.silent.get());
            o.put("errors", g.errors.get());
            o.put("totalTokens", g.totalTokens.get());
            o.put("avgLatencyMs", g.replies.get() > 0
                    ? g.totalLatencyMs.get() / g.replies.get() : 0);
            o.put("lastActive", g.lastActive > 0
                    ? Instant.ofEpochMilli(g.lastActive)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    : "-");
            arr.add(o);
        }
        sendJson(ex, arr.toString());
    }

    private void serveSystem(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        Runtime rt = Runtime.getRuntime();
        long uptimeMs = System.currentTimeMillis() - startTime;
        ObjectNode o = mapper.createObjectNode();
        o.put("uptime", formatDuration(uptimeMs));
        o.put("uptimeMs", uptimeMs);
        o.put("totalMessages", totalMessages.get());
        o.put("totalReplies", totalReplies.get());
        o.put("totalSilent", totalSilent.get());
        o.put("totalErrors", totalErrors.get());
        o.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        o.put("heapMaxMB", rt.maxMemory() / 1024 / 1024);
        o.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        o.put("activeGroups", groups.size());

        // 健康状态指标（5.2）
        java.util.function.Supplier<com.start.Main.BotHealth> hp = healthProvider;
        if (hp != null) {
            try {
                com.start.Main.BotHealth h = hp.get();
                ObjectNode healthNode = mapper.createObjectNode();
                healthNode.put("webSocketConnected", h.isWebSocketConnected());
                healthNode.put("pendingRequests", h.getPendingRequestCount());
                healthNode.put("lastReconnectAt", formatTime(h.getLastReconnectAt()));
                healthNode.put("lastDisconnectAt", formatTime(h.getLastDisconnectAt()));
                healthNode.put("lastMigrationAt", formatTime(com.start.config.DatabaseConfig.lastMigrationAt));
                healthNode.put("lastMigrationSuccess", com.start.config.DatabaseConfig.lastMigrationSuccess);
                healthNode.put("lastScheduledEventAt",
                        formatTime(com.start.service.ScheduleExecutor.lastScheduledEventAt));
                healthNode.put("lastScheduledTaskAt",
                        formatTime(com.start.service.ScheduleExecutor.lastScheduledTaskAt));
                healthNode.put("lastScheduledSuccess", com.start.service.ScheduleExecutor.lastScheduledSuccess);
                o.set("health", healthNode);
            } catch (Exception e) {
                logger.debug("拉取 health 失败: {}", e.getMessage());
            }
        }

        // GroupSerialExecutor 线程池指标
        java.util.function.Supplier<com.start.service.GroupSerialExecutor.ExecutorMetrics> provider =
                executorMetricsProvider;
        if (provider != null) {
            try {
                com.start.service.GroupSerialExecutor.ExecutorMetrics m = provider.get();
                ObjectNode execNode = mapper.createObjectNode();
                execNode.put("tasksSubmitted", m.tasksSubmitted);
                execNode.put("tasksCompleted", m.tasksCompleted);
                execNode.put("tasksRejected", m.tasksRejected);
                execNode.put("tasksExpired", m.tasksExpired);
                execNode.put("tasksActive", m.tasksActive);
                execNode.put("avgExecutionMs", m.tasksCompleted > 0
                        ? m.totalExecutionTimeMs / m.tasksCompleted : 0);
                execNode.put("maxExecutionMs", m.maxExecutionTimeMs);
                execNode.put("groupQueues", m.groupQueuesCount);
                execNode.put("groupQueuesSize", m.groupQueuesTotalSize);
                execNode.put("privateQueueSize", m.privateQueueSize);
                execNode.put("privateQueueCapacity", m.privateQueueCapacity);
                execNode.put("privateActiveThreads", m.privateActiveThreads);
                o.set("executor", execNode);
            } catch (Exception e) {
                logger.debug("拉取 executor 指标失败: {}", e.getMessage());
            }
        }

        // 工具调用排行 Top 15
        ArrayNode tools = mapper.createArrayNode();
        toolCallCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                        Comparator.comparingLong(AtomicLong::get)).reversed())
                .limit(15)
                .forEach(e -> {
                    ObjectNode t = mapper.createObjectNode();
                    t.put("name", e.getKey());
                    t.put("count", e.getValue().get());
                    tools.add(t);
                });
        o.set("topTools", tools);

        sendJson(ex, o.toString());
    }

    // ===== 表情包审阅 API =====

    /**
     * 表情包审阅接口：列表、图片预览、修改关键词和删除都复用 StickerIngestService，
     * 因此面板不会产生第二份缓存或绕过现有的持久化逻辑。
     */
    private void serveStickers(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            String path = ex.getRequestURI().getPath();
            String base = "/api/stickers";
            if (path.equals(base) || path.equals(base + "/")) {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendMethodNotAllowed(ex, "GET");
                    return;
                }
                serveStickerList(ex);
                return;
            }
            if (path.equals(base + "/image")) {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendMethodNotAllowed(ex, "GET");
                    return;
                }
                serveStickerImage(ex);
                return;
            }

            String id = urlDecode(path.substring((base + "/").length()));
            if (id.isBlank() || id.contains("/") || id.contains("\\")) {
                sendJson(ex, 404, "{\"error\":\"sticker_not_found\"}");
                return;
            }
            if ("PATCH".equalsIgnoreCase(ex.getRequestMethod())
                    || "PUT".equalsIgnoreCase(ex.getRequestMethod())) {
                updateStickerKeywords(ex, id);
            } else if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                deleteSticker(ex, id);
            } else {
                sendMethodNotAllowed(ex, "PATCH, PUT, DELETE");
            }
        } catch (IllegalStateException e) {
            sendJson(ex, 503, "{\"error\":\"sticker_service_unavailable\"}");
        } catch (Exception e) {
            logger.warn("表情包面板请求失败: {} {}", ex.getRequestMethod(), ex.getRequestURI(), e);
            sendJson(ex, 500, "{\"error\":\"sticker_request_failed\"}");
        }
    }

    private void serveReplay(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendMethodNotAllowed(ex, "POST");
            return;
        }
        String rawBody;
        try {
            rawBody = readBody(ex, 64 * 1024);
        } catch (IOException e) {
            sendJson(ex, 413, "{\"error\":\"request_body_too_large_or_unreadable\"}");
            return;
        }
        JsonNode body;
        try {
            body = mapper.readTree(rawBody);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"invalid_json\"}");
            return;
        }
        if (!body.path("test").isBoolean() || !body.path("test").asBoolean()) {
            sendJson(ex, 400, "{\"error\":\"test_flag_required\"}");
            return;
        }
        ManualMessageReplayService service = replayService;
        if (service == null) {
            sendJson(ex, 503, "{\"error\":\"replay_service_unavailable\"}");
            return;
        }

        try {
            switch (body.path("action").asText("")) {
                case "start" -> {
                    String groupId = body.path("groupId").asText("");
                    long fromId = requiredLong(body, "fromLocalMessageId");
                    long toId = requiredLong(body, "toLocalMessageId");
                    var mode = ManualMessageReplayService.ReplayMode.parse(body.path("mode").asText("HISTORICAL"));
                    ManualMessageReplayService.StartResult result = service.start(groupId, fromId, toId, mode);
                    sendJson(ex, replayStartJson(result));
                }
                case "advance" -> {
                    String replayId = body.path("replayId").asText("");
                    long messageId = requiredLong(body, "localMessageId");
                    ManualMessageReplayService.AdvanceResult result = service.advance(replayId, messageId);
                    sendJson(ex, replayAdvanceJson(result));
                }
                case "inject" -> {
                    String replayId = body.path("replayId").asText("");
                    long messageId = requiredLong(body, "localMessageId");
                    JsonNode proactiveCandidate = body.get("evaluateProactiveCandidate");
                    if (proactiveCandidate != null && !proactiveCandidate.isBoolean()) {
                        throw new IllegalArgumentException("invalid evaluateProactiveCandidate");
                    }
                    ManualMessageReplayService.InjectionResult result = service.inject(replayId, messageId,
                            proactiveCandidate != null && proactiveCandidate.asBoolean());
                    sendJson(ex, replayInjectionJson(result));
                }
                case "close" -> {
                    boolean closed = service.close(body.path("replayId").asText(""));
                    sendJson(ex, "{\"closed\":" + closed + "}");
                }
                default -> sendJson(ex, 400, "{\"error\":\"invalid_action\"}");
            }
        } catch (ManualMessageReplayService.ReplayException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", e.code());
            if (!e.details().isEmpty()) error.set("details", replayErrorDetails(e.details()));
            sendJson(ex, e.status(), error.toString());
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"error\":\"invalid_request\"}");
        } catch (Exception e) {
            logger.warn("Manual replay request failed: {}", e.getClass().getSimpleName());
            sendJson(ex, 500, "{\"error\":\"replay_request_failed\"}");
        }
    }

    private static long requiredLong(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.asLong();
    }

    private static String replayStartJson(ManualMessageReplayService.StartResult result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("test", true);
        root.put("executionScope", REPLAY_SCOPE);
        root.put("replayId", result.replayId());
        root.put("groupId", result.groupId());
        root.put("mode", result.mode());
        root.put("fromLocalMessageId", result.fromLocalMessageId());
        root.put("toLocalMessageId", result.toLocalMessageId());
        root.put("modelId", result.modelId());
        root.put("candidateId", result.candidateId());
        root.put("endpoint", result.endpoint());
        root.put("protocol", result.protocol());
        root.put("apiKeyEnv", result.apiKeyEnv());
        root.put("snapshotAt", result.snapshotAt().toString());
        root.put("nextLocalMessageId", result.nextLocalMessageId());
        ArrayNode messages = root.putArray("messages");
        for (ManualMessageReplayService.MessageView message : result.messages()) {
            ObjectNode item = messages.addObject();
            item.put("localMessageId", message.localMessageId());
            item.put("userId", message.userId());
            item.put("nickname", message.nickname());
            item.put("content", message.content());
            item.put("contentTruncated", message.contentTruncated());
            item.put("bot", message.bot());
            if (message.replyToLocalMessageId() != null) item.put("replyToLocalMessageId", message.replyToLocalMessageId());
            if (message.replyProviderMessageId() != null) {
                item.put("replyProviderMessageId", message.replyProviderMessageId());
            }
            if (message.replyToRawId() != null) item.put("replyToRawId", message.replyToRawId());
            if (message.replyReference() != null) item.put("replyReference", message.replyReference());
            item.put("replyReferenceSource", message.replyReferenceSource());
            item.put("replyResolutionStatus", message.replyResolutionStatus());
            if (message.createdAt() != null) item.put("createdAt", message.createdAt().toString());
            item.put("hasImage", message.hasImage());
            item.put("imageDescription", message.imageDescription());
            writeImageEvidence(item, "imageEvidence", message.images());
            ArrayNode ats = item.putArray("atUserIds");
            message.atUserIds().forEach(ats::add);
        }
        return root.toString();
    }

    private static String replayInjectionJson(ManualMessageReplayService.InjectionResult result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("test", true);
        root.put("executionScope", REPLAY_SCOPE);
        root.put("mode", result.mode());
        root.put("localMessageId", result.localMessageId());
        root.put("decision", result.decision());
        root.put("reply", result.reply());
        root.put("replyToCurrentMessage", result.replyToCurrentMessage());
        if (result.groupAssessment() != null) {
            root.set("groupAssessment", mapper.valueToTree(result.groupAssessment()));
        }
        root.put("modelId", result.modelId());
        root.put("candidateId", result.candidateId());
        root.put("endpoint", result.endpoint());
        root.put("apiKeyEnv", result.apiKeyEnv());
        root.put("latencyMs", result.latencyMs());
        root.put("protocol", result.protocol());
        root.put("attachedImageCount", result.attachedImageCount());
        root.put("modelCallMade", result.modelCallMade());
        root.put("triggerEvent", result.triggerEvent());
        root.put("proactiveCandidate", result.proactiveCandidate());
        root.put("requestBodySha256", result.requestBodySha256());
        root.put("snapshotAt", result.snapshotAt().toString());
        if (result.nextLocalMessageId() != null) root.put("nextLocalMessageId", result.nextLocalMessageId());
        else root.putNull("nextLocalMessageId");
        ArrayNode ids = root.putArray("contextMessageIds");
        result.contextMessageIds().forEach(ids::add);
        root.put("transcript", result.transcript());
        root.put("modelUserText", result.modelUserText());
        root.put("systemPrompt", result.systemPrompt());
        root.put("turnEvidence", result.turnEvidence());
        if (result.replyProviderMessageId() != null) {
            root.put("replyProviderMessageId", result.replyProviderMessageId());
        }
        if (result.replyToRawId() != null) root.put("replyToRawId", result.replyToRawId());
        if (result.replyReference() != null) root.put("replyReference", result.replyReference());
        root.put("replyReferenceSource", result.replyReferenceSource());
        if (result.replyToLocalMessageId() != null) {
            root.put("replyToLocalMessageId", result.replyToLocalMessageId());
        }
        root.put("replyResolutionStatus", result.replyResolutionStatus());
        writeImageEvidence(root, "imageEvidence", result.imageEvidence());
        ArrayNode omitted = root.putArray("omittedContextMessageIds");
        result.omittedContextMessageIds().forEach(omitted::add);
        return root.toString();
    }

    private static String replayAdvanceJson(ManualMessageReplayService.AdvanceResult result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("test", true);
        root.put("executionScope", REPLAY_SCOPE);
        root.put("mode", result.mode());
        root.put("localMessageId", result.localMessageId());
        root.put("decision", "OBSERVED");
        root.put("modelCallMade", false);
        root.put("snapshotAt", result.snapshotAt().toString());
        if (result.nextLocalMessageId() != null) root.put("nextLocalMessageId", result.nextLocalMessageId());
        else root.putNull("nextLocalMessageId");
        return root.toString();
    }

    private static void writeImageEvidence(com.fasterxml.jackson.databind.node.ObjectNode target, String name,
                                           java.util.List<ManualMessageReplayService.ImageEvidenceView> images) {
        ArrayNode array = target.putArray(name);
        for (ManualMessageReplayService.ImageEvidenceView image : images) {
            ObjectNode item = array.addObject();
            item.put("messageLocalId", image.messageLocalId());
            if (image.partId() != null) item.put("partId", image.partId());
            item.put("partIndex", image.partIndex());
            if (image.artifactId() != null) item.put("artifactId", image.artifactId());
            item.put("status", image.status());
            item.put("description", image.description());
            item.put("errorCode", image.errorCode());
            item.put("parserName", image.parserName());
            item.put("parserVersion", image.parserVersion());
            if (image.completedAt() != null) item.put("completedAt", image.completedAt().toString());
            item.put("sha256", image.sha256());
            item.put("sourceReferencePresent", image.sourceReferencePresent());
            item.put("sourceScheme", image.sourceScheme());
            item.put("sourceHost", image.sourceHost());
            item.put("sourceLength", image.sourceLength());
            ArrayNode queryNames = item.putArray("sourceQueryParameterNames");
            image.sourceQueryParameterNames().forEach(queryNames::add);
            item.put("providerFileIdPresent", image.providerFileIdPresent());
            item.put("providerFileIdLength", image.providerFileIdLength());
            item.put("storageKeyPresent", image.storageKeyPresent());
            item.put("storageKeyLength", image.storageKeyLength());
            item.put("sourceLoadStatus", image.sourceLoadStatus());
            item.put("sourceLoadErrorCode", image.sourceLoadErrorCode());
            item.put("descriptionInPrompt", image.descriptionInPrompt());
            item.put("attachedAsImage", image.attachedAsImage());
            item.put("inlinePayloadStatus", image.inlinePayloadStatus());
            item.put("inlinePayloadBytes", image.inlinePayloadBytes());
        }
    }

    private static ObjectNode replayErrorDetails(java.util.Map<String, Object> details) {
        ObjectNode result = mapper.createObjectNode();
        details.forEach((key, value) -> {
            if ("imageEvidence".equals(key) && value instanceof java.util.List<?> values
                    && values.stream().allMatch(ManualMessageReplayService.ImageEvidenceView.class::isInstance)) {
                @SuppressWarnings("unchecked")
                java.util.List<ManualMessageReplayService.ImageEvidenceView> images =
                        (java.util.List<ManualMessageReplayService.ImageEvidenceView>) values;
                writeImageEvidence(result, key, images);
            } else {
                result.set(key, mapper.valueToTree(value));
            }
        });
        return result;
    }

    private void serveStickerList(HttpExchange ex) throws IOException {
        StickerIngestService service = StickerIngestService.getInstance();
        String keyword = parseQuery(ex, "keyword");
        String lower = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        ArrayNode arr = mapper.createArrayNode();
        for (StickerIngestService.StickerRecord r : service.getAllStickers()) {
            if (!lower.isEmpty() && !stickerMatches(r, lower)) continue;
            ObjectNode o = mapper.createObjectNode();
            o.put("id", nullToEmpty(r.id));
            o.put("file", nullToEmpty(r.file));
            o.put("hasImage", service.hasStickerFile(r));
            o.put("description", nullToEmpty(r.description));
            o.put("correctedBy", nullToEmpty(r.correctedBy));
            o.put("correctedAt", r.correctedAt);
            o.put("sourceGroup", nullToEmpty(r.sourceGroup));
            o.put("createdAt", r.createdAt);
            o.set("keywords", mapper.valueToTree(r.keywords == null ? List.of() : r.keywords));
            o.set("autoKeywords", mapper.valueToTree(
                    r.autoKeywords == null ? List.of() : r.autoKeywords));
            arr.add(o);
        }
        sendJson(ex, arr.toString());
    }

    private void serveStickerImage(HttpExchange ex) throws IOException {
        StickerIngestService service = StickerIngestService.getInstance();
        String id = parseQuery(ex, "id");
        StickerIngestService.StickerRecord record = service.getById(id);
        byte[] bytes = record == null ? null : service.readStickerBytes(record);
        if (bytes == null || bytes.length == 0) {
            sendJson(ex, 404, "{\"error\":\"image_not_found\"}");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", service.stickerContentType(record));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void updateStickerKeywords(HttpExchange ex, String id) throws IOException {
        StickerIngestService service = StickerIngestService.getInstance();
        if (service.getById(id) == null) {
            sendJson(ex, 404, "{\"error\":\"sticker_not_found\"}");
            return;
        }
        String body = readBody(ex, 64 * 1024);
        var node = mapper.readTree(body);
        var keywordsNode = node == null ? null : node.get("keywords");
        if (keywordsNode == null || !keywordsNode.isArray()) {
            sendJson(ex, 400, "{\"error\":\"keywords_must_be_array\"}");
            return;
        }
        List<String> keywords = new ArrayList<>();
        for (var item : keywordsNode) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                if (value.length() > 40 || keywords.size() >= 20) {
                    sendJson(ex, 400, "{\"error\":\"invalid_keywords\"}");
                    return;
                }
                keywords.add(value);
            }
        }
        if (keywords.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"keywords_must_not_be_empty\"}");
            return;
        }
        service.correctKeywords(id, keywords, "web-dashboard");
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put("id", id);
        result.set("keywords", mapper.valueToTree(keywords));
        sendJson(ex, result.toString());
    }

    private void deleteSticker(HttpExchange ex, String id) throws IOException {
        StickerIngestService service = StickerIngestService.getInstance();
        if (service.getById(id) == null) {
            sendJson(ex, 404, "{\"error\":\"sticker_not_found\"}");
            return;
        }
        service.remove(id);
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put("id", id);
        sendJson(ex, result.toString());
    }

    private static boolean stickerMatches(StickerIngestService.StickerRecord r, String lower) {
        if (containsIgnoreCase(r.id, lower) || containsIgnoreCase(r.description, lower)
                || containsIgnoreCase(r.sourceGroup, lower)) return true;
        return containsIgnoreCase(r.keywords, lower) || containsIgnoreCase(r.autoKeywords, lower);
    }

    private static boolean containsIgnoreCase(String value, String lower) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lower);
    }

    private static boolean containsIgnoreCase(List<String> values, String lower) {
        if (values == null) return false;
        for (String value : values) if (containsIgnoreCase(value, lower)) return true;
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("request body too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void sendMethodNotAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        sendJson(ex, 405, "{\"error\":\"method_not_allowed\"}");
    }

    // ===== 工具方法 =====

    private static String parseQuery(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return urlDecode(kv[1]);
        }
        return null;
    }

    private static int parseQueryInt(HttpExchange ex, String key, int def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
            }
        }
        return def;
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        sendJson(ex, 200, json);
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String formatDuration(long ms) {
        Duration d = Duration.ofMillis(ms);
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return String.format("%dd %dh %dm", days, hours, mins);
        if (hours > 0) return String.format("%dh %dm", hours, mins);
        return String.format("%dm %ds", mins, d.toSeconds() % 60);
    }

    /** 0 返回 "-"（从未发生过），否则返回 HH:mm:ss 格式的本地时间。 */
    private static String formatTime(long epochMs) {
        if (epochMs <= 0) return "-";
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // ===== 内嵌 HTML 面板 =====

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>糖果熊 Dashboard</title>
            <style>
            :root {
                --bg: #0f0f1a; --card: #1a1a2e; --border: #2a2a4a;
                --text: #cdd6f4; --muted: #6c7086; --accent: #f5c2e7;
                --green: #a6e3a1; --yellow: #f9e2af; --red: #f38ba8; --blue: #89b4fa;
            }
            * { margin:0; padding:0; box-sizing:border-box; }
            body { background:var(--bg); color:var(--text); font-family:"Segoe UI",system-ui,sans-serif;
                   padding:20px; min-height:100vh; }
            .header { display:flex; justify-content:space-between; align-items:center;
                      background:var(--card); padding:16px 24px; border-radius:12px;
                      margin-bottom:16px; border:1px solid var(--border); }
            .header h1 { font-size:1.4rem; color:var(--accent); }
            .header .stats { display:flex; gap:24px; font-size:0.85rem; color:var(--muted); }
            .header .stats span { color:var(--text); font-weight:600; }
            .grid { display:grid; grid-template-columns:1.5fr 1fr; gap:16px; }
            .panel { background:var(--card); border:1px solid var(--border);
                     border-radius:12px; padding:16px; }
            .panel h2 { font-size:0.95rem; color:var(--blue); margin-bottom:12px;
                        text-transform:uppercase; letter-spacing:0.05em; }
            .trail-table { width:100%; font-size:0.78rem; border-collapse:collapse; }
            .trail-table th { text-align:left; color:var(--muted); padding:4px 6px;
                              border-bottom:1px solid var(--border); position:sticky; top:0;
                              background:var(--card); }
            .trail-table td { padding:3px 6px; border-bottom:1px solid #1e1e36; }
            .trail-table .trace-ids { max-width:360px; overflow-wrap:anywhere; color:var(--muted); }
            .trail-table .REPLY { color:var(--green); }
            .trail-table .SILENT { color:var(--muted); }
            .trail-table .ERROR { color:var(--red); }
            .scroll { max-height:65vh; overflow-y:auto; }
            .group-card { background:#16162a; border:1px solid var(--border); border-radius:8px;
                          padding:10px 14px; margin-bottom:8px; font-size:0.82rem; }
            .group-card .gid { color:var(--blue); font-weight:600; margin-bottom:4px; }
            .group-card .row { display:flex; gap:16px; color:var(--muted); }
            .group-card .row span { color:var(--text); }
            .tool-row { display:flex; justify-content:space-between; font-size:0.8rem;
                        padding:3px 0; border-bottom:1px solid #1e1e36; }
            .tool-row .name { color:var(--text); }
            .tool-row .count { color:var(--accent); font-weight:600; }
            .sticker-panel { margin-top:16px; }
            .sticker-toolbar { display:flex; gap:8px; margin-bottom:14px; }
            .sticker-toolbar input { flex:1; min-width:160px; background:#111120; color:var(--text);
                                     border:1px solid var(--border); border-radius:6px; padding:8px 10px; }
            button { background:#31315a; color:var(--text); border:1px solid #4a4a78; border-radius:6px;
                     padding:7px 12px; cursor:pointer; }
            button:hover { background:#454578; }
            button.danger { color:var(--red); border-color:#73364b; }
            .sticker-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:12px; }
            .sticker-card { background:#16162a; border:1px solid var(--border); border-radius:9px; overflow:hidden; }
            .sticker-preview { height:190px; display:flex; align-items:center; justify-content:center; background:#10101d; }
            .sticker-preview img { width:100%; height:100%; object-fit:contain; }
            .sticker-empty { color:var(--muted); font-size:.8rem; }
            .sticker-info { padding:11px; font-size:.78rem; }
            .sticker-id { color:var(--blue); font-family:monospace; word-break:break-all; }
            .sticker-desc { color:var(--muted); margin:6px 0; line-height:1.35; max-height:42px; overflow:hidden; }
            .sticker-meta { color:var(--muted); font-size:.7rem; margin:5px 0; }
            .keyword-input { width:100%; background:#111120; color:var(--text); border:1px solid var(--border);
                             border-radius:5px; padding:7px; margin:6px 0 8px; }
            .sticker-actions { display:flex; gap:7px; justify-content:flex-end; }
            .footer { margin-top:16px; text-align:center; font-size:0.75rem; color:var(--muted); }
            .pulse { animation:pulse 2s infinite; }
            @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.5} }
            </style>
            </head>
            <body>
            <div class="header">
                <h1>糖果熊 Dashboard</h1>
                <div class="stats">
                    <div>运行 <span id="uptime">-</span></div>
                    <div>消息 <span id="totalMsg">0</span></div>
                    <div>回复 <span id="totalReply">0</span></div>
                    <div>静默 <span id="totalSilent">0</span></div>
                    <div>错误 <span id="totalError">0</span></div>
                    <div>堆内存 <span id="heap">-</span></div>
                    <div class="pulse" style="color:var(--green);" id="liveDot">● LIVE</div>
                </div>
            </div>
            <div class="grid">
                <div class="panel">
                    <h2>Decision Trail</h2>
                    <div class="scroll">
                    <table class="trail-table">
                    <thead><tr>
                        <th>时间</th><th>群</th><th>用户</th><th>事件</th><th>决策</th>
                        <th>原因</th><th>工具</th><th>Token</th><th>延迟</th><th>消息链 IDs</th>
                    </tr></thead>
                    <tbody id="trailBody"></tbody>
                    </table>
                    </div>
                </div>
                <div>
                    <div class="panel" style="margin-bottom:16px;">
                        <h2>Group Metrics</h2>
                        <div id="groupCards" style="max-height:35vh;overflow-y:auto;">
                            <span style="color:var(--muted);">等待数据...</span>
                        </div>
                    </div>
                    <div class="panel">
                        <h2>Tool Call Stats</h2>
                        <div id="toolStats"><span style="color:var(--muted);">等待数据...</span></div>
                    </div>
                </div>
            </div>
            <div class="panel sticker-panel">
                <h2>表情包审阅 <span id="stickerCount" style="color:var(--muted);font-size:.75rem;"></span></h2>
                <div class="sticker-toolbar">
                    <input id="stickerSearch" placeholder="按 ID、关键词、描述或群号筛选">
                    <button id="stickerRefresh">刷新</button>
                </div>
                <div id="stickerGrid" class="sticker-grid"><span style="color:var(--muted);">加载中...</span></div>
            </div>
            <div class="footer">refresh: 3s | threads: <span id="threadCount">-</span></div>
            <script>
            const params = new URLSearchParams(location.search);
            const token = params.get('token');
            if (token) sessionStorage.setItem('dash_token', token);
            const auth = (u) => { const t = sessionStorage.getItem('dash_token'); return t ? u + (u.includes('?')?'&':'?') + 'token=' + t : u; };
            const esc = (v) => String(v ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
            const stickerDate = (v) => v ? new Date(v).toLocaleString('zh-CN') : '-';
            async function loadStickers() {
                const keyword = document.getElementById('stickerSearch').value.trim();
                const url = keyword ? '/api/stickers?keyword=' + encodeURIComponent(keyword) : '/api/stickers';
                const res = await fetch(auth(url));
                if (!res.ok) throw new Error('sticker api ' + res.status);
                const stickers = await res.json();
                document.getElementById('stickerCount').textContent = '（' + stickers.length + ' 条）';
                const grid = document.getElementById('stickerGrid');
                if (!stickers.length) { grid.innerHTML = '<span style="color:var(--muted)">没有符合条件的表情包</span>'; return; }
                grid.innerHTML = stickers.map(s => {
                    const image = s.hasImage
                        ? `<img loading="lazy" src="${esc(auth('/api/stickers/image?id=' + encodeURIComponent(s.id)))}" alt="${esc(s.id)}">`
                        : '<span class="sticker-empty">无本地图片（暂不可发送）</span>';
                    const kws = (s.keywords || []).join(', ');
                    const auto = (s.autoKeywords || []).join('、');
                    return `<article class="sticker-card" data-sticker-id="${esc(s.id)}">
                        <div class="sticker-preview">${image}</div>
                        <div class="sticker-info">
                            <div class="sticker-id">${esc(s.id)}</div>
                            <div class="sticker-desc" title="${esc(s.description)}">${esc(s.description) || '没有视觉描述'}</div>
                            <div class="sticker-meta">自动识别：${esc(auto) || '-'}</div>
                            <div class="sticker-meta">来源群：${esc(s.sourceGroup) || '-'} · ${stickerDate(s.createdAt)}</div>
                            <label>可用关键词</label>
                            <input class="keyword-input" value="${esc(kws)}" placeholder="例如：开心, 哈哈, 可爱">
                            <div class="sticker-actions"><button class="save-sticker">保存关键词</button><button class="danger delete-sticker">删除</button></div>
                        </div>
                    </article>`;
                }).join('');
            }
            async function saveSticker(card) {
                const id = card.dataset.stickerId;
                const keywords = card.querySelector('.keyword-input').value.split(/[,，、\\s]+/).map(s => s.trim()).filter(Boolean);
                const res = await fetch(auth('/api/stickers/' + encodeURIComponent(id)), {
                    method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({keywords})
                });
                if (!res.ok) throw new Error('save ' + res.status);
                await loadStickers();
            }
            async function deleteSticker(card) {
                const id = card.dataset.stickerId;
                if (!confirm('确定删除这条表情包？本地图片也会一并删除。')) return;
                const res = await fetch(auth('/api/stickers/' + encodeURIComponent(id)), {method:'DELETE'});
                if (!res.ok) throw new Error('delete ' + res.status);
                await loadStickers();
            }
            document.getElementById('stickerRefresh').addEventListener('click', () => loadStickers().catch(console.error));
            document.getElementById('stickerSearch').addEventListener('keydown', e => { if (e.key === 'Enter') loadStickers().catch(console.error); });
            document.getElementById('stickerGrid').addEventListener('click', e => {
                const card = e.target.closest('.sticker-card');
                if (!card) return;
                const action = e.target.closest('button');
                if (action?.classList.contains('save-sticker')) saveSticker(card).catch(console.error);
                if (action?.classList.contains('delete-sticker')) deleteSticker(card).catch(console.error);
            });
            async function refresh() {
                try {
                    let [sysRes, decRes, grpRes] = await Promise.all([
                        fetch(auth('/api/system')), fetch(auth('/api/decisions?limit=50')), fetch(auth('/api/groups'))
                    ]);
                    let sys = await sysRes.json();
                    document.getElementById('uptime').textContent = sys.uptime;
                    document.getElementById('totalMsg').textContent = sys.totalMessages;
                    document.getElementById('totalReply').textContent = sys.totalReplies;
                    document.getElementById('totalSilent').textContent = sys.totalSilent;
                    document.getElementById('totalError').textContent = sys.totalErrors;
                    document.getElementById('heap').textContent = sys.heapUsedMB + '/' + sys.heapMaxMB + ' MB';
                    document.getElementById('threadCount').textContent = sys.threadCount + ' threads | active groups: ' + sys.activeGroups;

                    let decs = await decRes.json();
                    let tb = document.getElementById('trailBody');
                    tb.innerHTML = decs.reverse().map(d =>
                        `<tr>
                            <td>${d.time}</td>
                            <td>${d.groupId}</td>
                            <td>${d.userId}</td>
                            <td>${d.event}</td>
                            <td class="${d.decision}">${d.decision}</td>
                            <td style="color:var(--muted)">${d.reason}</td>
                            <td>${d.toolCalls||0}</td>
                            <td>${d.tokensUsed||0}</td>
                            <td>${d.latencyMs}ms</td>
                            <td class="trace-ids">输入:${esc((d.inputMessageIds||[]).join(','))}<br>引用:${esc(d.replyToMessageId||'-')}<br>证据:${esc((d.evidenceMessageIds||[]).join(','))}</td>
                        </tr>`
                    ).join('');

                    let grps = await grpRes.json();
                    let gc = document.getElementById('groupCards');
                    gc.innerHTML = grps.length === 0 ? '<span style="color:var(--muted)">等待数据...</span>'
                        : grps.map(g =>
                        `<div class="group-card">
                            <div class="gid">群 ${g.groupId}</div>
                            <div class="row">
                                <span>消息 <span style="color:var(--text)">${g.messages}</span></span>
                                <span>回复 <span style="color:var(--green)">${g.replies}</span></span>
                                <span>静默 <span style="color:var(--muted)">${g.silent}</span></span>
                                <span>错误 <span style="color:var(--red)">${g.errors}</span></span>
                                <span>Token <span style="color:var(--text)">${g.totalTokens}</span></span>
                                <span>平均延迟 <span style="color:var(--yellow)">${g.avgLatencyMs}ms</span></span>
                            </div>
                            <div style="font-size:0.7rem;color:var(--muted);margin-top:2px;">最近活跃 ${g.lastActive}</div>
                        </div>`
                    ).join('');

                    let ts = document.getElementById('toolStats');
                    ts.innerHTML = sys.topTools.map(t =>
                        `<div class="tool-row"><span class="name">${t.name}</span><span class="count">${t.count}</span></div>`
                    ).join('') || '<span style="color:var(--muted)">暂无</span>';

                } catch(e) { console.error(e); }
            }
            refresh();
            loadStickers().catch(e => { document.getElementById('stickerGrid').innerHTML = '<span style="color:var(--red)">表情包加载失败，请检查鉴权或服务状态</span>'; console.error(e); });
            setInterval(refresh, 3000);
            // 如果 API 返回 401，提示需要 token
            fetch(auth('/api/system')).then(r => { if(r.status===401) document.body.innerHTML='<div style="text-align:center;padding:60px;color:var(--muted);"><h2>需要鉴权</h2><p>请在 URL 后添加 <code>?token=你的Token</code></p></div>'; });
            </script>
            </body>
            </html>
            """;
}
