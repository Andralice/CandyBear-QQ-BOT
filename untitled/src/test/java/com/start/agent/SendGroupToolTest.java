package com.start.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SendGroupToolTest {
    @Test
    void currentGroupCannotBeUsedAsAnExtraReplyChannel() {
        SendGroupTool tool = new SendGroupTool(null, "user", "1079312807");

        String result = tool.execute(Map.of(
                "group_id", "1079312807",
                "message", "重复回复"));

        assertTrue(result.startsWith("拒绝发送"));
    }
}
