package com.start.service;

import java.util.*;

import com.start.config.BotConfig;
import com.start.runtime.conversation.ConversationStateStore;

/**
 * 对话事件识别器。依赖 ConversationStateStore + 行为服务，不依赖 BaiLianService。
 */
public class ConversationInterpreter {

    private final ConversationStateStore stateStore;
    private final AIDatabaseService aiDatabaseService;

    public ConversationInterpreter(ConversationStateStore stateStore,
                                   AIDatabaseService aiDatabaseService) {
        this.stateStore = stateStore;
        this.aiDatabaseService = aiDatabaseService;
    }

    /** 识别当前消息触发的对话事件 */
    public InterpretResult interpret(String groupId, String userId, String nickname,
                                      String message, List<Long> ats) {
        if (userId.equals(String.valueOf(BotConfig.getBotQq()))) {
            return InterpretResult.NOTHING;
        }

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;

        stateStore.purgeExpiredAwaits();

        // 1. 追问检测（120s 窗口）
        ConversationStateStore.UserThread thread = stateStore.getUserThread(fullUserId);
        if (thread != null && now - thread.lastInteraction() < 120_000) {
            if (isFollowUpMessage(message)) {
                stateStore.removePendingAwait(fullUserId); // cancel await
                String cleanReply = normalizeReplyForContext(thread.lastBotReply());
                String prompt = "你之前说：" + cleanReply + "\n对方现在说：" + message + "\n请用一句自然的话回应。";
                return InterpretResult.of(ConversationEvent.FOLLOW_UP, prompt);
            }
        }

        // 2. 异步等待回复
        ConversationStateStore.PendingAwait await = stateStore.removePendingAwait(fullUserId);
        if (await != null && !await.isExpired()) {
            String prompt = "你之前问了" + await.targetNickname + "(" + await.targetUserId + "): " + await.question
                    + "\n你想了解的是: " + await.context
                    + "\n\nTA的回复是: " + message
                    + "\n\n请根据TA的回复自然地继续对话。";
            return InterpretResult.of(ConversationEvent.AWAIT_REPLY, prompt);
        }

        // 3. 概率性主动插话
        Map<String, Object> personality = aiDatabaseService.getCandyBearPersonality();
        @SuppressWarnings("unchecked")
        Map<String, Object> activeReply = (Map<String, Object>) personality.get("activeReply");
        double baseProbability = (double) activeReply.get("baseProbability");
        if (baseProbability >= 0.5) {
            if (aiDatabaseService.shouldJoinTopic(message, groupId)) {
                String prompt = "群友说：" + message + "\n作为糖果熊，请用一句话自然回应。不要长篇大论，不要分析。\n\n如果你觉得没必要回复（比如话题已经过去了、别人在聊别的、你刚说过话），只输出 <NO_REPLY>，不要输出任何其他内容。";
                return InterpretResult.of(ConversationEvent.PROBABILISTIC, prompt);
            }
        }

        // 4. AI 发言被评论
        Deque<ConversationStateStore.ContextEvent> events = stateStore.getGroupContext(groupId);
        if (events != null && !events.isEmpty()) {
            Optional<ConversationStateStore.ContextEvent> lastAi = events.stream()
                    .filter(e -> "ai_reply".equals(e.type()))
                    .reduce((first, second) -> second);
            if (lastAi.isPresent() && now - lastAi.get().timestamp() < 180_000) {
                if (isResponseToAIMessage(message, lastAi.get().content())) {
                    String cleanReply = normalizeReplyForContext(lastAi.get().content());
                    String prompt = "你之前说：" + cleanReply + "\n另一个群友评论：" + message + "\n请友好地回应。";
                    return InterpretResult.of(ConversationEvent.AI_COMMENTED, prompt);
                }
            }
        }

        // 5. 被动触发
        Optional<String> passive = checkPassiveReactions(message);
        if (passive.isPresent()) {
            return InterpretResult.direct(ConversationEvent.PASSIVE_TRIGGER, passive.get());
        }

        return InterpretResult.NOTHING;
    }

    /** 速率控制 — 委托 StateStore */
    public boolean canReact(String groupId) { return stateStore.canReact(groupId); }
    public void recordReaction(String groupId) { stateStore.recordReaction(groupId); }

    /** 是否为追问消息 */
    boolean isFollowUpMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) return false;
        String text = msg.trim();
        int len = text.length();
        if (len > 60) return false;
        String lower = text.toLowerCase();
        if (text.contains("？") || text.contains("?")) return true;
        String[] qks = {"为什么", "怎么会", "怎么", "为何", "咋", "啥", "什么", "谁",
                "呢", "吗", "嘛", "么", "是不是", "对不对", "行不行",
                "然后", "接着", "再", "继续", "后来", "下一步",
                "你觉得", "你认为", "你说", "你刚", "你之前", "你刚刚",
                "我能不能", "我可以", "能不能", "可不可以", "给我"};
        for (String kw : qks) if (lower.contains(kw)) return true;
        if ((text.startsWith("你") || text.startsWith("我") || text.startsWith("我们")) && len <= 20) {
            if (lower.contains("觉得") || lower.contains("认为") || lower.contains("喜欢")
                    || lower.contains("知道") || lower.contains("记得") || lower.contains("想")
                    || lower.contains("在") || lower.contains("是")
                    || lower.endsWith("呢") || lower.endsWith("啊") || lower.endsWith("呀")) return true;
        }
        if (text.matches("(?i)^(嗯+|哦+|啊+|呃+|额+|诶+|好+|行+|对+|哈哈+|嘻嘻+|嘿嘿+|呜+|唉+)[~～!！?？]*$")) return true;
        if ((lower.startsWith("那") || lower.startsWith("所以") || lower.startsWith("不过")) && len <= 25) return true;
        if (len <= 2 && (text.equals("呢") || text.equals("啊") || text.equals("哦") || text.equals("？"))) return true;
        if (lower.contains("你") && (lower.contains("擅长") || lower.contains("会") || lower.contains("能")
                || lower.contains("喜欢") || lower.contains("性格") || lower.contains("是什么")
                || lower.contains("介绍一下") || lower.contains("说说"))) return true;
        return false;
    }

    /** 是否在回应 AI 之前的发言 */
    boolean isResponseToAIMessage(String userMsg, String aiMsg) {
        if (userMsg.length() > 50) return false;
        String lower = userMsg.toLowerCase();
        return lower.contains("不对") || lower.contains("错") || lower.contains("为什么")
                || lower.contains("怎么") || lower.contains("接着") || lower.contains("继续")
                || lower.contains("同意") || lower.contains("觉得") || lower.contains("你说")
                || lower.contains("刚刚") || lower.contains("回应") || lower.contains("回复")
                || (lower.contains("你") && userMsg.length() <= 20);
    }

    /** 被动触发检测（红包、音乐等） */
    Optional<String> checkPassiveReactions(String message) {
        String lower = message.toLowerCase();
        if (message.contains("[CQ:redbag")) return Optional.of("诶？有红包？手慢无啊...");
        if (message.contains("[CQ:music") || lower.contains("网易云") || lower.contains("music.163"))
            return Optional.of("这首歌我也听过，挺不错的～");
        return Optional.empty();
    }

    /** 清理回复中的分隔符 */
    static String normalizeReplyForContext(String raw) {
        if (raw == null) return "";
        return raw.replace("|---|", "\n").replaceAll("\\n{2,}", "\n").trim();
    }

    // ===== InterpretResult =====

    public record InterpretResult(ConversationEvent event, String prompt, String directReply) {
        public static final InterpretResult NOTHING = new InterpretResult(ConversationEvent.NOTHING, null, null);
        public static InterpretResult of(ConversationEvent e, String p) { return new InterpretResult(e, p, null); }
        public static InterpretResult direct(ConversationEvent e, String d) { return new InterpretResult(e, null, d); }
        public boolean needsAi() { return prompt != null; }
        public boolean isDirect() { return directReply != null; }
        public boolean isNothing() { return event == ConversationEvent.NOTHING; }
    }
}
