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
        return interpret(groupId, userId, nickname, message, message, ats);
    }

    /**
     * 识别当前消息触发的对话事件，并保留 raw_message 供协议型事件识别。
     * 纯文本用于生成自然语言提示，raw_message 只用于识别红包/音乐等 CQ 消息。
     */
    public InterpretResult interpret(String groupId, String userId, String nickname,
                                     String message, String rawMessage, List<Long> ats) {
        if (userId.equals(String.valueOf(BotConfig.getBotQq()))) {
            return InterpretResult.NOTHING;
        }

        String text = message == null ? "" : message;
        String protocolText = rawMessage == null ? text : rawMessage;

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;

        stateStore.purgeExpiredAwaits();

        boolean atsOthers = ats != null && ats.stream().anyMatch(q -> q != BotConfig.getBotQq());

        // 1. 追问检测（120s 窗口）
        ConversationStateStore.UserThread thread = stateStore.getUserThread(fullUserId);
        if (!atsOthers && thread != null && now - thread.lastInteraction() < 120_000) {
            if (isFollowUpMessage(text)) {
                stateStore.removePendingAwait(fullUserId); // cancel await
                String cleanReply = normalizeReplyForContext(thread.lastBotReply());
                String prompt = "你之前说：" + cleanReply + "\n对方现在说：" + text
                        + "\n结合上下文接住这次补充；闲聊可以简短，认真问题或明确求助时把需要说清楚，不必机械追问。";
                return InterpretResult.of(ConversationEvent.FOLLOW_UP, prompt);
            }
        }

        // 2. 异步等待回复
        ConversationStateStore.PendingAwait await = stateStore.removePendingAwait(fullUserId);
        if (await != null && !await.isExpired()) {
            String prompt = "你之前问了" + await.targetNickname + "(" + await.targetUserId + "): " + await.question
                    + "\n你想了解的是: " + await.context
                    + "\n\nTA的回复是: " + text
                    + "\n\n请根据TA的回复自然地继续对话。";
            return InterpretResult.of(ConversationEvent.AWAIT_REPLY, prompt);
        }

        // 3. 概率性主动插话（消息 @ 了别人则跳过，不插嘴）
        if (atsOthers) {
            return InterpretResult.NOTHING;
        }

        Map<String, Object> personality = aiDatabaseService.getCandyBearPersonality();
        @SuppressWarnings("unchecked")
        Map<String, Object> activeReply = (Map<String, Object>) personality.get("activeReply");
        double baseProbability = (double) activeReply.get("baseProbability");
        if (baseProbability >= 0.5) {
            if (aiDatabaseService.shouldJoinTopic(text, groupId)) {
                String prompt = "群友说：" + text
                        + "\n结合当前话题、对象和前后对话自然回应：闲聊接住就停，明确问题或求助时把需要说清楚；"
                        + "不要把倾诉自动变成劝导，也不必机械分析、追问或建议。"
                        + "\n如果没有与你有关且值得补充的内容（例如话题已过去、其他人正处理、你刚回过），"
                        + "只输出 <NO_REPLY>，不输出任何其他内容。";
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
                if (isResponseToAIMessage(text, lastAi.get().content())) {
                    String cleanReply = normalizeReplyForContext(lastAi.get().content());
                    String prompt = "你之前说：" + cleanReply + "\n另一个群友评论：" + text
                            + "\n结合上下文回应这句评论；需要时解释或修正，不必为了友好而机械附和。";
                    return InterpretResult.of(ConversationEvent.AI_COMMENTED, prompt);
                }
            }
        }

        // 5. 被动触发
        Optional<String> passive = checkPassiveReactions(protocolText);
        if (passive.isPresent()) {
            return InterpretResult.of(ConversationEvent.PASSIVE_TRIGGER, passive.get());
        }

        return InterpretResult.NOTHING;
    }

    /** 速率控制 — 委托 StateStore */
    public boolean canReact(String groupId) { return stateStore.canReact(groupId); }
    public void recordReaction(String groupId) { stateStore.recordReaction(groupId); }
    public boolean isNegativeCooldown(String groupId) { return stateStore.isNegativeCooldown(groupId); }
    public void setNegativeCooldown(String groupId, long durationMs) {
        stateStore.setNegativeCooldown(groupId, durationMs);
    }

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

    /** 被动触发检测（红包、音乐等）。返回给模型的语义提示，不是用户可见话术。 */
    Optional<String> checkPassiveReactions(String message) {
        String text = message == null ? "" : message;
        String lower = text.toLowerCase();
        if (text.contains("[CQ:redbag")) {
            return Optional.of("群里刚出现了一个红包消息。结合当前群聊自然接住这件事；不要编造红包金额、发送者、是否抢到或其他不可见结果，是否拆成多条由你根据真实想法决定。");
        }
        if (text.contains("[CQ:music") || lower.contains("网易云") || lower.contains("music.163"))
            return Optional.of("群里刚分享了一首音乐。结合可见的歌名、链接和群友评论自然回应；除非有证据，不要声称自己听过或编造歌曲细节，是否拆成多条由你根据真实想法决定。");
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
