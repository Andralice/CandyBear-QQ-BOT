package com.start.agent;

import com.start.memory.MemoryEvidence;
import com.start.config.BotConfig;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.util.*;

/**
 * AI 检索长期记忆。当用户提到某个话题、或者对话需要回忆之前的信息时调用。
 * 如果 LLM 未提供 keyword，则用预注入的关键词自动搜索。
 * Returns scoped source evidence and bounded later context.
 */
public class RecallMemoryTool implements Tool {
    private final LongTermMemoryRepository repo;
    private final String callerUserId;
    private final String currentGroupId;
    private final java.time.LocalDateTime cutoff = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    private List<String> autoKeywords;

    public RecallMemoryTool(LongTermMemoryRepository repo, String callerUserId, String currentGroupId) {
        this.repo = repo;
        this.callerUserId = callerUserId;
        this.currentGroupId = currentGroupId;
    }

    /** 预注入关键词，LLM 未提供 keyword 时自动使用 */
    public void setAutoKeywords(List<String> keywords) {
        this.autoKeywords = keywords;
    }

    @Override public String getName() { return "recall_memory"; }

    @Override public String getDescription() {
        return "检索关于某个用户的长期记忆。遇到以下情况必须调用：" +
               "1) 用户问你还记得吗、我之前说过、你上次说、你知道我……吗——必须先查再答。" +
               "2) 和用户聊天时，如果能回忆起TA之前说过的事会让对话更自然——主动查。" +
               "3) 用户提了一个你隐约有印象但不确定的事——查了再说，别靠'感觉'。" +
               "查不到就直说'我不太记得了'，不要编造。" +
               "参数：user_id(用户QQ), group_id(群号), keyword(可选搜索关键词，不填则自动提取), count(返回条数，默认5), " +
               "date_from(可选，起始日期如2026-06-05), date_to(可选，结束日期)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "keyword", Map.of("type", "string", "description", "可选搜索关键词，不填则自动从当前消息提取"),
                        "count", Map.of("type", "string", "description", "返回条数，默认5"),
                        "date_from", Map.of("type", "string", "description", "可选，起始日期，如'2026-06-05'"),
                        "date_to", Map.of("type", "string", "description", "可选，结束日期，如'2026-06-05'")
                ),
                "required", Arrays.asList("user_id", "group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String keyword = (String) args.get("keyword");
        int count = Math.max(1, Math.min(10, parseIntSafe(Objects.toString(args.get("count"), null), 5)));
        String dateFrom = (String) args.get("date_from");
        String dateTo = (String) args.get("date_to");

        if (userId == null) return "缺少 user_id";
        boolean botPersona = userId.equals(String.valueOf(BotConfig.getBotQq()));
        if (groupId != null && !groupId.isBlank() && !"null".equals(groupId) && !groupId.equals(currentGroupId))
            return "拒绝跨群查询：只能查询当前会话范围";
        if ((currentGroupId == null || currentGroupId.isBlank()) && !userId.equals(callerUserId) && !botPersona)
            return "私聊中只能查询自己的记忆";
        groupId = botPersona ? null : currentGroupId;

        List<String> keywords = autoKeywords;
        if ((keyword == null || keyword.isBlank()) && (keywords == null || keywords.isEmpty())) {
            keywords = Collections.emptyList();
        }

        try {
            List<LongTermMemory> results;
            if (keyword != null && !keyword.isBlank()) {
                results = repo.search(userId, groupId, keyword, count, dateFrom, dateTo);
            } else if (!keywords.isEmpty()) {
                results = searchMultiKeyword(userId, groupId, keywords, count, dateFrom, dateTo);
            } else {
                results = repo.search(userId, groupId, null, count, dateFrom, dateTo);
            }

            if (results.isEmpty()) {
                StringBuilder hint = new StringBuilder();
                if (keyword != null && !keyword.isBlank()) hint.append("未找到关于 \"").append(keyword).append("\" 的记忆");
                else hint.append("暂无该用户的长期记忆");
                if (dateFrom != null) hint.append("（时间范围：").append(dateFrom).append(" ~ ").append(dateTo != null ? dateTo : "现在").append("）");
                return hint.toString();
            }

            // 标记为已召回
            for (LongTermMemory m : results) {
                try {
                    repo.markRecalled(m.getId());
                    repo.markUsed(m.getId());
                } catch (Exception ignored) {}
            }

            if (botPersona) results = results.stream().filter(m -> "AUTO_FORMED".equals(m.getSource())).toList();
            return MemoryEvidence.render(repo, results, cutoff);
        } catch (Exception e) {
            return "检索记忆失败: " + e.getMessage();
        }
    }

    /** 用多个关键词分别搜索，合并去重，限制条数 */
    private List<LongTermMemory> searchMultiKeyword(String userId, String groupId, List<String> keywords, int count,
                                                     String dateFrom, String dateTo) throws Exception {
        Set<Long> seen = new LinkedHashSet<>();
        List<LongTermMemory> merged = new ArrayList<>();
        for (String kw : keywords) {
            if (merged.size() >= count) break;
            List<LongTermMemory> batch = repo.search(userId, groupId, kw, count, dateFrom, dateTo);
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
