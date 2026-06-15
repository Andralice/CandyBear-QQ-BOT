package com.start.model;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 周期任务：定时触发 prompt 给 LLM，让 LLM 自己调工具完成联动。
 * 用户说"以后下雨提醒我"→ LLM 调 schedule_recurring_task 存一条记录，
 * 调度线程到时间取出 prompt 发给 LLM 自由执行。
 */
public class RecurringTask {
    private Long id;
    private String userId;
    private String groupId;
    private String taskName;
    private String cronExpr;          // 标准 5 字段 cron 或特殊格式 "daily_06:30" "weekly_mon_08:00"
    private String triggerPrompt;     // 触发时发给 LLM 的指令
    private int expireDays = 7;       // 多少天后自动过期
    private boolean enabled = true;
    private LocalDateTime lastFiredAt;
    private LocalDateTime nextFireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }

    public String getTriggerPrompt() { return triggerPrompt; }
    public void setTriggerPrompt(String triggerPrompt) { this.triggerPrompt = triggerPrompt; }

    public int getExpireDays() { return expireDays; }
    public void setExpireDays(int expireDays) { this.expireDays = expireDays; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(LocalDateTime lastFiredAt) { this.lastFiredAt = lastFiredAt; }

    public LocalDateTime getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(LocalDateTime nextFireAt) { this.nextFireAt = nextFireAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
