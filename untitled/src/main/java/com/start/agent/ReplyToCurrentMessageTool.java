package com.start.agent;

import java.util.List;
import java.util.Map;

/** Marks the first message in this group turn to quote the current inbound message. */
public final class ReplyToCurrentMessageTool implements Tool {
    public static final String NAME = "reply_to_current_message";

    @Override
    public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "默认不要调用。仅当最近有多条可能回应的消息，而不引用会让群友看不懂你在回应哪一条时调用；如果你是在顺着最近一条已经明确的内容继续说，禁止调用。只标记本轮第一条群消息使用QQ引用，不发送消息。调用后仍需提交 group_turn_result。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(),
                "required", List.of(), "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return "已标记本轮第一条群消息引用当前消息；现在单独调用 group_turn_result 提交最终决定。";
    }
}
