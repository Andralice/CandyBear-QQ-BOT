package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.service.BaiLianService;
import com.start.service.BotMoodService;
import com.start.service.ConversationEvent;
import com.start.service.ConversationInterpreter;
import com.start.service.ConversationManager;
import com.start.service.ConversationMetrics;
import com.start.service.ConversationState;
import com.start.service.GenerationResult;
import com.start.service.GroupSerialExecutor;
import com.start.service.GroupCognitionMode;
import com.start.service.GroupDialogueAnalyzer;
import com.start.service.GroupDialoguePlan;
import com.start.service.LinkPreviewService;
import com.start.service.ProactiveFeedbackDetector;
import com.start.service.ProactiveInterjectionPolicy;
import com.start.service.StickerIngestService;
import com.start.service.ToolAuthorizationService;
import com.start.model.DecisionContext;
import com.start.model.DecisionTrace;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import com.start.runtime.ConversationRuntime;
import com.start.runtime.conversation.ConversationRuntimeConfig;
import com.start.runtime.conversation.ConversationSession;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.trace.WebDashboardListener;
import com.start.util.MessageUtil;
import com.start.vision.ImageUtils;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.start.util.MessageUtil.extractAts;

/**
 * AIHandler  ai模块入口
 */
public class AIHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AIHandler.class);
    private static final long MAX_QUEUE_MS = 30_000; // 排队超过30秒则丢弃

    private final BaiLianService aiService;
    private final BotMoodService moodService;
    private final GroupSerialExecutor groupExecutor;
    private final ConversationManager conversationManager;
    private final ConversationInterpreter interpreter;
    private final ConversationRuntime runtime;
    private final ConversationRuntimeConfig config;
    private final GroupDialogueAnalyzer dialogueAnalyzer;
    /** 长期记忆仓库：rate_limited 时的快速记忆查询用（不调 LLM） */
    private final LongTermMemoryRepository memoryRepo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Long> lastReactionTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastGroupReplyTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastProactiveReplyTime = new ConcurrentHashMap<>();
    private static final long PROACTIVE_SETTLE_MS = 1_500;
    private static final long NEGATIVE_FEEDBACK_WINDOW_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long NEGATIVE_FEEDBACK_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(10);
    private final ProactiveInterjectionPolicy proactivePolicy = new ProactiveInterjectionPolicy();
    private final ConcurrentHashMap<String, Long> pendingProactiveIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingProactiveTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pendingCognitionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingCognitionTasks = new ConcurrentHashMap<>();
    private final AtomicLong proactiveSequence = new AtomicLong();
    private final AtomicLong cognitionSequence = new AtomicLong();
    private final AtomicBoolean coldGroupMonitorStarted = new AtomicBoolean();
    private final ScheduledExecutorService proactiveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CandyBear-Proactive-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final Logger DECISION_LOGGER = LoggerFactory.getLogger("com.start.decision");
    private static final Logger COGNITION_LOGGER = LoggerFactory.getLogger("com.start.cognition");
    /** 连续普通消息可与后续触发合并的最大窗口；群环境另由 publicGroupHistory 维护。 */
    private static final long UNTRIGGERED_BUFFER_TTL_MS = 120_000L;
    /** 只 @ 糖果熊时交给模型理解的内部提示，不作为固定回复发送给用户。 */
    private static final String EMPTY_MENTION_PROMPT =
            "（用户只 @ 了糖果熊，没有附加文字。请结合当下关系和上下文自然回应。）";

    /** 记忆查询意图关键词：rate_limited 时如果用户明显在问"你还记得..."，不沉默，先查记忆给短回复 */
    private static final String[] MEMORY_QUERY_KEYWORDS = {
            "记得", "还记得", "之前说过", "之前说", "你说过", "你说",
            "你之前", "你刚说", "我说过", "我说", "你提过", "上次",
            "你说过吧", "我说过吧"
    };

    public AIHandler(BaiLianService aiService, GroupSerialExecutor groupExecutor, ConversationManager conversationManager,
                     ConversationInterpreter interpreter, ConversationRuntime runtime, ConversationRuntimeConfig config) {
        this(aiService, groupExecutor, conversationManager, interpreter, runtime, config,
                GroupDialogueAnalyzer.fromConfig());
    }

    public AIHandler(BaiLianService aiService, GroupSerialExecutor groupExecutor, ConversationManager conversationManager,
                     ConversationInterpreter interpreter, ConversationRuntime runtime, ConversationRuntimeConfig config,
                     GroupDialogueAnalyzer dialogueAnalyzer) {
        this.aiService = aiService;
        this.moodService = aiService.getMoodService();
        this.groupExecutor = groupExecutor;
        this.conversationManager = conversationManager;
        this.interpreter = interpreter;
        this.runtime = runtime;
        this.config = config;
        this.dialogueAnalyzer = dialogueAnalyzer;
    }

    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        if ("private".equals(messageType)) {
            String raw = msg.path("raw_message").asText().trim();
            if (raw.isEmpty()) return false;
            if (raw.startsWith("!") &&
                    !raw.startsWith("!ai ") &&
                    !raw.startsWith("！ai ") &&
                    !raw.startsWith("#ai ")) {
                return false;
            }
            return true;
        } else if ("group".equals(messageType)) {
            return true;
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long selfId = msg.path("self_id").asLong();
        long userId = msg.path("user_id").asLong();
        String messageType = msg.path("message_type").asText();
        long groupId = msg.path("group_id").asLong();
        JsonNode messageArray = msg.path("message");
        List<Long> ats = extractAts(messageArray);
        String nickname = msg.path("sender").path("nickname").asText();
        if (userId == selfId) return;

        String plainText = MessageUtil.extractPlainText(msg.path("message")).trim();
        String rawMessage = msg.path("raw_message").asText();
        String messageId = msg.path("message_id").asText("");
        String senderNick = msg.path("sender").path("card").asText();
        if (senderNick.isEmpty()) {
            senderNick = msg.path("sender").path("nickname").asText();
        }

        // 提取图片信息（只在 WebSocket 线程提取 URL，下载在 executor 内完成）
        List<Map<String, String>> imageInfos = MessageUtil.extractImages(msg.path("message"));
        // 后续可能把多条群消息合并成一次生成；把消息 ID 和消息内序号一起带过缓冲区。
        for (int i = 0; i < imageInfos.size(); i++) {
            Map<String, String> image = imageInfos.get(i);
            image.put("message_id", messageId);
            image.put("image_index", String.valueOf(i + 1));
            image.put("sender_id", String.valueOf(userId));
        }

        // 消息接收阶段立即 fire ImageReceived：让 StickerHarvesterListener 异步入库，
        // 不依赖 conversation 是否被唤醒/速率限制/无关键 prompt。
        if (!imageInfos.isEmpty()) {
            String gidForSticker = "group".equals(messageType) ? String.valueOf(groupId) : "private";
            String uidForSticker = String.valueOf(userId);
            for (Map<String, String> img : imageInfos) {
                String url = imageSource(img);
                if (!url.isBlank()) {
                    try {
                        runtime.fire(new RuntimeEvent.ImageReceived(gidForSticker, uidForSticker, url));
                    } catch (Exception e) {
                        logger.debug("fire ImageReceived 失败: {}", e.getMessage());
                    }
                }
            }
        }

        // 提取文件信息 → 存入缓存，由副 AI 处理，主 AI 通过 query_file 工具主动获取
        List<Map<String, String>> fileInfos = MessageUtil.extractFiles(msg.path("message"));
        if (!fileInfos.isEmpty()) {
            String fileKey = "group".equals(messageType)
                    ? "group_" + groupId + "_" + userId
                    : "private_" + userId;
            aiService.addPendingFiles(fileKey, fileInfos);
        }

        // 提取链接（分享卡片 + 纯文本 URL）
        List<String> linksToFetch = new ArrayList<>();
        for (Map<String, String> share : MessageUtil.extractShares(msg.path("message"))) {
            String url = share.get("url");
            if (url != null && !url.isEmpty()) {
                linksToFetch.add(url);
            }
        }
        linksToFetch.addAll(MessageUtil.extractUrls(rawMessage));

        // 私聊
        if ("private".equals(messageType)) {
            handlePrivateMessage(bot, msg, userId, rawMessage, plainText, nickname, imageInfos, linksToFetch);
            return;
        }

        Long replyId = MessageUtil.extractReplyId(msg.path("message"));

        // 群聊：先记录原始消息及协议层指向信息到上下文（WebSocket 线程，无竞争）
        aiService.recordPublicGroupMessage(
                String.valueOf(groupId),
                String.valueOf(userId),
                senderNick,
                plainText,
                messageId,
                ats,
                replyId
        );

        String gid = String.valueOf(groupId);
        String uid = String.valueOf(userId);
        GroupCognitionMode cognitionMode = dialogueAnalyzer.modeFor(gid);

        // A new group message means an earlier interjection candidate is no longer timely.
        cancelPendingProactive(gid);
        cancelPendingCognition(gid);

        if (cognitionMode == GroupCognitionMode.SHADOW && !plainText.isBlank()) {
            List<BaiLianService.PublicMessage> snapshot = aiService.getPublicGroupHistorySnapshot(gid, 12);
            dialogueAnalyzer.analyzeAsync(snapshot).whenComplete((plan, error) -> {
                recordShadowCognition(gid, uid, messageId, plainText, plan, error);
                if (error != null) {
                    COGNITION_LOGGER.warn("shadow gid={} uid={} msg={} failed={}",
                            gid, uid, messageId, error.getClass().getSimpleName());
                } else {
                    COGNITION_LOGGER.info("shadow gid={} uid={} msg={} {}",
                            gid, uid, messageId, plan.compactSummary());
                }
            });
        }

        if (isNegativeFeedbackForRecentProactive(gid, plainText, ats)) {
            interpreter.setNegativeCooldown(gid, NEGATIVE_FEEDBACK_COOLDOWN_MS);
            if (moodService != null) moodService.onNegativeInteraction(gid);
            logDecision(gid, uid, "PROBABILISTIC", "SILENT", "negative_feedback", 0, 0, 0);
            return;
        }

        // 群聊情绪追踪
        if (moodService != null) {
            moodService.recordGroupActivity(gid);
            if (ats.contains(BotConfig.getBotQq())) {
                moodService.onMentioned(gid);
            }
        }

        // 用户短期连续消息保留在临时缓冲中；超过 120 秒、且从未提交给 AI 的缓冲
        // 会在这里自动替换。群内环境仍由独立的 publicGroupHistory/SceneState 记录。
        ConversationState conv = conversationManager.getOrCreatePending(gid, uid, UNTRIGGERED_BUFFER_TTL_MS);
        conv.addMessage(plainText, messageId);
        if (!imageInfos.isEmpty()) {
            for (Map<String, String> img : imageInfos) {
                conv.addImageInfo(img.get("url"), img.get("file"),
                        img.get("message_id"), parseImageIndex(img.get("image_index")), img.get("sender_id"));
            }
        }
        for (String link : linksToFetch) {
            conv.addLink(link);
        }
        if (replyId != null) conv.setReplyToMessageId(replyId);
        conv.incrementRevision();

        // Thread 更新：每条群消息都维护群级 Thread 状态，保持群级顺序且不阻塞 WebSocket 线程。
        groupExecutor.execute(gid, () -> {
            aiService.processThreadMessage(gid, uid, plainText);
        });

        // 明确触发（#ai / !ai / @）
        if (isExplicitTrigger(msg, rawMessage)) {
            aiService.cancelPendingAwait(gid, uid);
            String strippedPrompt = extractPrompt(rawMessage, plainText);
            if (isClearCommand(strippedPrompt)) {
                aiService.clearContext("group_" + groupId + "_" + uid);
                bot.sendReply(msg, "已清除我们的聊天记忆！");
                conversationManager.remove(gid, uid);
                logDecision(gid, uid, "MENTION", "REPLY", "clear", 0, 0, 0);
                return;
            }
            conv.markSubmitted();
            long t0 = System.currentTimeMillis();
            runGroupConversation(bot, groupId, gid, uid, nickname, ats, false, t0, ConversationEvent.MENTION);
            return;
        }

        // 记录群聊节奏
        runtime.fire(new RuntimeEvent.MessageReceived(gid, uid, plainText));

        // ConversationInterpreter 识别事件类型
        ConversationInterpreter.InterpretResult result = interpreter.interpret(
                gid, uid, senderNick, plainText, rawMessage, ats);

        // 纯图片消息的追问处理
        boolean imageFollowUp = !imageInfos.isEmpty() && result.isNothing()
                && aiService.isWithinFollowUpWindow(gid, uid);

        // Keep protocol/direct and explicit await behavior intact. ENFORCE only replaces
        // the heuristic path for ordinary text continuations and proactive candidates.
        if (cognitionMode.controlsOrdinaryTriggers()
                && !plainText.isBlank()
                && !imageFollowUp
                && !result.isDirect()
                && result.event() != ConversationEvent.AWAIT_REPLY) {
            scheduleCognitionDecision(bot, groupId, gid, uid, nickname, ats, plainText,
                    conv, result);
            return;
        }

        handleLegacyDecision(bot, groupId, gid, uid, nickname, ats, plainText,
                conv, result, imageFollowUp, null);
    }

    /** Existing trigger/rate-limit/generation path, also used as cognition failure fallback. */
    private void handleLegacyDecision(Main bot, long groupId, String gid, String uid, String nickname,
                                      List<Long> ats, String plainText, ConversationState conv,
                                      ConversationInterpreter.InterpretResult result, boolean imageFollowUp,
                                      GroupDialoguePlan dialoguePlan) {
        if (result.isNothing() && !imageFollowUp) {
            logDecision(gid, uid, "NOTHING", "SILENT", "no_trigger", 0, 0, 0);
            return;
        }

        if (result.event() == ConversationEvent.PROBABILISTIC) {
            ConversationMetrics.Snapshot snapshot = aiService.getConversationMetrics() != null
                    ? aiService.getConversationMetrics().getSnapshot(gid)
                    : ConversationMetrics.Snapshot.EMPTY;
            ProactiveInterjectionPolicy.Decision decision = proactivePolicy.assess(
                    plainText, snapshot, interpreter.isNegativeCooldown(gid), random.nextDouble());
            if (!decision.shouldSchedule()) {
                logDecision(gid, uid, result.event().name(), "SILENT", decision.reason(), 0, 0, 0);
                return;
            }
            conv.markSubmitted();
            scheduleProactiveReply(bot, groupId, gid, uid, nickname, ats, result);
            return;
        }

        long now = System.currentTimeMillis();
        boolean bypassReplyCooldown = bypassesReplyCooldown(result.event());

        // 明确的认知续聊与显式 @ 语义相同：已有话权时不能被主动插话冷却误杀。
        boolean rateLimited = false;
        if (!bypassReplyCooldown && result.event() != ConversationEvent.PASSIVE_TRIGGER && !imageFollowUp) {
            rateLimited = !aiService.canReact(gid);
        }

        // 群级冷却
        if (!bypassReplyCooldown) {
            long groupCooldown = config.groupReplyCooldown().toMillis();
            Long lastGroupReply = lastGroupReplyTime.get(gid);
            if (lastGroupReply != null && now - lastGroupReply < groupCooldown) {
                rateLimited = true;
            }

            // 用户冷却
            long userCooldown = config.userReplyCooldown().toMillis();
            String userKey = gid + "_" + uid;
            Long last = lastReactionTime.get(userKey);
            if (last != null && now - last < userCooldown) {
                rateLimited = true;
            }
        }

        if (rateLimited) {
            // 记忆查询快路径：rate_limited 但用户明显在问"你还记得..."
            // 不沉默，DB 查一下就发短回复——成本极低，避免把记忆问题交给泛化兜底。
            // 注意：只更新 groupReplyTime 不更新 userReactionTime，避免被刷屏
            String shortReply = tryFastMemoryReply(uid, gid, plainText);
            if (shortReply != null) {
                // 第二阶段 2.2：传 sessionId 让 AI 回复与用户消息同 session
                String fastSessionId = com.start.service.SessionId.groupConversation(gid, uid);
                boolean delivered = sendSplitGroupReplies(bot, groupId, shortReply, fastSessionId);
                if (delivered) {
                    recordDeliveredReaction(gid, uid);
                }
                // 留痕：让后续 Interpreter 能识别到这条 ai_reply
                // （否则 fast path 回复后的 3 分钟内，AI_COMMENTED 事件识别不到）
                logDecision(gid, uid, result.event().name(), delivered ? "REPLY" : "SILENT",
                        delivered ? "memory_recall_fast" : "send_failed", 0, 0, 0);
                return;
            }
            logDecision(gid, uid, result.event().name(), "SILENT", "rate_limited", 0, 0, 0);
            return;
        }

        if (result.isDirect()) {
            // 被动触发直接回复，不走 AI
            boolean delivered = sendSplitGroupReplies(bot, groupId, result.directReply(),
                    com.start.service.SessionId.groupConversation(gid, uid));
            if (delivered) {
                recordDeliveredReaction(gid, uid);
            }
            conversationManager.remove(gid, uid);
            logDecision(gid, uid, result.event().name(), delivered ? "REPLY" : "SILENT",
                    delivered ? "direct" : "send_failed", 0, 0, now - System.currentTimeMillis());
            return;
        }

        // 需要 AI 生成：只有 PROBABILISTIC 允许沉默
        conv.markSubmitted();
        boolean allowSilence = result.event().allowsSilence();
        long startMs = System.currentTimeMillis();
        runGroupConversation(bot, groupId, gid, uid, nickname, ats, allowSilence, startMs,
                result.event(), dialoguePlan, result.prompt());
    }

    private void logDecision(String gid, String uid, String eventType, String decision,
                             String reason, int toolCalls, int tokensUsed, long latencyMs) {
        DecisionTrace trace = new DecisionTrace(System.currentTimeMillis(), gid, uid, eventType,
                decision, reason, toolCalls, tokensUsed, latencyMs, 0, 0, false);
        DECISION_LOGGER.info(trace.toLogLine());
        WebDashboardListener.recordDecision(gid, uid, eventType, decision, reason,
                toolCalls, tokensUsed, latencyMs);
    }

    static boolean bypassesReplyCooldown(ConversationEvent event) {
        return event == ConversationEvent.COGNITIVE_CONTINUE;
    }

    private void scheduleProactiveReply(Main bot, long groupId, String gid, String uid, String nickname,
                                        List<Long> ats, ConversationInterpreter.InterpretResult result) {
        long candidateId = proactiveSequence.incrementAndGet();
        pendingProactiveIds.put(gid, candidateId);
        ScheduledFuture<?> previous = pendingProactiveTasks.remove(gid);
        if (previous != null) previous.cancel(false);

        ScheduledFuture<?> task = proactiveScheduler.schedule(() -> groupExecutor.execute(gid, () -> {
            if (!pendingProactiveIds.remove(gid, candidateId)) return;
            pendingProactiveTasks.remove(gid);

            ConversationMetrics.Snapshot snapshot = aiService.getConversationMetrics() != null
                    ? aiService.getConversationMetrics().getSnapshot(gid)
                    : ConversationMetrics.Snapshot.EMPTY;
            if (!proactivePolicy.remainsAppropriate(snapshot, interpreter.isNegativeCooldown(gid))) {
                logDecision(gid, uid, result.event().name(), "SILENT", "group_changed_during_settle", 0, 0, 0);
                return;
            }
            if (conversationManager.get(gid, uid) == null) {
                logDecision(gid, uid, result.event().name(), "SILENT", "conversation_expired", 0, 0, 0);
                return;
            }
            if (!canStartReaction(gid, uid)) {
                logDecision(gid, uid, result.event().name(), "SILENT", "rate_limited", 0, 0, 0);
                return;
            }
            runGroupConversation(bot, groupId, gid, uid, nickname, ats, true,
                    System.currentTimeMillis(), result.event(), null, result.prompt());
        }), PROACTIVE_SETTLE_MS, TimeUnit.MILLISECONDS);
        pendingProactiveTasks.put(gid, task);
    }

    private void cancelPendingProactive(String gid) {
        pendingProactiveIds.remove(gid);
        ScheduledFuture<?> task = pendingProactiveTasks.remove(gid);
        if (task != null) task.cancel(false);
    }

    private void cancelPendingCognition(String gid) {
        pendingCognitionIds.remove(gid);
        ScheduledFuture<?> task = pendingCognitionTasks.remove(gid);
        if (task != null) task.cancel(false);
    }

    /** Debounces ordinary group traffic, then lets the two cognition tasks run in parallel. */
    private void scheduleCognitionDecision(Main bot, long groupId, String gid, String uid, String nickname,
                                           List<Long> ats, String plainText, ConversationState conv,
                                           ConversationInterpreter.InterpretResult legacyResult) {
        long candidateId = cognitionSequence.incrementAndGet();
        long expectedRevision = conv.getMessageRevision();
        pendingCognitionIds.put(gid, candidateId);

        ScheduledFuture<?> task = proactiveScheduler.schedule(() -> {
            if (pendingCognitionIds.getOrDefault(gid, -1L) != candidateId) return;
            List<BaiLianService.PublicMessage> snapshot = aiService.getPublicGroupHistorySnapshot(gid, 12);
            if (snapshot.isEmpty()) return;
            String expectedLatestMessageId = latestHumanMessageId(snapshot);
            long started = System.currentTimeMillis();
            try {
                dialogueAnalyzer.analyzeAsync(snapshot).whenComplete((plan, error) ->
                        groupExecutor.execute(gid, () -> completeCognitionDecision(
                                bot, groupId, gid, uid, nickname, ats, plainText, conv, legacyResult,
                                candidateId, expectedRevision, expectedLatestMessageId, started, plan, error)));
            } catch (Throwable error) {
                groupExecutor.execute(gid, () -> completeCognitionDecision(
                        bot, groupId, gid, uid, nickname, ats, plainText, conv, legacyResult,
                        candidateId, expectedRevision, expectedLatestMessageId, started, null, error));
            }
        }, PROACTIVE_SETTLE_MS, TimeUnit.MILLISECONDS);
        pendingCognitionTasks.put(gid, task);
    }

    private void completeCognitionDecision(Main bot, long groupId, String gid, String uid, String nickname,
                                           List<Long> ats, String plainText, ConversationState conv,
                                           ConversationInterpreter.InterpretResult legacyResult,
                                           long candidateId, long expectedRevision, String expectedLatestMessageId,
                                           long started, GroupDialoguePlan plan, Throwable error) {
        if (!pendingCognitionIds.remove(gid, candidateId)) return;
        pendingCognitionTasks.remove(gid);

        ConversationState current = conversationManager.get(gid, uid);
        List<BaiLianService.PublicMessage> latestSnapshot = aiService.getPublicGroupHistorySnapshot(gid, 12);
        boolean sameLatestMessage = expectedLatestMessageId.equals(latestHumanMessageId(latestSnapshot));
        if (current == null || current != conv || current.getMessageRevision() != expectedRevision
                || !sameLatestMessage) {
            logDecision(gid, uid, "COGNITION", "SILENT", "stale_candidate", 0, 0,
                    System.currentTimeMillis() - started);
            return;
        }

        if (error != null || plan == null) {
                COGNITION_LOGGER.warn("enforce fallback gid={} uid={} reason=analysis_failed", gid, uid);
                handleLegacyDecision(bot, groupId, gid, uid, nickname, ats, plainText,
                        conv, legacyResult, false, null);
                return;
        }

        COGNITION_LOGGER.info("enforce gid={} uid={} {}", gid, uid, plan.compactSummary());
        if (!plan.shouldReply()) {
            logDecision(gid, uid, "COGNITION", "SILENT", "parallel_observer",
                    0, plan.tokensUsed(), System.currentTimeMillis() - started);
            return;
        }

        ConversationEvent event = plan.entryRole() == GroupDialoguePlan.EntryRole.SECOND_PERSON
                ? ConversationEvent.COGNITIVE_CONTINUE
                : ConversationEvent.COGNITIVE_INTERJECTION;
        if (event == ConversationEvent.COGNITIVE_INTERJECTION) {
            ConversationMetrics.Snapshot metrics = aiService.getConversationMetrics() != null
                    ? aiService.getConversationMetrics().getSnapshot(gid)
                    : ConversationMetrics.Snapshot.EMPTY;
            if (!proactivePolicy.remainsAppropriate(metrics, interpreter.isNegativeCooldown(gid))) {
                logDecision(gid, uid, event.name(), "SILENT", "interjection_guard",
                        0, plan.tokensUsed(), System.currentTimeMillis() - started);
                return;
            }
        }

        ConversationInterpreter.InterpretResult cognitiveResult =
                ConversationInterpreter.InterpretResult.of(event, plainText);
        handleLegacyDecision(bot, groupId, gid, uid, nickname, ats, plainText,
                conv, cognitiveResult, false, plan);
    }

    /** Persist shadow outcomes for sampling and aggregate reports; this never changes routing or replies. */
    private void recordShadowCognition(String gid, String uid, String messageId, String plainText,
                                       GroupDialoguePlan plan, Throwable error) {
        try {
            if (aiService.getAiDatabaseService() == null) return;
            String decision;
            String reason;
            if (error != null || plan == null) {
                decision = "SHADOW_ERROR";
                reason = "message_id=" + messageId + ";error=" + errorSummary(error);
            } else {
                decision = plan.shouldReply() ? "SHADOW_REPLY" : "SHADOW_SILENT";
                reason = "message_id=" + messageId + ";" + plan.compactSummary();
            }
            aiService.getAiDatabaseService().logActiveReplyDecision(
                    gid, uid, plainText, decision, reason, "");
        } catch (Exception e) {
            COGNITION_LOGGER.debug("shadow audit persistence skipped gid={} msg={}: {}",
                    gid, messageId, e.getMessage());
        }
    }

    private static String errorSummary(Throwable error) {
        if (error == null) return "unknown";
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ":" + message);
    }

    private static String latestHumanMessageId(List<BaiLianService.PublicMessage> messages) {
        if (messages == null) return "";
        String botId = String.valueOf(BotConfig.getBotQq());
        for (int i = messages.size() - 1; i >= 0; i--) {
            BaiLianService.PublicMessage message = messages.get(i);
            if (!botId.equals(message.userId)) return message.messageId;
        }
        return "";
    }

    private boolean canStartReaction(String gid, String uid) {
        if (!aiService.canReact(gid)) return false;
        long now = System.currentTimeMillis();
        Long lastGroupReply = lastGroupReplyTime.get(gid);
        if (lastGroupReply != null && now - lastGroupReply < config.groupReplyCooldown().toMillis()) return false;
        Long lastUserReply = lastReactionTime.get(gid + "_" + uid);
        return lastUserReply == null || now - lastUserReply >= config.userReplyCooldown().toMillis();
    }

    private void recordDeliveredReaction(String gid, String uid) {
        long now = System.currentTimeMillis();
        lastGroupReplyTime.put(gid, now);
        lastReactionTime.put(gid + "_" + uid, now);
        aiService.recordReaction(gid);
    }

    private void recordProactiveDelivery(String gid) {
        lastProactiveReplyTime.put(gid, System.currentTimeMillis());
    }

    private boolean isNegativeFeedbackForRecentProactive(String gid, String message, List<Long> ats) {
        Long proactiveAt = lastProactiveReplyTime.get(gid);
        if (proactiveAt == null) return false;
        long elapsed = System.currentTimeMillis() - proactiveAt;
        if (elapsed > NEGATIVE_FEEDBACK_WINDOW_MS) {
            lastProactiveReplyTime.remove(gid, proactiveAt);
            return false;
        }
        return ProactiveFeedbackDetector.isDirectedNegativeFeedback(message, ats, BotConfig.getBotQq());
    }

    /** Starts a daemon check for quiet groups after all services have been initialized. */
    public void startProactiveMonitor(Main bot) {
        if (moodService == null || !coldGroupMonitorStarted.compareAndSet(false, true)) return;
        proactiveScheduler.scheduleWithFixedDelay(() -> checkColdGroups(bot), 1, 1, TimeUnit.MINUTES);
    }

    private void checkColdGroups(Main bot) {
        for (String gid : moodService.getTrackedGroupIds()) {
            try {
                if (!moodService.shouldThrowTopic(gid)
                        || interpreter.isNegativeCooldown(gid)
                        || !canStartReaction(gid, "candybear")) continue;
                long groupId = Long.parseLong(gid);
                // [2026-08-14] 暂时关掉冷场主动插话的开场白——用户反馈太突兀（"问大家想听什么歌"这种）。
                //   getColdGroupOpening() 方法保留不动，以后想换内容或重新开启直接恢复。
                // String opening = moodService.getColdGroupOpening(gid);
                // boolean delivered = bot.sendGroupReply(groupId, opening,
                //         com.start.service.SessionId.groupBotReply(gid));
                // if (!delivered) continue;
                // moodService.recordTopicThrown(gid);
                // recordDeliveredReaction(gid, "candybear");
                // recordProactiveDelivery(gid);
                // if (aiService.getConversationMetrics() != null) {
                //     aiService.getConversationMetrics().recordAiReply(gid);
                // }
                // moodService.onBotSpeak(gid);
                // logDecision(gid, "candybear", "COLD_START", "REPLY", "quiet_group", 0, 0, 0);
            } catch (Exception e) {
                logger.debug("Cold-group prompt skipped for {}: {}", gid, e.getMessage());
            }
        }
    }

    private static final HttpClient auditHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    /** 从 ConversationState 读取累积消息，生成回复并发送。在 executor 线程中执行。 */
    private void runGroupConversation(Main bot, long groupId, String gid, String userId, String nickname,
                                       List<Long> ats, boolean allowSilence, long startMs, ConversationEvent event) {
        runGroupConversation(bot, groupId, gid, userId, nickname, ats, allowSilence, startMs, event, null);
    }

    private void runGroupConversation(Main bot, long groupId, String gid, String userId, String nickname,
                                      List<Long> ats, boolean allowSilence, long startMs, ConversationEvent event,
                                      GroupDialoguePlan precomputedPlan) {
        runGroupConversation(bot, groupId, gid, userId, nickname, ats, allowSilence, startMs, event,
                precomputedPlan, null);
    }

    private void runGroupConversation(Main bot, long groupId, String gid, String userId, String nickname,
                                      List<Long> ats, boolean allowSilence, long startMs, ConversationEvent event,
                                      GroupDialoguePlan precomputedPlan, String promptOverride) {
        groupExecutor.executeConversation(gid, userId, () -> {
            ConversationState state = conversationManager.get(gid, userId);
            // 显式 @ 即使没有正文也已经提交给模型；普通空 buffer 仍然直接丢弃。
            if (state == null || (!state.hasContent() && !state.isSubmitted())) return;

            GroupDialoguePlan dialoguePlan = precomputedPlan;
            GroupCognitionMode cognitionMode = dialogueAnalyzer.modeFor(gid);
            if (dialoguePlan == null && cognitionMode.injectsPerspective()) {
                dialoguePlan = dialogueAnalyzer.analyze(aiService.getPublicGroupHistorySnapshot(gid, 12));
                if (dialoguePlan != null) {
                    COGNITION_LOGGER.info("assist gid={} uid={} event={} {}",
                            gid, userId, event, dialoguePlan.compactSummary());
                }
            }

            long startRevision = state.getMessageRevision();
            int startMsgCount = state.getPendingMessages().size();
            state.incrementGeneration();
            GenerationResult genResult = null;
            DecisionContext dc = null;

            // 图片 + 链接上下文在一次对话中不变，提到循环外避免重复下载/描述
            String replyContext = "";
            Long replyToMsgId = state.getReplyToMessageId();
            if (replyToMsgId != null) {
                replyContext = fetchReplyContext(replyToMsgId, bot);
            }
            List<Map<String, String>> imageInfoMaps = new ArrayList<>();
            for (ConversationState.ImageInfo img : state.getImageInfos()) {
                Map<String, String> m = new HashMap<>();
                m.put("url", img.url());
                m.put("file", img.file());
                m.put("message_id", img.messageId());
                m.put("image_index", String.valueOf(img.imageIndex()));
                m.put("sender_id", img.senderId() == null || img.senderId().isBlank()
                        ? userId : img.senderId());
                imageInfoMaps.add(m);
            }
            List<String> imageDataUris = downloadImages(imageInfoMaps);
            String imageDesc = describeImagesWithCache(imageInfoMaps, imageDataUris);
            String linkContext = buildLinkContext(state.getLinksToFetch());

            if (!imageDesc.isEmpty()) {
                String latestMessageId = state.getPendingMessageIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .reduce((first, second) -> second)
                        .orElse(null);
                attachImageDescriptions(bot, "group", gid, userId, imageInfoMaps, latestMessageId);
            }

            // Vision 描述完成 → 触发 ImageDescribed 事件，让 StickerHarvesterListener 异步入库
            // 最多 3 张图，URL 与 imageInfoMaps 对应
            fireImageDescribed(gid, userId, imageInfoMaps, imageDesc);

            for (int attempt = 0; attempt <= config.maxRegenerate(); attempt++) {
                if (attempt > 0) {
                    startRevision = state.getMessageRevision();
                    startMsgCount = state.getPendingMessages().size();
                    state.incrementGeneration();
                    state.incrementRegenerateCount();
                    logger.debug("Conversation regenerate gen={} rev={} count={} gid={} uid={}",
                            state.getGeneration(), state.getMessageRevision(), state.getRegenerateCount(), gid, userId);
                }

                // 从 buffer 读取累积的消息文本（每次循环可能变化）
                String mergedText = state.getMergedText();
                String prompt = composeGenerationPrompt(mergedText, promptOverride, attempt);
                if (!replyContext.isEmpty()) prompt = replyContext + prompt;
                if (prompt.isEmpty()) {
                    prompt = state.getImageInfos().isEmpty() ? EMPTY_MENTION_PROMPT : "看一下这张图片";
                }
                if (!imageDesc.isEmpty()) prompt = prompt + "\n\n" + imageDesc;
                if (!linkContext.isEmpty()) prompt = prompt + "\n\n" + linkContext;

                // 冻结决策上下文（Replay 用）
                ConversationMetrics convMetrics = aiService.getConversationMetrics();
                ConversationMetrics.Snapshot snap = convMetrics != null
                        ? convMetrics.getSnapshot(gid) : ConversationMetrics.Snapshot.EMPTY;
                dc = DecisionContext.of(
                        event, state.getGeneration(), state.getMessageRevision(),
                        allowSilence, snap.messagesLast30s(), snap.aiMessagesLast5m());

                ConversationSession session = ConversationSession.of(gid, userId, nickname)
                        .userPrompt(prompt)
                        .capabilityInput(mergedText, !state.getImageInfos().isEmpty())
                        .atUserIds(ats)
                        .allowSilence(allowSilence)
                        .generation(state.getGeneration())
                        .revision(state.getMessageRevision())
                        .event(event)
                        .metricsSnapshot(snap)
                        .excludedPublicMessageIds(state.getPendingMessageIds().stream()
                                .filter(id -> id != null && !id.isBlank())
                                .collect(java.util.stream.Collectors.toSet()))
                        .dialoguePlan(dialoguePlan)
                        .imageDataUris(BotConfig.isBaiLianMultimodalEnabled()
                                ? imageDataUris : Collections.emptyList())
                        .startMs(startMs)
                        .build();

                aiService.setSuppressSessionWrite(true);
                try {
                    genResult = aiService.generate(session);
                } finally {
                    aiService.setSuppressSessionWrite(false);
                }

                String reply = genResult.reply();

                // 模型沉默 — 直接退出
                if (genResult.isSilent()) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    runtime.fire(new RuntimeEvent.CommitFinished(gid, userId, genResult, elapsed, dc));
                    conversationManager.remove(gid, userId);
                    return;
                }

                // 检查 LLM 调用期间是否有新消息到达
                if (state.getMessageRevision() == startRevision) {
                    break;
                }
                if (attempt >= config.maxRegenerate()) {
                    logger.debug("Max regenerate reached, sending anyway gid={} uid={}", gid, userId);
                    break;
                }

                // 有新消息 → audit 模型判断是否合并再生
                String oldText = getMergedTextRange(state.getPendingMessages(), 0, startMsgCount);
                String newText = getMergedTextRange(state.getPendingMessages(), startMsgCount, state.getPendingMessages().size());
                String auditResult = classifyMessageRelation(oldText, newText, reply);
                logger.debug("Audit classify: {} old=[{}] new=[{}] gid={} uid={}", auditResult, oldText, newText, gid, userId);

                if ("C".equals(auditResult)) {
                    continue;
                }
                break;
            }

            String reply = genResult != null ? genResult.reply() : "";
            long elapsed = System.currentTimeMillis() - startMs;

            String groupSessionId = com.start.service.SessionId.groupConversation(gid, String.valueOf(userId));
            if (genResult != null && genResult.isError()) {
                String failureMessage = genResult.reply();
                boolean delivered = failureMessage != null && !failureMessage.isBlank()
                        && sendSplitGroupReplies(bot, groupId, failureMessage, groupSessionId);
                if (delivered) recordDeliveredReaction(gid, userId);
                logDecision(gid, userId, event.name(), delivered ? "REPLY" : "SILENT",
                        delivered ? "generation_failed" : "send_failed",
                        genResult.toolCalls(), 0, elapsed);
            } else if (reply != null && !reply.trim().isEmpty()) {
                boolean delivered = sendSplitGroupReplies(bot, groupId, reply, groupSessionId);
                if (delivered) {
                    recordDeliveredReaction(gid, userId);
                    if (event == ConversationEvent.PROBABILISTIC
                            || event == ConversationEvent.COGNITIVE_INTERJECTION) {
                        recordProactiveDelivery(gid);
                    }
                    aiService.commitGeneration(groupSessionId, String.valueOf(userId),
                            state.getMergedText(), reply, gid);
                    if (moodService != null) moodService.onBotSpeak(gid);
                    runtime.fire(new RuntimeEvent.CommitFinished(gid, userId, genResult, elapsed, dc));
                } else {
                    logDecision(gid, userId, event.name(), "SILENT", "send_failed",
                            genResult != null ? genResult.toolCalls() : 0, 0, elapsed);
                }
            } else {
                logDecision(gid, userId, allowSilence ? "PROBABILISTIC" : "OTHER",
                        "SILENT", "empty_reply",
                        genResult != null ? genResult.toolCalls() : 0, 0, elapsed);
            }

            conversationManager.remove(gid, userId);
        });
    }

    /**
     * InterpretResult 的语义提示只在首次请求使用；重新生成时以最新 buffer 为准，避免旧上下文覆盖新消息。
     */
    static String composeGenerationPrompt(String mergedText, String promptOverride, int attempt) {
        String current = mergedText == null ? "" : mergedText.trim();
        String override = promptOverride == null ? "" : promptOverride.trim();
        if (attempt > 0 || override.isEmpty()) return current;
        if (current.isEmpty() || override.contains(current)) return override;
        return override + "\n本条消息中的可见文字：" + current;
    }

    private static String getMergedTextRange(List<ConversationState.MessageEntry> msgs, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(msgs.size(), end);
        if (from >= to) return "";
        return msgs.subList(from, to).stream()
                .map(ConversationState.MessageEntry::text)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /** 调用 audit 模型（便宜）判断后半部分消息的性质。返回 S / C / N */
    private String classifyMessageRelation(String oldText, String newText, String generatedReply) {
        if (newText.isEmpty()) return "S";
        try {
            String prompt = "用户连续发了消息，AI已为前半部分生成了回复，但后半部分在回复生成后才到达。\n"
                    + "\n【前半部分消息】\n" + (oldText.isEmpty() ? "（空）" : oldText)
                    + "\n\n【后半部分新增消息】\n" + newText
                    + "\n\n【AI已生成的回复】\n" + generatedReply
                    + "\n\n请判断后半部分消息的性质，只回复一个大写字母：\n"
                    + "S - 后半部分是前半部分的重复/同义/废话，不需要额外回复\n"
                    + "C - 后半部分是前半部分的补充/修正/延续，应该合并后重新生成回复\n"
                    + "N - 后半部分是新的独立话题，与前半部分无关\n\n只回复一个字母：";

            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("model", BotConfig.getAuditModel());
            ArrayNode msgs = body.putArray("messages");
            ObjectNode msg = msgs.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            body.put("max_tokens", 10);
            body.put("temperature", 0.0);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BotConfig.getAuditBaseUrl()))
                    .header("Authorization", "Bearer " + BotConfig.getAuditApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(BotConfig.getAuditTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = auditHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                String content = json.path("choices").get(0).path("message").path("content").asText("S").trim();
                if (content.startsWith("S")) return "S";
                if (content.startsWith("C")) return "C";
                if (content.startsWith("N")) return "N";
                return "S"; // 默认不再生
            }
            logger.warn("Audit classify HTTP error: {}", resp.statusCode());
        } catch (Exception e) {
            logger.warn("Audit classify failed: {}", e.getMessage());
        }
        return "S"; // 出错时保守处理：不重新生成
    }

    /** 获取引用消息的文本上下文 */
    private String fetchReplyContext(Long replyMsgId, Main bot) {
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyMsgId);
            var future = bot.callOneBotApi("get_msg", params);
            var resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && resp.has("data")) {
                String repliedText = resp.path("data").path("raw_message").asText();
                if (!repliedText.isEmpty()) {
                    return "（对方正在回复这条消息：\"" + repliedText + "\"）";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String buildReplyContext(JsonNode msg, Main bot) {
        Long replyId = MessageUtil.extractReplyId(msg.path("message"));
        if (replyId == null) return "";
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyId);
            var future = bot.callOneBotApi("get_msg", params);
            var resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && resp.has("data")) {
                String repliedText = resp.path("data").path("raw_message").asText();
                if (!repliedText.isEmpty()) {
                    return "（对方正在回复这条消息：\"" + repliedText + "\"）";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void handlePrivateMessage(Main bot, JsonNode msg, long userId, String rawMessage, String plainText, String nickname, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        // 管理员私聊表情包管理命令：直达 service，绕过 LLM
        if (ToolAuthorizationService.getInstance().isAdmin(String.valueOf(userId))) {
            String adminResult = tryHandleAdminStickerCommand(bot, msg, String.valueOf(userId), rawMessage);
            if (adminResult != null) {
                bot.sendReply(msg, adminResult);
                return;
            }
        }
        String currentText = extractPrompt(rawMessage, plainText);
        String prompt = buildReplyContext(msg, bot) + currentText;
        String sessionId = "private_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty() && imageInfos.isEmpty()) prompt = EMPTY_MENTION_PROMPT;
        if (prompt.isEmpty()) prompt = "看一下这张图片";

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, null, nickname, Collections.emptyList(), imageInfos, linksToFetch, currentText);
    }

    private boolean isExplicitTrigger(JsonNode msg, String rawMessage) {
        return rawMessage.startsWith("#ai ") ||
                rawMessage.startsWith("!ai ") ||
                rawMessage.startsWith("！ai ") ||
                MessageUtil.isAt(msg.path("message"), BotConfig.getBotQq());
    }

    private String extractPrompt(String rawMessage, String plainText) {
        if (rawMessage.startsWith("#ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("!ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("！ai ")) return rawMessage.substring(5).trim();
        return plainText;
    }

    private boolean isClearCommand(String prompt) {
        return "#clear".equals(prompt) || "!clear".equals(prompt) || "！clear".equals(prompt);
    }


    private void replyWithAI(Main bot, JsonNode originalMsg, String sessionId, String userId, String prompt, String groupId, String nickname, List<Long> atUserIds, List<Map<String, String>> imageInfos, List<String> linksToFetch, String currentText) {
        groupExecutor.execute(sessionId, () -> {
            List<String> imageDataUris = downloadImages(imageInfos);
            String imageDesc = describeImagesWithCache(imageInfos, imageDataUris);
            String linkContext = buildLinkContext(linksToFetch);
            // 私聊图片也入库：groupId 传 null 表示"private"
            fireImageDescribed(null, userId, imageInfos, imageDesc);
            String fullPrompt = prompt;
            if (!imageDesc.isEmpty()) fullPrompt = fullPrompt + "\n\n" + imageDesc;
            if (!linkContext.isEmpty()) fullPrompt = fullPrompt + "\n\n" + linkContext;

            // 回填到 Main 在入站时创建的同一条记录，不在 generate() 里插入重复用户消息。
            if (!imageDesc.isEmpty()) {
                attachImageDescriptions(bot, "private", null, userId, imageInfos,
                        originalMsg.path("message_id").asText());
            }
            String reply = aiService.generate(sessionId, userId, fullPrompt, groupId, nickname, atUserIds,
                    BotConfig.isBaiLianMultimodalEnabled() ? imageDataUris : Collections.emptyList(),
                    currentText, !imageInfos.isEmpty());

            if (reply == null || reply.trim().isEmpty()) {
                logger.debug("AI 未返回可发送的私聊内容，sessionId={}", sessionId);
                return;
            }

            if (groupId != null) {
                long gId = Long.parseLong(groupId);
                // 第二阶段 2.2：传 sessionId 让 AI 回复写入与用户消息同 session
                sendSplitGroupReplies(bot, gId, reply, sessionId);

                String senderNick = originalMsg.path("sender").path("card").asText();
                if (senderNick.isEmpty()) senderNick = originalMsg.path("sender").path("nickname").asText();
                aiService.recordUserInteraction(groupId, userId, reply);
                } else {
                sendSplitPrivateReplies(bot, originalMsg, reply, sessionId);
            }
        });
    }

    /**
     * 将 AI 回复拆分为多条短消息，并逐条发送（带打字延迟）。
     * 第二阶段 2.2：传 sessionId，AI 回复写入与用户消息同一 session。
     */
    private boolean sendSplitGroupReplies(Main bot, long groupId, String fullReply, String sessionId) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        boolean sent = false;
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            }

            sent |= bot.sendGroupReply(groupId, msg, sessionId);
        }
        return sent;
    }

    /** 获取链接预览上下文，失败则返回空 */
    private String buildLinkContext(List<String> linksToFetch) {
        if (linksToFetch == null || linksToFetch.isEmpty()) return "";
        LinkPreviewService lps = new LinkPreviewService();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(linksToFetch.size(), 3);
        for (int i = 0; i < limit; i++) {
            String preview = lps.fetchPreview(linksToFetch.get(i));
            if (preview != null && !preview.isEmpty()) {
                sb.append(preview).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 触发 ImageDescribed 事件。每张图一个事件，StickerHarvesterListener 会异步入库。
     * 最多 3 张图，避免单条消息触发太多 Vision→入库 链路。
     */
    private void fireImageDescribed(String groupId, String userId, List<Map<String, String>> imageInfoMaps, String imageDesc) {
        if (imageInfoMaps == null || imageInfoMaps.isEmpty() || imageDesc == null || imageDesc.isEmpty()) return;
        int limit = Math.min(imageInfoMaps.size(), 3);
        for (int i = 0; i < limit; i++) {
            Map<String, String> image = imageInfoMaps.get(i);
            String url = imageSource(image);
            String description = image.get("description");
            if (url.isBlank() || description == null || description.isBlank()) continue;
            try {
                String senderId = image.get("sender_id");
                runtime.fire(new RuntimeEvent.ImageDescribed(groupId,
                        senderId == null || senderId.isBlank() ? userId : senderId,
                        url, description));
            } catch (Exception e) {
                logger.debug("fire ImageDescribed 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * Vision 描述（带 sticker-ingest 缓存复用）。每张图片单独命中/生成描述，并把结果写回
     * 同一个图片 map。这样下载失败、重复 URL、单条多图和多条缓冲消息都不会串图。
     */
    private String describeImagesWithCache(List<Map<String, String>> imageInfoMaps, List<String> imageDataUris) {
        if (imageInfoMaps == null || imageInfoMaps.isEmpty()) return "";
        int limit = Math.min(imageInfoMaps.size(), 3);
        StringBuilder result = new StringBuilder("[用户发送了").append(limit).append("张图片]");
        for (int i = 0; i < limit; i++) {
            Map<String, String> image = imageInfoMaps.get(i);
            image.remove("description");
            String url = imageSource(image);
            String description = null;

            try {
                if (url != null && !url.isBlank()) {
                    String cached = StickerIngestService.getInstance().tryGetVisionDescription(url);
                    if (isSingleImageDescription(cached)) {
                        description = extractSingleImageDescription(cached);
                        logger.debug("vision 描述命中 sticker-ingest 缓存: url={}", url);
                    }
                }
            } catch (Exception ignored) {
                // sticker-ingest 未初始化就降级到主视觉路径
            }

            String dataUri = imageDataUris != null && i < imageDataUris.size() ? imageDataUris.get(i) : "";
            if ((description == null || description.isBlank()) && dataUri != null && !dataUri.isBlank()) {
                String raw = aiService.describeImages(List.of(dataUri));
                description = extractSingleImageDescription(raw);
                if (description != null && !description.isBlank() && url != null && !url.isBlank()) {
                    try {
                        StickerIngestService.getInstance().cacheVisionDescription(url, description);
                    } catch (Exception ignored) {}
                }
            }

            if (description != null && !description.isBlank()) {
                description = description.trim();
                image.put("description", description);
                result.append("\n图片").append(i + 1);
                String messageId = image.get("message_id");
                if (messageId != null && !messageId.isBlank()) {
                    result.append("（消息ID=").append(messageId)
                            .append("，消息内第").append(parseImageIndex(image.get("image_index"), i + 1)).append("张");
                    String senderId = image.get("sender_id");
                    if (senderId != null && !senderId.isBlank()) result.append("，发送者=").append(senderId);
                    result.append("）");
                }
                result.append("内容：").append(description);
            } else {
                // Keep the image's identity in the prompt even when download/Vision failed;
                // otherwise a later image can be mistaken for this one.
                result.append("\n图片").append(i + 1);
                String messageId = image.get("message_id");
                if (messageId != null && !messageId.isBlank()) {
                    result.append("（消息ID=").append(messageId)
                            .append("，消息内第").append(parseImageIndex(image.get("image_index"), i + 1)).append("张");
                    String senderId = image.get("sender_id");
                    if (senderId != null && !senderId.isBlank()) result.append("，发送者=").append(senderId);
                    result.append("）");
                }
                result.append("内容：暂时无法读取原图，不能据此猜测内容");
            }
        }
        return result.toString();
    }

    /** 旧缓存可能保存过整段多图描述；这种值无法证明属于当前这张图，必须重新识别。 */
    private boolean isSingleImageDescription(String value) {
        if (value == null || value.isBlank()) return false;
        int labels = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("图片\\d+内容：").matcher(value);
        while (matcher.find()) labels++;
        return labels <= 1;
    }

    /** 从视觉模型的单图结果中取出正文，去掉数量和图片序号包装。 */
    private String extractSingleImageDescription(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?s)(?:^|\\n)图片\\d+内容：\\s*(.*?)(?=\\n图片\\d+内容：|$)")
                .matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        text = text.replaceFirst("^\\[用户发送了\\d+张图片]\\s*", "").trim();
        if (text.matches("图片\\d+内容：?\\s*")) return "";
        return text;
    }

    /** 把视觉描述按原入站消息分别回填，保留同一消息内的图片顺序。 */
    private void attachImageDescriptions(Main bot, String messageType, String groupId, String userId,
                                         List<Map<String, String>> imageInfoMaps, String fallbackMessageId) {
        if (imageInfoMaps == null || imageInfoMaps.isEmpty()) return;
        Map<String, List<Map<String, String>>> byMessage = new LinkedHashMap<>();
        for (Map<String, String> image : imageInfoMaps) {
            String messageId = image.get("message_id");
            if (messageId == null || messageId.isBlank()) messageId = fallbackMessageId;
            if (messageId == null || messageId.isBlank()) continue;
            byMessage.computeIfAbsent(messageId, ignored -> new ArrayList<>()).add(image);
        }
        for (Map.Entry<String, List<Map<String, String>>> entry : byMessage.entrySet()) {
            boolean hasDescription = entry.getValue().stream()
                    .anyMatch(image -> image.get("description") != null && !image.get("description").isBlank());
            if (!hasDescription) continue;
            String ownerId = entry.getValue().stream()
                    .map(image -> image.get("sender_id"))
                    .filter(sender -> sender != null && !sender.isBlank())
                    .findFirst()
                    .orElse(userId);
            bot.attachInboundImageData(messageType, groupId, ownerId, entry.getKey(),
                    buildImageDataJson(entry.getValue()));
        }
    }

    private static int parseImageIndex(String value) {
        return parseImageIndex(value, 0);
    }

    private static int parseImageIndex(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ===== 管理员表情包管理命令（私聊入口） =====

    private static final java.util.regex.Pattern ADMIN_STICKER_CMD =
            java.util.regex.Pattern.compile(
                    "^\\s*(fix|纠正|修改|set|list|列表|ls|remove|删除|delete)\\s+(sticker|表情包|贴纸)?\\s*(.*)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 解析管理员私聊表情包命令。返回结果消息；非命令返回 null（让上层走正常 LLM 流程）。
     *
     * <p>支持语法：
     * <pre>
     *   fix sticker a3f8e91c 关键词: 哈哈 裂开 崩溃
     *   list sticker 哈哈
     *   list                    （列出全部）
     *   remove sticker a3f8e91c
     * </pre>
     */
    private String tryHandleAdminStickerCommand(Main bot, JsonNode msg, String adminUserId, String rawMessage) {
        if (rawMessage == null) return null;
        java.util.regex.Matcher m = ADMIN_STICKER_CMD.matcher(rawMessage.trim());
        if (!m.find()) return null;
        String verb = m.group(1).toLowerCase();
        String rest = m.group(3) == null ? "" : m.group(3).trim();
        if (rest.isEmpty() && !verb.equals("list") && !verb.equals("ls") && !verb.equals("列表")) {
            return "用法：fix sticker <id> 关键词: a b c\n或：list sticker [过滤]\n或：remove sticker <id>";
        }
        try {
            StickerIngestService service = StickerIngestService.getInstance();
            switch (verb) {
                case "list":
                case "ls":
                case "列表": {
                    String filter = rest.isEmpty() ? null : rest;
                    java.util.List<StickerIngestService.StickerRecord> all =
                            filter != null ? service.searchByKeyword(filter) : service.getAllStickers();
                    if (all.isEmpty()) return filter != null ? "未找到匹配 " + filter + " 的 sticker" : "sticker 库为空";
                    StringBuilder sb = new StringBuilder("📦 sticker 库（").append(all.size()).append(" 条）\n");
                    int max = Math.min(all.size(), 20);
                    for (int i = 0; i < max; i++) {
                        StickerIngestService.StickerRecord r = all.get(i);
                        sb.append(String.format("- %s | file=%s | kw=[%s]%s\n",
                                r.id, r.file,
                                String.join(",", r.keywords),
                                r.correctedBy != null ? " (✏️by " + r.correctedBy + ")" : ""));
                    }
                    if (all.size() > max) sb.append("...还有 ").append(all.size() - max).append(" 条");
                    return sb.toString().trim();
                }
                case "remove":
                case "删除":
                case "delete": {
                    String id = rest.split("\\s+")[0].trim();
                    return service.remove(id);
                }
                case "fix":
                case "纠正":
                case "修改":
                case "set": {
                    // 解析 <id> 关键词: a b c
                    String[] parts = rest.split("\\s+", 2);
                    if (parts.length < 1 || parts[0].isEmpty()) return "缺少 sticker_id";
                    String id = parts[0];
                    String kwRaw = parts.length > 1 ? parts[1] : "";
                    // 兼容 "关键词: a b c" / "kw: a b c" / "a b c"
                    String stripped = kwRaw.replaceFirst("^(?:关键词|kw|keywords)\\s*[:：]\\s*", "");
                    java.util.List<String> kws = StickerIngestService.parseKeywords(stripped);
                    if (kws.isEmpty()) return "缺少 keywords";
                    return service.correctKeywords(id, kws, adminUserId);
                }
                default:
                    return null;
            }
        } catch (IllegalStateException e) {
            return "StickerIngestService 未初始化（系统未就绪）";
        } catch (Exception e) {
            logger.warn("管理员命令执行失败: {}", e.getMessage(), e);
            return "执行失败: " + e.getMessage();
        }
    }

    /** 下载图片并转为 base64 data URI，失败时保留空位，避免后续图片序号前移。 */
    private List<String> downloadImages(List<Map<String, String>> imageInfos) {
        List<String> uris = new ArrayList<>();
        if (imageInfos == null || imageInfos.isEmpty()) return uris;
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            String url = imageSource(imageInfos.get(i));
            if (url.isBlank()) {
                uris.add("");
                continue;
            }
            String dataUri = ImageUtils.downloadImageAsBase64DataUri(url);
            uris.add(dataUri == null ? "" : dataUri);
        }
        return uris;
    }

    private String buildImageDataJson(List<Map<String, String>> imageInfos) {
        if (imageInfos == null || imageInfos.isEmpty()) return null;
        ArrayNode entries = JsonNodeFactory.instance.arrayNode();
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            Map<String, String> image = imageInfos.get(i);
            ObjectNode entry = entries.addObject();
            entry.put("url", imageSource(image));
            entry.put("file", image.getOrDefault("file", ""));
            entry.put("desc", image.getOrDefault("description", ""));
            entry.put("image_index", parseImageIndex(image.get("image_index"), i + 1));
            String messageId = image.get("message_id");
            if (messageId != null && !messageId.isBlank()) entry.put("message_id", messageId);
        }
        return entries.toString();
    }

    /** Only HTTP(S) provider URLs are safe for the inbound Vision/download path. */
    private static String imageSource(Map<String, String> image) {
        if (image == null) return "";
        String url = image.get("url");
        if (isHttpUrl(url)) return url.trim();
        String file = image.get("file");
        return isHttpUrl(file) ? file.trim() : "";
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    /** 私聊同样拆分，避免一大段砸过去 */
    private void sendSplitPrivateReplies(Main bot, JsonNode originalMsg, String fullReply, String sessionId) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendReply(originalMsg, msg, sessionId);
        }
    }

    // ===== rate_limited 快速记忆查询（不发 LLM） =====

    /** 判断消息是否含"你还记得..."这种记忆查询意图 */
    private static boolean isMemoryQueryIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        for (String kw : MEMORY_QUERY_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * rate_limited 快路径：调 DB 查 1 条最相关记忆，命中就生成短回复。
     * 仅查 DB 不调 LLM，QPS 高也无所谓。
     * @return 短回复文本；查不到或非查询类则返回 null（让上层走原沉默逻辑）
     */
    private String tryFastMemoryReply(String uid, String gid, String text) {
        if (!isMemoryQueryIntent(text)) return null;
        try {
            String kw = null;
            try {
                List<String> kws = HanLP.extractKeyword(text, 3);
                if (kws != null && !kws.isEmpty()) kw = kws.get(0);
            } catch (Exception ignored) {}
            java.util.List<LongTermMemory> results = memoryRepo.search(uid, gid, kw, 1, null, null);
            if (results.isEmpty()) return null;
            LongTermMemory m = results.get(0);
            try { memoryRepo.markRecalled(m.getId()); } catch (Exception ignored) {}
            if (m.getSourceMessageId() == null) return null;
            var source = memoryRepo.findSource(m.getSourceMessageId(), uid, gid,
                    java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
            if (source.isEmpty() || source.get().text().length() > 200 || source.get().text().contains("[CQ:")) return null;
            return "你之前说过：“" + source.get().text() + "”";
        } catch (Exception e) {
            logger.debug("fast memory recall failed: {}", e.getMessage());
            return null;
        }
    }
}
