package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only reply/reference chain lookup. */
public final class QueryThreadTool extends EvidenceToolSupport implements Tool {
    public QueryThreadTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                           String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_thread"; }
    @Override public String getDescription() { return "查询当前会话中某条消息的回复/引用关系。只能按当前群或当前私聊范围读取，最多30条。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("provider_message_id", Map.of("type", "string", "description", "OneBot message_id"));
        p.put("cursor", Map.of("type", "string", "description", "上一页游标"));
        p.put("limit", Map.of("type", "integer", "description", "最多30，默认20"));
        p.put("group_id", Map.of("type", "string", "description", "只能填写当前会话群号"));
        return Map.of("type", "object", "properties", p, "required", List.of("provider_message_id"));
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        String id = value(args.get("provider_message_id")); if (id.isBlank()) return error("缺少 provider_message_id");
        try { return render("thread", evidenceRepository.thread(currentGroupId, currentSessionId, id,
                cursor(args.get("cursor")), limit(args.get("limit"), 20, 30)), 1200); }
        catch (Exception e) { return error("查询消息链失败"); }
    }
}
