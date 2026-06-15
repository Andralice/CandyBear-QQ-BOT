package com.start.agent;

import com.start.model.RecurringTask;
import com.start.repository.RecurringTaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具联动入口：用户说"以后下雨提醒我"→ LLM 调此工具，存入定时 prompt，
 * 调度线程到时间取出 prompt 发给 LLM，LLM 自己调用现有工具完成联动。
 */
public class ScheduleRecurringTaskTool implements Tool {

    private final RecurringTaskRepository repo;

    public ScheduleRecurringTaskTool(RecurringTaskRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "schedule_recurring_task"; }

    @Override
    public String getDescription() {
        return "设置一个周期执行的联动任务。当你想'以后每次XX条件满足时做YY'，把检查条件和执行动作写成 trigger_prompt，到时间调度系统会把 prompt 发给你，你再调其他工具完成。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "发起者QQ"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "task_name", Map.of("type", "string", "description", "任务名称，如'雨天提醒'"),
                        "schedule", Map.of("type", "string",
                                "description", "触发时间表。格式：'daily_06:30'（每天6:30）或 'daily_07:00,18:00'（每天多个时间逗号分隔）或 'weekly_mon_08:00'（每周一8:00）。时间用24小时制。"),
                        "trigger_prompt", Map.of("type", "string",
                                "description", "触发时的完整指令。写明：检查什么条件（如调 get_weather 查今天是否下雨），满足条件后做什么（如调 set_reminder 在具体时间提醒用户）。包含所有必要参数：user_id={当前user_id}, group_id={当前group_id}。"),
                        "expire_days", Map.of("type", "string", "description", "几天后自动失效，默认7天。用户可以指定更久如30天。")
                ),
                "required", Arrays.asList("user_id", "schedule", "trigger_prompt"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String taskName = (String) args.get("task_name");
        String schedule = (String) args.get("schedule");
        String triggerPrompt = (String) args.get("trigger_prompt");
        int expireDays = parseIntSafe((String) args.get("expire_days"), 7);

        if (userId == null || schedule == null || triggerPrompt == null) {
            return "缺少必要参数 user_id/schedule/trigger_prompt";
        }

        String cronExpr = parseSchedule(schedule);
        if (cronExpr == null) {
            return "无法解析 schedule: " + schedule + "。请用 daily_HH:mm 格式（如 daily_06:30）或 weekly_周几_HH:mm（如 weekly_mon_08:00）";
        }

        LocalDateTime nextFireAt = computeNextFire(schedule);
        if (nextFireAt == null) {
            return "无法计算下次触发时间";
        }

        RecurringTask t = new RecurringTask();
        t.setUserId(userId);
        t.setGroupId(groupId);
        t.setTaskName(taskName != null ? taskName : schedule + "任务");
        t.setCronExpr(cronExpr);
        t.setTriggerPrompt(triggerPrompt);
        t.setExpireDays(Math.min(expireDays, 365));
        t.setEnabled(true);
        t.setNextFireAt(nextFireAt);

        try {
            repo.insert(t);
            String timeDesc = nextFireAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return String.format("已设置周期任务[%s]，将在 %s 首次触发（共%d天后过期）。\n触发指令：%s",
                    t.getTaskName(), timeDesc, expireDays, triggerPrompt);
        } catch (Exception e) {
            return "设置失败: " + e.getMessage();
        }
    }

    /** 解析 schedule 为 cron 表达式 */
    static String parseSchedule(String schedule) {
        if (schedule == null) return null;

        // daily_HH:mm 或 daily_HH:mm,HH:mm
        Matcher daily = Pattern.compile("^daily_(\\d{1,2}:\\d{2}(?:,\\d{1,2}:\\d{2})*)$").matcher(schedule);
        if (daily.matches()) {
            String[] times = daily.group(1).split(",");
            StringBuilder sb = new StringBuilder();
            for (String t : times) {
                String[] hm = t.split(":");
                if (sb.length() > 0) sb.append(";");
                sb.append(hm[1]).append(" ").append(hm[0]).append(" * * *");
            }
            return sb.toString();
        }

        // weekly_周几_HH:mm（支持中文和英文缩写）
        Matcher weekly = Pattern.compile("^weekly_(mon|tue|wed|thu|fri|sat|sun|周一|周二|周三|周四|周五|周六|周日|一|二|三|四|五|六|日)_(\\d{1,2}:\\d{2}(?:,\\d{1,2}:\\d{2})*)$", Pattern.CASE_INSENSITIVE).matcher(schedule);
        if (weekly.matches()) {
            int dow = dayOfWeek(weekly.group(1));
            if (dow < 0) return null;
            String[] times = weekly.group(2).split(",");
            StringBuilder sb = new StringBuilder();
            for (String t : times) {
                String[] hm = t.split(":");
                if (sb.length() > 0) sb.append(";");
                sb.append(hm[1]).append(" ").append(hm[0]).append(" * * ").append(dow);
            }
            return sb.toString();
        }

        return null;
    }

    /** 计算下次触发时间 */
    static LocalDateTime computeNextFire(String schedule) {
        Matcher daily = Pattern.compile("^daily_(\\d{1,2}:\\d{2}(?:,\\d{1,2}:\\d{2})*)$").matcher(schedule);
        if (daily.matches()) {
            LocalDate today = LocalDate.now();
            String[] times = daily.group(1).split(",");
            List<LocalTime> timeList = new ArrayList<>();
            for (String t : times) {
                String[] hm = t.split(":");
                timeList.add(LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])));
            }
            timeList.sort(null);
            LocalDateTime now = LocalDateTime.now();
            for (LocalTime lt : timeList) {
                LocalDateTime candidate = LocalDateTime.of(today, lt);
                if (candidate.isAfter(now)) return candidate;
            }
            // 今天全过了，明天第一个
            return LocalDateTime.of(today.plusDays(1), timeList.get(0));
        }

        Matcher weekly = Pattern.compile("^weekly_(mon|tue|wed|thu|fri|sat|sun|周一|周二|周三|周四|周五|周六|周日|一|二|三|四|五|六|日)_(\\d{1,2}:\\d{2}(?:,\\d{1,2}:\\d{2})*)$", Pattern.CASE_INSENSITIVE).matcher(schedule);
        if (weekly.matches()) {
            int targetDow = dayOfWeek(weekly.group(1));
            String[] times = weekly.group(2).split(",");
            List<LocalTime> timeList = new ArrayList<>();
            for (String t : times) {
                String[] hm = t.split(":");
                timeList.add(LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])));
            }
            timeList.sort(null);
            LocalDate today = LocalDate.now();
            LocalDateTime now = LocalDateTime.now();
            int todayDow = today.getDayOfWeek().getValue(); // 1=Mon
            int daysUntil = (targetDow - todayDow + 7) % 7;
            LocalDate nextDow = today.plusDays(daysUntil);
            for (LocalTime lt : timeList) {
                LocalDateTime candidate = LocalDateTime.of(nextDow, lt);
                if (candidate.isAfter(now)) return candidate;
            }
            // 当天已过，下周
            return LocalDateTime.of(today.plusDays(daysUntil + 7), timeList.get(0));
        }

        return null;
    }

    /**
     * 计算下次触发时间（在已解析出 cron 表达式的基础上）。
     * RecurringTaskScheduler 每 60 秒扫描时也会用此逻辑更新 next_fire_at。
     */
    public static LocalDateTime computeNextFireFromSchedule(String schedule) {
        return computeNextFire(schedule);
    }

    private static int dayOfWeek(String s) {
        switch (s.toLowerCase()) {
            case "mon": case "周一": case "一": return 1;
            case "tue": case "周二": case "二": return 2;
            case "wed": case "周三": case "三": return 3;
            case "thu": case "周四": case "四": return 4;
            case "fri": case "周五": case "五": return 5;
            case "sat": case "周六": case "六": return 6;
            case "sun": case "周日": case "日": return 7;
            default: return -1;
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
