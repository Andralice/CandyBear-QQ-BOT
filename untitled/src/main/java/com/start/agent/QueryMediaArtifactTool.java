package com.start.agent;

import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only media parser result lookup. */
public final class QueryMediaArtifactTool extends EvidenceToolSupport implements Tool {
    public QueryMediaArtifactTool(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                  String callerUserId, String currentGroupId, String currentSessionId) {
        super(evidenceRepository, messageRepository, callerUserId, currentGroupId, currentSessionId);
    }
    @Override public String getName() { return "query_media_artifact"; }
    @Override public String getDescription() { return "查询当前会话范围内的媒体解析产物。必须区分 SUCCEEDED、UNAVAILABLE、FAILED、PENDING，解析失败时不能补猜内容。"; }
    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("part_id", Map.of("type", "string", "description", "消息部件 ID，与 message_id 二选一"));
        p.put("message_id", Map.of("type", "string", "description", "本地消息 ID，与 part_id 二选一"));
        p.put("limit", Map.of("type", "integer", "description", "最多10，默认5"));
        return Map.of("type", "object", "properties", p);
    }
    @Override public String execute(Map<String, Object> args) {
        String scopeError = scopeError(args, false); if (scopeError != null) return error(scopeError);
        try { return render("media_artifact", evidenceRepository.mediaArtifacts(currentGroupId, currentSessionId,
                cursor(args.get("part_id")), cursor(args.get("message_id")), limit(args.get("limit"), 5, 10)), 4000, true); }
        catch (Exception e) { return error("查询媒体解析产物失败"); }
    }
}
