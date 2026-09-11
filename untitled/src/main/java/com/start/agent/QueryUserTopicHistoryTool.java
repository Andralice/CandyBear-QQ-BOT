package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only personal facts plus current-group evidence for one visible user. */
public final class QueryUserTopicHistoryTool extends EvidenceToolSupport implements Tool {
    private final ProfileFactRepository factRepository;
    public QueryUserTopicHistoryTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                     ProfileFactRepository factRepository, String callerUserId,
                                     String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
        this.factRepository = factRepository;
    }
    @Override public String getName() { return "query_user_topic_history"; }
    @Override public String getDescription() { return "查询当前会话可见用户的个人主题历史。正式事实按个人主体跨群复用，但群聊中只返回 PERSONAL 事实，且目标用户必须在当前群出现过。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", Map.of("type", "string", "description", "目标用户QQ；缺省为当前用户"));
        p.put("topic", Map.of("type", "string", "description", "可选主题关键词"));
        p.put("limit", Map.of("type", "integer", "description", "最多10条正式事实，默认10"));
        return Map.of("type", "object", "properties", p);
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        String userId = value(args.get("user_id")); if (userId.isBlank()) userId = callerUserId;
        if (!canAccessUser(userId)) return error("目标用户不在当前授权范围");
        try {
            boolean privateContext = currentGroupId == null || currentGroupId.isBlank();
            var facts = factRepository.findActiveByUserId(userId, privateContext, limit(args.get("limit"), 10, 10));
            List<Map<String, Object>> rows = new ArrayList<>();
            String topic = value(args.get("topic")).toLowerCase();
            for (var fact : facts) {
                if (!topic.isBlank() && !fact.claim().toLowerCase().contains(topic)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fact_id", fact.id()); row.put("kind", fact.kind()); row.put("epistemic_type", fact.epistemicType());
                row.put("claim", fact.claim()); row.put("stability", fact.stability()); row.put("confidence", fact.confidence());
                row.put("scope", fact.scope()); row.put("last_observed_at", fact.lastObservedAt());
                row.put("valid_until", fact.validUntil()); row.put("subject_user_id", userId); row.put("availability_status", "AVAILABLE");
                row.put("evidence", MAPPER.writeValueAsString(factRepository.findEvidenceForFact(fact.id(), 3)));
                rows.add(row);
            }
            return render("user_topic_history", rows, 1200);
        } catch (Exception e) { return error("查询用户主题历史失败"); }
    }
}
