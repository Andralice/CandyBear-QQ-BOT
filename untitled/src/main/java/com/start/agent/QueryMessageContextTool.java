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
    @Override public String getDescription() { return "查询当前会话附近的原始消息上下文。结果含本地 id、OneBot message_id、时间、发送者、来源范围和 availability_status；最多20条，不能跨群。需要重新确认上次读取后新到的消息时，使用 after_message_id；消息明显未说完或需要等群友补充时，可同时传 wait_ms，最多等待4秒后再读。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("message_id", Map.of("type", "string", "description", "中心消息的本地 message_id；缺省使用当前会话最新消息"));
        p.put("after_message_id", Map.of("type", "string", "description", "上次结果中的本地 id；只返回此 id 之后新到的消息，按时间正序"));
        p.put("wait_ms", Map.of("type", "integer", "description", "after_message_id 结果为空时最多等待毫秒数，默认0，最多4000；仅在确实需要等新消息时使用"));
        p.put("cursor", Map.of("type", "string", "description", "上一页 next_cursor；缺省0"));
        p.put("limit", Map.of("type", "integer", "description", "最多20，默认12"));
        p.put("group_id", Map.of("type", "string", "description", "只能填写当前会话群号"));
        return Map.of("type", "object", "properties", p);
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        try {
            long afterMessageId = parseLong(args.get("after_message_id"));
            if (afterMessageId > 0) {
                int limit = limit(args.get("limit"), 12, 20);
                int waitMs = (int) Math.min(4000L, Math.max(0L, parseLong(args.get("wait_ms"))));
                long deadline = System.currentTimeMillis() + waitMs;
                List<Map<String, Object>> rows;
                do {
                    rows = evidenceRepository.messageContextAfter(currentGroupId, currentSessionId,
                            afterMessageId, limit);
                    if (!rows.isEmpty() || waitMs == 0) break;
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        Thread.sleep(Math.min(200L, remaining));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } while (true);
                return render("message_stream", rows, 1200);
            }
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
