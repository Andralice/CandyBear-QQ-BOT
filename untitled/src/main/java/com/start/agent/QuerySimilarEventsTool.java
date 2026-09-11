package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded current-group event similarity lookup. */
public final class QuerySimilarEventsTool extends EvidenceToolSupport implements Tool {
    public QuerySimilarEventsTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                  String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_similar_events"; }
    @Override public String getDescription() { return "按关键词查询当前群已记录的相似对话事件，最多20条。群外事件和任意用户的全库检索均不可用。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyword", Map.of("type", "string", "description", "事件/主题关键词"));
        p.put("cursor", Map.of("type", "string", "description", "上一页游标"));
        p.put("limit", Map.of("type", "integer", "description", "最多20，默认10"));
        return Map.of("type", "object", "properties", p, "required", java.util.List.of("keyword"));
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, true); if (scopeError != null) return error(scopeError);
        String keyword = value(args.get("keyword")); if (keyword.isBlank()) return error("缺少 keyword");
        try { return render("similar_events", evidenceRepository.similarEvents(currentGroupId, keyword,
                cursor(args.get("cursor")), limit(args.get("limit"), 10, 20)), 2500, true); }
        catch (Exception e) { return error("查询相似事件失败"); }
    }
}
