package com.start.runtime.trace;

import com.start.model.DecisionTrace;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将 Runtime 事件转为 DecisionTrace 日志。 */
public class DecisionTraceListener implements RuntimeListener {
    private static final Logger logger = LoggerFactory.getLogger("com.start.decision");

    @Override
    public void onEvent(RuntimeEvent e) {
        if (e instanceof RuntimeEvent.CommitFinished f) {
            DecisionTrace trace = DecisionTrace.from(f);
            logger.info(trace.toLogLine());
        }
    }
}
