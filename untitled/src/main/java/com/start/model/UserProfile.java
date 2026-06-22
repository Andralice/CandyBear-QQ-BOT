package com.start.model;

import java.time.LocalDateTime;

public class UserProfile {
    private Long id;
    private String userId;
    private String groupId; // null 表示私聊
    private String profileText;
    private Integer messageCountSnapshot;
    private Long lastMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getProfileText() { return profileText; }
    public void setProfileText(String profileText) { this.profileText = profileText; }

    public Integer getMessageCountSnapshot() { return messageCountSnapshot; }
    public void setMessageCountSnapshot(Integer messageCountSnapshot) { this.messageCountSnapshot = messageCountSnapshot; }

    public Long getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(Long lastMessageId) { this.lastMessageId = lastMessageId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
