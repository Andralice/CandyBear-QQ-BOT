package com.start.model;

import java.time.LocalDateTime;

/**
 * 糖果熊分群心情状态，持久化到 group_mood 表。
 */
public class GroupMood {
    private Long id;
    private String groupId;
    private int mood = 50;
    private long lastTopicThrowTime = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public int getMood() { return mood; }
    public void setMood(int mood) { this.mood = mood; }

    public long getLastTopicThrowTime() { return lastTopicThrowTime; }
    public void setLastTopicThrowTime(long lastTopicThrowTime) { this.lastTopicThrowTime = lastTopicThrowTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
