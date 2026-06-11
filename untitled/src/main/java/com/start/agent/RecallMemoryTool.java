package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.util.*;

/**
 * AI 检索长期记忆。当用户提到某个话题、或者对话需要回忆之前的信息时调用。
 * 如果 LLM 未提供 keyword，则用预注入的关键词自动搜索。
 */
public class RecallMemoryTool implements Tool {
    private final LongTermMemoryRepository repo;
    private List<String> autoKeywords;

    public RecallMemoryTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    /** 预注入关键词，LLM 未提供 keyword 时自动使用 */
    public void setAutoKeywords(List<String> keywords) {
        this.autoKeywords = keywords;
    }

    @Override public String getName() { return "recall_memory"; }

    @Override public String getDescription() {
        return "检索关于某个用户的长期记忆。当用户说还记得我之前说过什么吗、你上次说、或者对话需要回忆以前的上下文时调用。" +
               "参数：user_id(用户QQ), group_id(群号), keyword(可选搜索关键词，不填则自动提取), count(返回条数，默认5)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "keyword", Map.of("type", "string", "description", "可选搜索关键词，不填则自动从当前消息提取"),
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

        // 如果 LLM 没提供关键词，使用预注入的关键词
        List<String> keywords = autoKeywords;
        if ((keyword == null || keyword.isBlank()) && (keywords == null || keywords.isEmpty())) {
            keywords = Collections.emptyList();
        }

        try {
            List<LongTermMemory> results;
            if (keyword != null && !keyword.isBlank()) {
                // LLM 明确指定了关键词，直接用
                results = repo.search(userId, groupId, keyword, count);
            } else if (!keywords.isEmpty()) {
                // 用预注入的关键词多词搜索
                results = searchMultiKeyword(userId, groupId, keywords, count);
            } else {
                // 什么都没有，无关键词搜索
                results = repo.search(userId, groupId, null, count);
            }

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

    /** 用多个关键词分别搜索，合并去重，限制条数 */
    private List<LongTermMemory> searchMultiKeyword(String userId, String groupId, List<String> keywords, int count) throws Exception {
        Set<Long> seen = new LinkedHashSet<>();
        List<LongTermMemory> merged = new ArrayList<>();
        for (String kw : keywords) {
            if (merged.size() >= count) break;
            List<LongTermMemory> batch = repo.search(userId, groupId, kw, count);
            for (LongTermMemory m : batch) {
                if (seen.add(m.getId())) {
                    merged.add(m);
                    if (merged.size() >= count) break;
                }
            }
        }
        return merged;
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
