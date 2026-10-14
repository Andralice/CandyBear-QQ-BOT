package com.start.model;

import com.start.service.ConversationEvent;
import java.util.List;

/** 决策输入冻结快照。Replay 时可直接使用，不需要重新查询 runtime 状态。 */
public record DecisionContext(
        ConversationEvent event,
        long generation,
        long revision,
        boolean allowSilence,
        int messagesLast30s,
        int aiMessagesLast5m,
        List<String> inputMessageIds,
        String replyToMessageId) {

    public DecisionContext {
        inputMessageIds = inputMessageIds == null ? List.of() : List.copyOf(inputMessageIds);
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
    }

    /** 兼容较早的回放调用方。 */
    public DecisionContext(ConversationEvent event, long generation, long revision,
                           boolean allowSilence, int messagesLast30s, int aiMessagesLast5m) {
        this(event, generation, revision, allowSilence, messagesLast30s, aiMessagesLast5m,
                List.of(), "");
    }

    public static DecisionContext of(ConversationEvent event, long generation, long revision,
                                     boolean allowSilence, int msgs30s, int aiMsgs5m) {
        return new DecisionContext(event, generation, revision, allowSilence, msgs30s, aiMsgs5m);
    }

    public static DecisionContext of(ConversationEvent event, long generation, long revision,
                                     boolean allowSilence, int msgs30s, int aiMsgs5m,
                                     List<String> inputMessageIds, String replyToMessageId) {
        return new DecisionContext(event, generation, revision, allowSilence, msgs30s, aiMsgs5m,
                inputMessageIds, replyToMessageId);
    }
}
