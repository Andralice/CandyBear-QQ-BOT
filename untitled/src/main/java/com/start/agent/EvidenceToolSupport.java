package com.start.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.repository.EvidenceQueryRepository;
import com.start.repository.MessageRepository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared scope, pagination, and output rules for read-only evidence tools. */
abstract class EvidenceToolSupport {
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected final EvidenceQueryRepository evidenceRepository;
    protected final MessageRepository messageRepository;
    protected final String callerUserId;
    protected final String currentGroupId;
    protected final String currentSessionId;

    protected EvidenceToolSupport(EvidenceQueryRepository evidenceRepository, MessageRepository messageRepository,
                                  String callerUserId, String currentGroupId, String currentSessionId) {
        this.evidenceRepository = evidenceRepository;
        this.messageRepository = messageRepository;
        this.callerUserId = callerUserId;
        this.currentGroupId = currentGroupId;
        this.currentSessionId = currentSessionId;
    }

    protected String scopeError(Map<String, Object> args, boolean groupOnly) {
        String requested = value(args.get("group_id"));
        if (requested != null && !requested.isBlank() && !requested.equals(currentGroupId)) return "拒绝跨群查询：只能查询当前会话范围";
        if (groupOnly && (currentGroupId == null || currentGroupId.isBlank())) return "当前不是群聊会话，不能查询群范围证据";
        return null;
    }

    protected boolean canAccessUser(String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (currentGroupId == null || currentGroupId.isBlank()) return userId.equals(callerUserId);
        var visible = messageRepository.hasUserInGroup(userId, currentGroupId);
        return visible.isSuccess() && Boolean.TRUE.equals(visible.getData());
    }

    protected int limit(Object value, int defaultValue, int max) {
        try { return Math.max(1, Math.min(max, Integer.parseInt(String.valueOf(value)))); }
        catch (Exception ignored) { return defaultValue; }
    }

    protected long cursor(Object value) {
        try { return Math.max(0L, Long.parseLong(String.valueOf(value))); }
        catch (Exception ignored) { return 0L; }
    }

    protected String value(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    protected String render(String kind, List<Map<String, Object>> rows, int maxChars) {
        return render(kind, rows, maxChars, false);
    }

    protected String render(String kind, List<Map<String, Object>> rows, int maxChars, boolean descending) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("status", "OK");
            root.put("source", "raw_or_derived_evidence");
            root.put("scope_group_id", currentGroupId == null ? "" : currentGroupId);
            root.put("scope_session_id", currentSessionId == null ? "" : currentSessionId);
            root.put("caller_user_id", callerUserId == null ? "" : callerUserId);
            root.put("kind", kind);
            ArrayNode items = root.putArray("items");
            long next = descending ? Long.MAX_VALUE : 0;
            for (Map<String, Object> row : rows) {
                ObjectNode item = items.addObject();
                if (row.get("user_id") != null) {
                    item.put("is_current_requester", callerUserId != null
                            && callerUserId.equals(String.valueOf(row.get("user_id"))));
                }
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (value == null) { item.putNull(key); continue; }
                    if (value instanceof Timestamp timestamp) { item.put(key, timestamp.toInstant().toString()); continue; }
                    String text = String.valueOf(value);
                    if ("content".equals(key) || "raw_content".equals(key) || "content_json".equals(key)
                            || "raw_node_json".equals(key)) text = text.length() > maxChars ? text.substring(0, maxChars) + "...[truncated]" : text;
                    if (value instanceof Integer number) item.put(key, number);
                    else if (value instanceof Long number) item.put(key, number);
                    else if (value instanceof Short number) item.put(key, number.intValue());
                    else if (value instanceof Number number) item.put(key, number.doubleValue());
                    else if (value instanceof Boolean bool) item.put(key, bool);
                    else item.put(key, text);
                }
                Object id = row.get("id");
                if (id instanceof Number number) next = descending
                        ? Math.min(next, number.longValue()) : Math.max(next, number.longValue());
                Object messageId = row.get("message_id");
                if (messageId instanceof Number number) next = descending
                        ? Math.min(next, number.longValue()) : Math.max(next, number.longValue());
            }
            root.put("next_cursor", rows.isEmpty() ? 0 : next);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return error("OUTPUT_SERIALIZATION_FAILED");
        }
    }

    protected String error(String message) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "ERROR");
        root.put("error", message == null ? "UNKNOWN" : message);
        return root.toString();
    }

    protected String unavailable(String message) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "UNAVAILABLE");
        root.put("availability_status", "UNAVAILABLE");
        root.put("message", message);
        return root.toString();
    }
}
