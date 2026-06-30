package com.start.agent;

import com.start.model.ChatMessage;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 查询群聊历史：同时查 long_term_memories（结构化记忆）+ messages（原始记录兜底）。
 * 支持任意时间范围查询。
 */
public class SearchHistoryTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(SearchHistoryTool.class);
    private final MessageRepository messageRepo;
    private final LongTermMemoryRepository memoryRepo;

    public SearchHistoryTool(LongTermMemoryRepository memoryRepo) {
        this.messageRepo = new MessageRepository();
        this.memoryRepo = memoryRepo;
    }

    @Override public String getName() { return "search_chat_history"; }

    @Override public String getDescription() {
        return "搜索群聊历史记录（包括AI提炼的记忆和原始消息）。支持任意时间范围。" +
               "当用户问'刚才谁说了XXX''今天大家聊了什么''昨天XX说过什么''上周有没有人提过XXX'时调用。" +
               "参数：keyword(搜索关键词), user_id(指定某人QQ), count(返回条数，默认10), " +
               "date_from(起始日期，格式yyyy-MM-dd，如2026-06-05。查今天/昨天/某天时必填，否则只能扫到全表最近N条，无法区分日期), " +
               "date_to(结束日期，格式yyyy-MM-dd，如2026-06-05。查今天/昨天/某天时必填)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "keyword", Map.of("type", "string", "description", "搜索关键词"),
                        "user_id", Map.of("type", "string", "description", "指定用户QQ号"),
                        "count", Map.of("type", "string", "description", "返回条数，默认10，最多50"),
                        "date_from", Map.of("type", "string", "description", "起始日期时间，如'2026-06-05'（当天00:00起）或'2026-06-05 14:30'。查今天/昨天/某天时必填"),
                        "date_to", Map.of("type", "string", "description", "结束日期时间，如'2026-06-05'（当天23:59止）或'2026-06-05 18:00'。查今天/昨天/某天时必填")
                ),
                "required", Arrays.asList("group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        if (groupId == null || groupId.isBlank()) return "缺少 group_id 参数";

        String keyword = (String) args.get("keyword");
        String userId = (String) args.get("user_id");
        int count = Math.min(parseIntSafe((String) args.get("count"), 10), 50);
        String dateFrom = (String) args.get("date_from");
        String dateTo = (String) args.get("date_to");

        int memLimit = Math.min(count, 20);
        logger.info("search_chat_history: group={}, keyword={}, userId={}, dateFrom={}, dateTo={}, count={}",
                groupId, keyword, userId, dateFrom, dateTo, count);
        StringBuilder result = new StringBuilder();

        // 1. 先查结构化记忆（long_term_memories）
        try {
            List<LongTermMemory> memories;
            if (userId != null && !userId.isBlank()) {
                memories = memoryRepo.search(userId, groupId, blankToNull(keyword), memLimit, dateFrom, dateTo);
            } else {
                memories = memoryRepo.searchByGroup(groupId, blankToNull(keyword), memLimit, dateFrom, dateTo);
            }

            if (memories != null && !memories.isEmpty()) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
                result.append("【结构化记忆】找到 ").append(memories.size()).append(" 条");
                if (memories.size() >= memLimit) result.append("（已达上限，可能不全，请缩小时间范围）");
                result.append("：\n");
                for (int i = 0; i < memories.size(); i++) {
                    LongTermMemory m = memories.get(i);
                    result.append(i + 1).append(". [").append(m.getMemoryType()).append("] ");
                    result.append(m.getUserId()).append(": ").append(m.getContent());
                    result.append(" (").append(m.getCreatedAt() != null ? m.getCreatedAt().format(fmt) : "??").append(")\n");
                }
                result.append("\n");
            }
        } catch (Exception e) {
            result.append("【结构化记忆】查询失败: ").append(e.getMessage()).append("\n\n");
        }

        // 2. 再查原始消息（messages 兜底）
        var msgResult = messageRepo.searchMessages(groupId,
                blankToNull(keyword),
                blankToNull(userId),
                dateFrom,
                dateTo,
                count);

        if (!msgResult.isSuccess()) {
            result.append("【原始记录】查询失败：").append(msgResult.getError());
            return result.toString().trim();
        }

        List<ChatMessage> messages = msgResult.getData();
        if (messages == null || messages.isEmpty()) {
            if (result.isEmpty()) {
                StringBuilder sb = new StringBuilder("未找到相关聊天记录。");
                if (keyword != null && !keyword.isBlank()) sb.append(" 关键词：").append(keyword);
                if (userId != null && !userId.isBlank()) sb.append(" 用户：").append(userId);
                if (dateFrom != null) sb.append(" 时间：").append(dateFrom).append(" ~ ").append(dateTo != null ? dateTo : "现在");
                return sb.toString();
            }
            result.append("【原始记录】无匹配消息");
            return result.toString().trim();
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
        result.append("【原始记录】找到 ").append(messages.size()).append(" 条");
        if (messages.size() >= count) result.append("（已达上限，可能不全，请缩小时间范围）");
        result.append("：\n");
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            result.append("- [").append(msg.getCreatedAt() != null ? msg.getCreatedAt().format(fmt) : "??");
            result.append("] ").append(msg.getUserId()).append(": ").append(msg.getContent()).append("\n");
        }

        return result.toString().trim();
    }

    private int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
