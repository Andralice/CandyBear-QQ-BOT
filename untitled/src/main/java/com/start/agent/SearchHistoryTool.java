package com.start.agent;

import com.start.model.ChatMessage;
import com.start.repository.MessageRepository;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 查询群聊历史记录：按关键词、用户、条数搜索。
 */
public class SearchHistoryTool implements Tool {
    private final MessageRepository messageRepo;

    public SearchHistoryTool() {
        this.messageRepo = new MessageRepository();
    }

    @Override public String getName() { return "search_chat_history"; }

    @Override public String getDescription() {
        return "搜索群聊历史记录（数据库中的真实消息）。" +
               "当用户问'刚才谁说了XXX''帮我查一下XXX说过什么''最近有没有人提过XXX''搜一下聊天记录'时调用。" +
               "参数：keyword(搜索关键词), user_id(指定某人QQ), count(返回条数，默认10), minutes(最近N分钟)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "keyword", Map.of("type", "string", "description", "搜索关键词"),
                        "user_id", Map.of("type", "string", "description", "指定用户QQ号"),
                        "count", Map.of("type", "string", "description", "返回条数，默认10，最多50"),
                        "minutes", Map.of("type", "string", "description", "搜索最近N分钟内的消息")
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
        int minutes = parseIntSafe((String) args.get("minutes"), 0);

        var result = messageRepo.searchMessages(groupId,
                blankToNull(keyword),
                blankToNull(userId),
                minutes,
                count);

        if (!result.isSuccess()) {
            return "查询聊天记录失败：" + result.getError();
        }

        List<ChatMessage> messages = result.getData();
        if (messages == null || messages.isEmpty()) {
            StringBuilder sb = new StringBuilder("未找到相关聊天记录。");
            if (keyword != null && !keyword.isBlank()) sb.append(" 关键词：").append(keyword);
            if (userId != null && !userId.isBlank()) sb.append(" 用户：").append(userId);
            return sb.toString();
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("找到 ").append(messages.size()).append(" 条聊天记录：\n");
        // DB 返回按时间倒序，反转成正序方便阅读
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            sb.append("- [").append(msg.getCreatedAt() != null ? msg.getCreatedAt().format(fmt) : "??");
            sb.append("] ").append(msg.getUserId()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
