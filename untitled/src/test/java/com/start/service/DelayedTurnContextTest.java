package com.start.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DelayedTurnContextTest {
    @Test void refreshRequiresBothDelayAndNewMessagesAndKeepsOriginalRequester() {
        var message = new BaiLianService.PublicMessage("102", "other-user", "乙", "新话题");
        long started = message.timestamp - 1000;
        assertEquals("", BaiLianService.delayedTurnContext(List.of(message), "original-user", Set.of("101"), started, started + 10000));
        assertEquals("", BaiLianService.delayedTurnContext(List.of(message), "original-user", Set.of("101"), message.timestamp + 1, started + 120000));
        String context = BaiLianService.delayedTurnContext(List.of(message), "original-user", Set.of("101"), started, started + 120000);
        assertTrue(context.contains("QQ=original-user"));
        assertTrue(context.contains("新话题") && context.contains("other-user"));
        assertTrue(context.contains("不因迟到自动使用 QQ 引用"));
        assertTrue(context.contains("对象已经清楚就不补"));
    }
}
