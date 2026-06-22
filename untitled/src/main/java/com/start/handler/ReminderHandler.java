package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.service.ReminderService;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 私聊提醒任务管理命令处理器
 *
 * 实现 {@link MessageHandler} 接口，提供三种提醒模式：
 * 1. 周期提醒：每隔 N 秒重复发送（最多 M 次）
 * 2. 一次性定时提醒：在指定日期时间发送一次
 * 3. 每日定时提醒：每天固定时间发送
 *
 * 所有功能仅限私聊使用，且需管理员权限。
 */
public class ReminderHandler implements MessageHandler {

    /**
     * 管理员 QQ 号白名单（建议后续从配置读取）
     */
    private static final Set<Long> ADMIN_USERS = Set.of(
            0L   // ← 请替换为你的 QQ 号，或通过 admin.qq 配置
    );

    /**
     * 判断当前消息是否应由本 Handler 处理。
     *
     * 匹配规则：
     * - 必须是私聊消息；
     * - 必须以 "/remind" 开头；
     * - "/remind" 后必须是空格或制表符；
     * - 至少包含一个子命令（如 "on", "user", "at" 等）。
     *
     * @param message OneBot 消息事件 JSON 对象
     * @return 是否匹配
     */
    @Override
    public boolean match(JsonNode message) {
        String messageType = message.path("message_type").asText();
        if (!"private".equals(messageType)) {
            return false;
        }

        String rawMessage = message.path("raw_message").asText();
        if (rawMessage == null || rawMessage.isEmpty()) {
            return false;
        }

        if (!rawMessage.startsWith("/remind")) {
            return false;
        }

        // 防止匹配 "/reminder" 等非命令
        if (rawMessage.length() > 7) {
            char nextChar = rawMessage.charAt(7);
            if (nextChar != ' ' && nextChar != '\t') {
                return false;
            }
        }

        // 至少要有子命令
        String[] parts = rawMessage.trim().split("\\s+", 2);
        return parts.length >= 2;
    }

    /**
     * 执行提醒命令逻辑。
     *
     * 支持的子命令：
     * - on / off：全局开关
     * - user：周期提醒
     * - at：一次性定时提醒
     * - daily：每日定时提醒
     *
     * @param message OneBot 消息事件
     * @param bot     机器人主实例
     */
    @Override
    public void handle(JsonNode message, Main bot) {
        long userId = message.path("user_id").asLong();

        if (!ADMIN_USERS.contains(userId)) {
            bot.sendPrivateReply(userId, "❌ 权限不足，仅管理员可使用此命令。");
            return;
        }

        String raw = message.path("raw_message").asText().trim();
        String[] parts = raw.split("\\s+", 4); // 先粗分，用于判断子命令类型

        if (parts.length < 2) {
            showUsage(bot, userId);
            return;
        }

        ReminderService rs = ReminderService.getInstance();
        String subCmd = parts[1];

        if ("on".equals(subCmd)) {
            rs.setEnabled(true);
            bot.sendPrivateReply(userId, "✅ 私聊提醒服务已全局开启。");

        } else if ("off".equals(subCmd)) {
            rs.setEnabled(false);
            bot.sendPrivateReply(userId, "🔕 私聊提醒服务已全局关闭，所有活跃任务已取消。");

        } else if ("user".equals(subCmd)) {
            handleIntervalReminder(raw, bot, userId, rs);

        } else if ("at".equals(subCmd)) {
            handleAtReminder(raw, bot, userId, rs);

        } else if ("daily".equals(subCmd)) {
            handleDailyReminder(raw, bot, userId, rs);

        } else {
            showUsage(bot, userId);
        }
    }

    // --- 周期提醒：/remind user <uid> <msg> <interval> ---
    private void handleIntervalReminder(String raw, Main bot, long adminId, ReminderService rs) {
        String[] parts = raw.split("\\s+", 5);
        if (parts.length != 5) {
            bot.sendPrivateReply(adminId, "❌ 用法：/remind user <user_id> <消息> <间隔秒数>");
            return;
        }

        try {
            long uid = Long.parseLong(parts[2]);
            String msg = parts[3];
            long interval = Long.parseLong(parts[4]);

            if (interval < 10) {
                bot.sendPrivateReply(adminId, "⚠️ 间隔不能少于10秒。");
                return;
            }

            rs.startReminding(uid, msg, interval, 5); // 默认最多5次
            bot.sendPrivateReply(adminId,
                    String.format("✅ 已设置周期提醒：\n用户：%d\n消息：%s\n间隔：%d秒\n最多重试：5次", uid, msg, interval));
        } catch (NumberFormatException e) {
            bot.sendPrivateReply(adminId, "❌ 用户ID或间隔必须为数字。");
        }
    }

    // --- 一次性定时提醒：/remind at <uid> <yyyy-MM-ddTHH:mm> <msg> ---
    private void handleAtReminder(String raw, Main bot, long adminId, ReminderService rs) {
        // 正则：/remind at <数字> <ISO时间> <剩余部分>
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^/remind\\s+at\\s+(\\d+)\\s+(\\d{4}-\\d{2}-\\d{2}T[0-2]\\d:[0-5]\\d)\\s+(.+)$"
        );
        java.util.regex.Matcher matcher = pattern.matcher(raw);

        if (!matcher.matches()) {
            bot.sendPrivateReply(adminId, "❌ 用法：/remind at <user_id> <yyyy-MM-ddTHH:mm> <消息>\n示例：/remind at 123456789 2026-02-01T09:00 开会了！");
            return;
        }

        try {
            long uid = Long.parseLong(matcher.group(1));
            String timeStr = matcher.group(2);
            String msg = matcher.group(3).trim();

            if (msg.isEmpty()) {
                bot.sendPrivateReply(adminId, "❌ 消息内容不能为空。");
                return;
            }

            LocalDateTime triggerTime = LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            rs.remindAt(uid, msg, triggerTime);
            bot.sendPrivateReply(adminId,
                    String.format("✅ 已设置一次性提醒：\n用户：%d\n时间：%s\n消息：%s", uid, triggerTime, msg));

        } catch (DateTimeParseException e) {
            bot.sendPrivateReply(adminId, "❌ 时间格式错误！应为：yyyy-MM-ddTHH:mm（例如：2026-02-01T09:00）");
        } catch (NumberFormatException e) {
            bot.sendPrivateReply(adminId, "❌ 用户ID必须为数字。");
        }
    }

    // --- 每日定时提醒：/remind daily <uid> <HH:mm> <msg> ---
    private void handleDailyReminder(String raw, Main bot, long adminId, ReminderService rs) {
        // 正则：/remind daily <数字> <H:mm> <剩余部分>
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^/remind\\s+daily\\s+(\\d+)\\s+([0-2]?\\d:[0-5]?\\d)\\s+(.+)$"
        );
        java.util.regex.Matcher matcher = pattern.matcher(raw);

        if (!matcher.matches()) {
            bot.sendPrivateReply(adminId, "❌ 用法：/remind daily <user_id> <HH:mm> <消息>");
            return;
        }

        try {
            long uid = Long.parseLong(matcher.group(1));
            String timeStr = matcher.group(2);
            String msg = matcher.group(3).trim();

            if (msg.isEmpty()) {
                bot.sendPrivateReply(adminId, "❌ 消息内容不能为空。");
                return;
            }

            LocalTime time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"));
            rs.remindDaily(uid, msg, time);
            bot.sendPrivateReply(adminId,
                    String.format("✅ 已设置每日提醒：\n用户：%d\n时间：%s\n消息：%s", uid, time, msg));

        } catch (DateTimeParseException e) {
            bot.sendPrivateReply(adminId, "❌ 时间格式错误！应为 HH:mm（例如：09:00 或 18:30）");
        } catch (NumberFormatException e) {
            bot.sendPrivateReply(adminId, "❌ 用户ID必须为数字。");
        }
    }

    /**
     * 向管理员发送完整使用说明
     */
    private void showUsage(Main bot, long userId) {
        String usage = """
            🔔 私聊提醒管理命令（仅管理员可用）：

            /remind on
            /remind off
            /remind user <uid> <消息> <间隔秒数>
            /remind at <uid> <yyyy-MM-ddTHH:mm> <消息>
            /remind daily <uid> <HH:mm> <消息>

            说明：
            - <uid>：目标用户的 QQ 号
            - 周期提醒最小间隔：10 秒
            - 一次性时间格式：2026-02-01T09:00
            - 每日时间格式：09:00 或 18:30
            - 当目标用户回复任意私聊时，提醒自动停止

            示例：
            /remind user 123456789 快超时了！ 300
            /remind at 123456789 2026-02-01T09:00 会议开始！
            /remind daily 123456789 08:00 早安打卡！
            """;
        bot.sendPrivateReply(userId, usage);
    }
}