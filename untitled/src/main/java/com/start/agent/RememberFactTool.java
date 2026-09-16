package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Save a retrieval cue linked to an utterance, without promoting it to a verified fact. */
public class RememberFactTool implements Tool {
    private final LongTermMemoryRepository repo;
    private final String currentUserId;
    private final String currentGroupId;
    private final LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

    public RememberFactTool(LongTermMemoryRepository repo, String currentUserId, String currentGroupId) {
        this.repo = repo;
        this.currentUserId = currentUserId;
        this.currentGroupId = currentGroupId;
    }
    @Override public String getName() { return "remember_fact"; }
    @Override public String getDescription() {
        return "保存值得以后回顾的用户原话线索。先从 query_message_context 或 search_chat_history 取得本地 source_message_id。"
                + "content 是检索概括，保留原话中的可能、今天、偶尔等限定；原话是判断依据。"
                + "更正或补充作为新的观察保存，旧观察保留。无需把每条闲聊都存成长期偏好。";
    }
    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(
                "source_message_id", Map.of("type", "string", "description", "当前用户原始消息的本地数据库编号，不是QQ消息编号"),
                "content", Map.of("type", "string", "description", "保留人物、事件及时间限定的检索线索"),
                "memory_type", Map.of("type", "string", "enum", List.of("fact", "preference", "event", "relation"),
                        "description", "仅用于检索分类，不表示永久有效"),
                "keywords", Map.of("type", "string", "description", "逗号分隔关键词"),
                "importance", Map.of("type", "string", "description", "重要性1至5，默认3")
        ), "required", List.of("content", "source_message_id"));
    }
    @Override public String execute(Map<String, Object> args) {
        String content = Objects.toString(args.get("content"), "").trim();
        if (currentUserId == null || content.isBlank()) return "缺少 user_id 或 content";
        if (content.length() > 2000) return "检索概括过长，请保留必要信息";
        long sourceId;
        try { sourceId = Long.parseLong(Objects.toString(args.get("source_message_id"), "")); }
        catch (NumberFormatException e) { return "缺少有效 source_message_id，请先查询原始消息"; }
        String type = Objects.toString(args.get("memory_type"), "fact");
        if (!Set.of("fact", "preference", "event", "relation").contains(type)) return "无效 memory_type";
        LongTermMemory memory = new LongTermMemory();
        memory.setUserId(currentUserId);
        memory.setGroupId(currentGroupId == null || currentGroupId.isBlank() ? null : currentGroupId);
        memory.setSourceMessageId(sourceId);
        memory.setContent(content);
        memory.setMemoryType(type);
        memory.setKeywords(Objects.toString(args.get("keywords"), null));
        int importance = 3;
        try { importance = Integer.parseInt(Objects.toString(args.get("importance"), "3")); }
        catch (NumberFormatException ignored) {}
        memory.setImportance(Math.max(1, Math.min(5, importance)));
        try {
            repo.insertGrounded(memory, cutoff);
            return "已记住原话线索，memory_id=" + memory.getId() + "，source_message_id=" + sourceId
                    + "。保留原话适用范围；概括尚未做语义核验。";
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        catch (Exception e) { return "记录失败，未确认保存成功"; }
    }
}
