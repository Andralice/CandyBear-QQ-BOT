package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * AI 记录定时事件。当用户提到未来某个时间点的事（生日、纪念日、约定等），
 * 调用此工具记录，系统会在到期时主动触发糖果熊回应。
 */
public class ScheduleEventTool implements Tool {
    private final LongTermMemoryRepository repo;

    private static final List<DateTimeFormatter> FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    public ScheduleEventTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "schedule_event"; }

    @Override public String getDescription() {
        return "记录一个未来会发生的定时事件。当用户说了某个时间点的事情时调用。" +
               "例如用户说我下周三生日、6月15号考试、明天下午3点开会等。" +
               "参数：user_id(用户QQ), group_id(群号), content(事件描述), trigger_time(触发时间，格式yyyy-MM-dd HH:mm:ss), event_type(事件类型:birthday/anniversary/meeting/custom), importance(1-5)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "content", Map.of("type", "string", "description", "事件描述，简洁一句话，如归儿的生日、考试日"),
                        "trigger_time", Map.of("type", "string", "description", "触发时间，格式yyyy-MM-dd HH:mm:ss。只写日期则默认当天09:00触发"),
                        "event_type", Map.of("type", "string", "description", "事件类型：birthday/anniversary/meeting/custom"),
                        "importance", Map.of("type", "string", "description", "重要性 1-5，生日类建议5")
                ),
                "required", Arrays.asList("user_id", "group_id", "content", "trigger_time"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String content = (String) args.get("content");
        String triggerTimeStr = (String) args.get("trigger_time");

        if (userId == null || content == null || triggerTimeStr == null) {
            return "缺少必要参数 user_id/content/trigger_time";
        }

        LocalDateTime triggerAt = parseDateTime(triggerTimeStr.trim());
        if (triggerAt == null) {
            return "无法解析时间 " + triggerTimeStr + "，请用 yyyy-MM-dd HH:mm:ss 格式";
        }

        if (triggerAt.isBefore(LocalDateTime.now())) {
            return "触发时间 " + triggerTimeStr + " 已经过去了，不需要记录";
        }

        LongTermMemory m = new LongTermMemory();
        m.setUserId(userId);
        m.setGroupId(groupId);
        m.setContent(content.trim());
        m.setMemoryType((String) args.getOrDefault("event_type", "event"));
        m.setKeywords("定时事件," + content.trim());
        m.setImportance(parseIntSafe((String) args.get("importance"), 3));
        m.setTriggerAt(triggerAt);

        try {
            repo.insert(m);
            return String.format("已记录定时事件: %s，将在 %s 触发", content, triggerTimeStr);
        } catch (Exception e) {
            return "记录失败: " + e.getMessage();
        }
    }

    private LocalDateTime parseDateTime(String s) {
        for (DateTimeFormatter fmt : FORMATS) {
            try {
                return LocalDateTime.parse(s, fmt);
            } catch (DateTimeParseException ignored) {}
        }
        // 尝试只解析日期，默认 09:00
        for (DateTimeFormatter fmt : Arrays.asList(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyy年M月d日"))) {
            try {
                return LocalDateTime.parse(s, fmt).withHour(9).withMinute(0).withSecond(0);
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
