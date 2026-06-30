package com.start.model;

import com.start.service.ConversationEvent;

/** 决策输入冻结快照。Replay 时可直接使用，不需要重新查询 runtime 状态。 */
public record DecisionContext(
        ConversationEvent event,
        long generation,
        long revision,
        boolean allowSilence,
        int messagesLast30s,
        int aiMessagesLast5m) {

    public static DecisionContext of(ConversationEvent event, long generation, long revision,
                                     boolean allowSilence, int msgs30s, int aiMsgs5m) {
        return new DecisionContext(event, generation, revision, allowSilence, msgs30s, aiMsgs5m);
    }
}
