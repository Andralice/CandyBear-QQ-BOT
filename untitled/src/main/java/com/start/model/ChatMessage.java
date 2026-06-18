
// model/ChatMessage.java
package com.start.model;

import java.time.LocalDateTime;

public class ChatMessage {
    private Long id;
    private String groupId;          // 群号，私聊为null
    private String userId;           // 发送者ID
    private String content;
    private Boolean isRobotReply = false;
    private Boolean isPrivate = false;
    private Long replyToId;          // 回复的消息ID
    private String topics;           // 话题标签
    private String sessionId;        // BaiLianService的sessionId
    private String imageData;        // JSON: [{"url":"...","desc":"..."}]
    private LocalDateTime createdAt;

    // 构造方法
    public ChatMessage() {}

    public ChatMessage(String userId, String content, String groupId) {
        this.userId = userId;
        this.content = content;
        this.groupId = groupId;
        this.createdAt = LocalDateTime.now();
    }

    // getter和setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Boolean getIsRobotReply() { return isRobotReply; }
    public void setIsRobotReply(Boolean robotReply) { isRobotReply = robotReply; }

    public Boolean getIsPrivate() { return isPrivate; }
    public void setIsPrivate(Boolean isPrivate) { this.isPrivate = isPrivate; }

    public Long getReplyToId() { return replyToId; }
    public void setReplyToId(Long replyToId) { this.replyToId = replyToId; }

    public String getTopics() { return topics; }
    public void setTopics(String topics) { this.topics = topics; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getImageData() { return imageData; }
    public void setImageData(String imageData) { this.imageData = imageData; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
