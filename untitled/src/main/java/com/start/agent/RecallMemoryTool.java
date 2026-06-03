package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.util.*;

/**
 * AI 检索长期记忆。当用户提到某个话题、或者对话需要回忆之前的信息时调用。
 */
public class RecallMemoryTool implements Tool {
    private final LongTermMemoryRepository repo;

    public RecallMemoryTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "recall_memory"; }

    @Override public String getDescription() {
        return "检索关于某个用户的长期记忆。当用户说还记得我之前说过什么吗、你上次说、或者对话需要回忆以前的上下文时调用。" +
               "参数：user_id(用户QQ), group_id(群号), keyword(搜索关键词), count(返回条数，默认5)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "keyword", Map.of("type", "string", "description", "搜索关键词"),
                        "count", Map.of("type", "string", "description", "返回条数，默认5")
                ),
                "required", Arrays.asList("user_id", "group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String keyword = (String) args.get("keyword");
        int count = parseIntSafe((String) args.get("count"), 5);

        if (userId == null) return "缺少 user_id";

        try {
            List<LongTermMemory> results = repo.search(userId, groupId, keyword, count);
            if (results.isEmpty()) {
                return keyword != null && !keyword.isBlank()
                        ? "未找到关于 " + keyword + " 的记忆"
                        : "暂无该用户的长期记忆";
            }

            // 标记为已召回
            for (LongTermMemory m : results) {
                try { repo.markRecalled(m.getId()); } catch (Exception ignored) {}
            }

            StringBuilder sb = new StringBuilder("关于该用户的记忆：\n");
            for (int i = 0; i < results.size(); i++) {
                LongTermMemory m = results.get(i);
                sb.append(i + 1).append(". [").append(m.getMemoryType()).append("] ");
                sb.append(m.getContent());
                sb.append(" (重要性:").append(m.getImportance()).append(", 回忆").append(m.getRecallCount() + 1).append("次)\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "检索记忆失败: " + e.getMessage();
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
