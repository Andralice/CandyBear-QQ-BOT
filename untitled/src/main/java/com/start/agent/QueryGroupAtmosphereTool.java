package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Group-only atmosphere query, intentionally separate from personal facts. */
public final class QueryGroupAtmosphereTool extends EvidenceToolSupport implements Tool {
    public QueryGroupAtmosphereTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                    String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_group_atmosphere"; }
    @Override public String getDescription() { return "查询当前群最近的群体氛围/事件观察。群体氛围不是任何成员的个人画像，最多20条并带有效期。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cursor", Map.of("type", "string", "description", "上一页游标"));
        p.put("limit", Map.of("type", "integer", "description", "最多20，默认10"));
        p.put("group_id", Map.of("type", "string", "description", "只能填写当前会话群号"));
        return Map.of("type", "object", "properties", p);
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, true); if (scopeError != null) return error(scopeError);
        try { return render("group_atmosphere", evidenceRepository.atmosphere(currentGroupId,
                cursor(args.get("cursor")), limit(args.get("limit"), 10, 20)), 2000, true); }
        catch (Exception e) { return error("查询群聊氛围失败"); }
    }
}
