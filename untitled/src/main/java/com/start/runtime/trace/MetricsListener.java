package com.start.runtime.trace;

import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.ConversationMetrics;

/** 将 Runtime 消息/回复事件转发到 ConversationMetrics。 */
public class MetricsListener implements RuntimeListener {
    private final ConversationMetrics metrics;

    public MetricsListener(ConversationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onEvent(RuntimeEvent e) {
        if (e instanceof RuntimeEvent.MessageReceived m) {
            metrics.recordMessage(m.groupId(), m.userId());
        } else if (e instanceof RuntimeEvent.CommitFinished f) {
            if (f.result() != null && f.result().shouldSend()) {
                metrics.recordAiReply(f.groupId());
            }
        }
    }
}
