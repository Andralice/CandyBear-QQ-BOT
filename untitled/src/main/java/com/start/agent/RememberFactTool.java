package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.util.*;

/**
 * AI 存储长期记忆。当用户说了值得记住的信息（个人事实、偏好、事件），调用此工具写入 DB。
 */
public class RememberFactTool implements Tool {
    private final LongTermMemoryRepository repo;

    public RememberFactTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "remember_fact"; }

    @Override public String getDescription() {
        return "记录一条关于用户的长期记忆。当用户说了一个可能以后有用的信息时调用。" +
               "例如用户说我今天不开心、我是程序员、下周五是我生日等，这些信息以后可能有用。" +
               "参数：user_id(用户QQ), group_id(群号), content(记忆内容，一句话), memory_type(fact/preference/event/relation), keywords(逗号分隔的关键词), importance(1-5重要性)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "content", Map.of("type", "string", "description", "记忆内容，简洁的一句话"),
                        "memory_type", Map.of("type", "string", "description", "类型：fact(事实)/preference(偏好)/event(事件)/relation(关系)"),
                        "keywords", Map.of("type", "string", "description", "关键词，逗号分隔，方便以后检索"),
                        "importance", Map.of("type", "string", "description", "重要性 1-5，5 为非常重要")
                ),
                "required", Arrays.asList("user_id", "group_id", "content"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String content = (String) args.get("content");
        if (userId == null || content == null || content.isBlank()) return "缺少 user_id 或 content";

        LongTermMemory m = new LongTermMemory();
        m.setUserId(userId);
        m.setGroupId(groupId);
        m.setContent(content.trim());
        m.setMemoryType((String) args.getOrDefault("memory_type", "fact"));
        m.setKeywords((String) args.get("keywords"));
        m.setImportance(parseIntSafe((String) args.get("importance"), 3));

        try {
            repo.insert(m);
            return "已记住: " + content;
        } catch (Exception e) {
            return "记录失败: " + e.getMessage();
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
