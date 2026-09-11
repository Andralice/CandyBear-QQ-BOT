package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only bounded context query. */
public final class QueryMessageContextTool extends EvidenceToolSupport implements Tool {
    public QueryMessageContextTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                   String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_message_context"; }
    @Override public String getDescription() { return "查询当前会话附近的原始消息上下文。结果含 message_id、时间、发送者、来源范围和 availability_status；最多20条，不能跨群。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("message_id", Map.of("type", "string", "description", "中心消息的本地 message_id；缺省使用当前会话最新消息"));
        p.put("cursor", Map.of("type", "string", "description", "上一页 next_cursor；缺省0"));
        p.put("limit", Map.of("type", "integer", "description", "最多20，默认12"));
        p.put("group_id", Map.of("type", "string", "description", "只能填写当前会话群号"));
        return Map.of("type", "object", "properties", p);
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        try {
            long messageId = parseLong(args.get("message_id"));
            if (messageId == 0) {
                var latest = evidenceRepository.messageContext(currentGroupId, currentSessionId, Long.MAX_VALUE, 0, 1);
                if (latest.isEmpty()) return render("message_context", List.of(), 1200);
                messageId = ((Number) latest.get(0).get("id")).longValue();
            }
            return render("message_context", evidenceRepository.messageContext(currentGroupId, currentSessionId,
                    messageId, parseLong(args.get("cursor")), limit(args.get("limit"), 12, 20)), 1200, true);
        } catch (Exception e) { return error("查询消息上下文失败"); }
    }
    private long parseLong(Object value) { try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); } catch (Exception e) { return 0; } }
}
