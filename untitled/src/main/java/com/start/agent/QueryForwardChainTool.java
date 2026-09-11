package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only forwarded-message relationship lookup. */
public final class QueryForwardChainTool extends EvidenceToolSupport implements Tool {
    public QueryForwardChainTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                 String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_forward_chain"; }
    @Override public String getDescription() { return "查询当前会话中的转发消息展开关系。每个节点带原始发送者、时间、深度和 availability_status；没有成功展开时必须按不可用处理。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("part_id", Map.of("type", "string", "description", "forward 消息部件 ID"));
        p.put("limit", Map.of("type", "integer", "description", "最多30，默认20"));
        return Map.of("type", "object", "properties", p, "required", java.util.List.of("part_id"));
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        long partId = cursor(args.get("part_id")); if (partId == 0) return error("缺少 part_id");
        try {
            var rows = evidenceRepository.forwardChain(currentGroupId, currentSessionId, partId, limit(args.get("limit"), 20, 30));
            if (rows.isEmpty()) return unavailable("转发链尚未成功展开或没有可访问节点");
            return render("forward_chain", rows, 4000);
        } catch (Exception e) { return error("查询转发链失败"); }
    }
}
