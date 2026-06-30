package com.start.service;

import java.util.*;

import com.start.config.BotConfig;

/**
 * 对话事件识别器。Java 只负责识别"当前发生了什么事件"，LLM 负责决定"怎么做"。
 * 当前委托 BaiLianService 的内部状态，后续逐步将状态迁移至此。
 */
public class ConversationInterpreter {

    private final BaiLianService ai;

    public ConversationInterpreter(BaiLianService ai) {
        this.ai = ai;
    }

    /** 识别当前消息触发的对话事件，不做频率控制 */
    public InterpretResult interpret(String groupId, String userId, String nickname,
                                      String message, List<Long> ats) {
        if (userId.equals(String.valueOf(BotConfig.getBotQq()))) {
            return InterpretResult.NOTHING;
        }

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;

        ai.purgeExpiredAwaits();

        // 1. 追问检测（120s 窗口）
        BaiLianService.UserThread thread = ai.getUserThread(fullUserId);
        if (thread != null && now - thread.lastInteraction < 120_000) {
            if (ai.isFollowUpMessage(message)) {
                ai.cancelPendingAwait(groupId, userId);
                String cleanReply = ai.normalizeReplyForContext(thread.lastBotReply);
                String prompt = "你之前说：" + cleanReply + "\n对方现在说：" + message + "\n请用一句自然的话回应。";
                return InterpretResult.of(ConversationEvent.FOLLOW_UP, prompt);
            }
        }

        // 2. 异步等待回复
        BaiLianService.PendingAwait await = ai.removePendingAwait(fullUserId);
        if (await != null && !await.isExpired()) {
            String prompt = "你之前问了" + await.targetNickname + "(" + await.targetUserId + "): " + await.question
                    + "\n你想了解的是: " + await.context
                    + "\n\nTA的回复是: " + message
                    + "\n\n请根据TA的回复自然地继续对话.如果TA回答了你的问题就顺着聊下去,如果TA没回答或敷衍也别追问了.";
            return InterpretResult.of(ConversationEvent.AWAIT_REPLY, prompt);
        }

        // 3. 概率性主动插话
        BehaviorAnalyzer.BehaviorAdvice advice = ai.getBehaviorAdvice(groupId);
        double effectiveProbability = advice.adjustedProbability;
        if (effectiveProbability >= 0.15) {
            Map<String, Object> personality = ai.getCandyBearPersonality();
            @SuppressWarnings("unchecked")
            Map<String, Object> activeReply = (Map<String, Object>) personality.get("activeReply");
            double baseProbability = (double) activeReply.get("baseProbability");
            if (baseProbability >= 0.5) {
                if (ai.shouldJoinTopic(message, groupId)) {
                    String prompt = "群友说：" + message + "\n作为糖果熊，请用一句话自然回应。不要长篇大论，不要分析。\n\n如果你觉得没必要回复（比如话题已经过去了、别人在聊别的、你刚说过话），只输出 <NO_REPLY>，不要输出任何其他内容。";
                    return InterpretResult.of(ConversationEvent.PROBABILISTIC, prompt);
                }
            }
        }

        // 4. AI 发言被评论
        Deque<BaiLianService.ContextEvent> events = ai.getGroupContext(groupId);
        if (events != null && !events.isEmpty()) {
            Optional<BaiLianService.ContextEvent> lastAi = events.stream()
                    .filter(e -> "ai_reply".equals(e.type))
                    .reduce((first, second) -> second);
            if (lastAi.isPresent() && now - lastAi.get().timestamp < 180_000) {
                if (ai.isResponseToAIMessage(message, lastAi.get().content)) {
                    String cleanReply = ai.normalizeReplyForContext(lastAi.get().content);
                    String prompt = "你之前说：" + cleanReply + "\n另一个群友评论：" + message + "\n请友好地回应。";
                    return InterpretResult.of(ConversationEvent.AI_COMMENTED, prompt);
                }
            }
        }

        // 5. 被动触发（红包、音乐等）
        Optional<String> passive = ai.checkPassiveReactions(groupId, message);
        if (passive.isPresent()) {
            return InterpretResult.direct(ConversationEvent.PASSIVE_TRIGGER, passive.get());
        }

        return InterpretResult.NOTHING;
    }

    // ===== InterpretResult =====

    public record InterpretResult(ConversationEvent event, String prompt, String directReply) {
        public static final InterpretResult NOTHING = new InterpretResult(ConversationEvent.NOTHING, null, null);

        public static InterpretResult of(ConversationEvent event, String prompt) {
            return new InterpretResult(event, prompt, null);
        }

        public static InterpretResult direct(ConversationEvent event, String directReply) {
            return new InterpretResult(event, null, directReply);
        }

        public boolean needsAi() { return prompt != null; }
        public boolean isDirect() { return directReply != null; }
        public boolean isNothing() { return event == ConversationEvent.NOTHING; }
    }
}
