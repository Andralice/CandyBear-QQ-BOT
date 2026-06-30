package com.start.runtime.conversation;

import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 对话生命周期协调器。不包含业务 if/else，只负责阶段调度和事件分发。
 */
public class ConversationRuntime {
    private static final Logger logger = LoggerFactory.getLogger(ConversationRuntime.class);

    private final ConversationRuntimeConfig config;
    private final ConversationInterpreter interpreter;
    private final BaiLianService generator;
    private final ConversationManager conversationManager;
    private final GroupSerialExecutor groupExecutor;
    private final List<RuntimeListener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, Long> lastReactionTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGroupReplyTime = new ConcurrentHashMap<>();

    public ConversationRuntime(ConversationRuntimeConfig config,
                               ConversationInterpreter interpreter,
                               BaiLianService generator,
                               ConversationManager conversationManager,
                               GroupSerialExecutor groupExecutor) {
        this.config = config;
        this.interpreter = interpreter;
        this.generator = generator;
        this.conversationManager = conversationManager;
        this.groupExecutor = groupExecutor;
    }

    public void addListener(RuntimeListener l) { listeners.add(l); }

    /** 入口：处理一条群消息。构建 Session → 解释 → 速率 → 生成 → 提交。 */
    public void handleGroupMessage(GroupMessage msg) {
        String gid = String.valueOf(msg.groupId());
        String uid = String.valueOf(msg.userId());
        long now = System.currentTimeMillis();

        fire(new RuntimeEvent.MessageReceived(gid, uid, msg.plainText()));

        // 缓冲到 ConversationState
        ConversationState conv = conversationManager.getOrCreate(gid, uid);
        conv.addMessage(msg.plainText());
        for (Map<String, String> img : msg.imageInfos()) {
            conv.addImageInfo(img.get("url"), img.get("file"));
        }
        for (String link : msg.linksToFetch()) {
            conv.addLink(link);
        }
        if (msg.replyToMessageId() != null) conv.setReplyToMessageId(msg.replyToMessageId());
        long revision = conv.incrementRevision();

        // 解释事件
        ConversationInterpreter.InterpretResult result = interpreter.interpret(
                gid, uid, msg.senderNick(), msg.plainText(), msg.ats());
        fire(new RuntimeEvent.Interpreted(gid, uid, result.event(), result));

        if (result.isNothing()) return;

        // 速率控制
        if (result.event() != ConversationEvent.PASSIVE_TRIGGER) {
            if (!interpreter.canReact(gid)) return;
        }
        Long lastGroup = lastGroupReplyTime.get(gid);
        if (lastGroup != null && now - lastGroup < config.groupReplyCooldown().toMillis()) return;
        String userKey = gid + "_" + msg.userId();
        Long lastUser = lastReactionTime.get(userKey);
        if (lastUser != null && now - lastUser < config.userReplyCooldown().toMillis()) return;

        lastReactionTime.put(userKey, now);
        lastGroupReplyTime.put(gid, now);
        interpreter.recordReaction(gid);

        // 直接回复（被动触发）
        if (result.isDirect()) {
            fire(new RuntimeEvent.CommitFinished(gid, uid, null, 0));
            return;
        }

        // 构建 Session
        long startMs = System.currentTimeMillis();
        long gen = conv.incrementGeneration();
        boolean allowSilence = config.allowSilence() && result.event().allowsSilence();

        ConversationSession session = ConversationSession.of(gid, uid, msg.nickname())
                .userPrompt(result.prompt())
                .atUserIds(msg.ats())
                .allowSilence(allowSilence)
                .generation(gen)
                .revision(revision)
                .event(result.event())
                .startMs(startMs)
                .build();

        // 异步生成
        groupExecutor.execute(gid, () -> {
            GenerationResult genResult = generator.generate(session);
            long elapsed = System.currentTimeMillis() - startMs;
            session.complete(genResult, elapsed);
            fire(new RuntimeEvent.GenerationFinished(gid, uid, genResult));
            fire(new RuntimeEvent.CommitFinished(gid, uid, genResult, elapsed));
        });
    }

    private void fire(RuntimeEvent e) {
        for (RuntimeListener l : listeners) {
            try { l.onEvent(e); } catch (Exception ex) {
                logger.warn("Listener {} failed: {}", l.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }
}
