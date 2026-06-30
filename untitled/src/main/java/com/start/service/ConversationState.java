package com.start.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 代表一次进行中的群聊对话任务。消息到达时创建，回复发送后销毁。
 */
public class ConversationState {
    private final String groupId;
    private final String userId;
    private final AtomicLong messageRevision = new AtomicLong(0);
    private final AtomicLong generation = new AtomicLong(0);
    private final List<MessageEntry> pendingMessages = new ArrayList<>();
    private final List<ImageInfo> imageInfos = new ArrayList<>();
    private final List<String> linksToFetch = new ArrayList<>();
    private volatile int regenerateCount = 0;
    private volatile boolean submitted = false;
    private final long createdAt;
    private volatile Long replyToMessageId;

    public ConversationState(String groupId, String userId) {
        this.groupId = groupId;
        this.userId = userId;
        this.createdAt = System.currentTimeMillis();
    }

    public String getGroupId() { return groupId; }
    public String getUserId() { return userId; }
    public long getMessageRevision() { return messageRevision.get(); }
    public long getGeneration() { return generation.get(); }
    public int getRegenerateCount() { return regenerateCount; }
    public long getCreatedAt() { return createdAt; }
    public boolean isSubmitted() { return submitted; }

    public long incrementRevision() { return messageRevision.incrementAndGet(); }
    public long incrementGeneration() { return generation.incrementAndGet(); }
    public int incrementRegenerateCount() { regenerateCount++; return regenerateCount; }
    public void markSubmitted() { this.submitted = true; }

    public void addMessage(String text) {
        synchronized (pendingMessages) {
            pendingMessages.add(new MessageEntry(text, System.currentTimeMillis()));
        }
    }

    public void addImageInfo(String url, String file) {
        synchronized (imageInfos) {
            imageInfos.add(new ImageInfo(url, file));
        }
    }

    public void addLink(String url) {
        if (url != null && !url.isEmpty()) {
            synchronized (linksToFetch) {
                linksToFetch.add(url);
            }
        }
    }

    public void setReplyToMessageId(Long msgId) { this.replyToMessageId = msgId; }
    public Long getReplyToMessageId() { return replyToMessageId; }

    /** 获取当前所有待处理消息的快照 */
    public List<MessageEntry> getPendingMessages() {
        synchronized (pendingMessages) {
            return List.copyOf(pendingMessages);
        }
    }

    /** 获取当前所有图片信息的快照 */
    public List<ImageInfo> getImageInfos() {
        synchronized (imageInfos) {
            return List.copyOf(imageInfos);
        }
    }

    /** 获取当前所有链接的快照 */
    public List<String> getLinksToFetch() {
        synchronized (linksToFetch) {
            return List.copyOf(linksToFetch);
        }
    }

    /** 合并所有待处理消息文本 */
    public String getMergedText() {
        synchronized (pendingMessages) {
            return pendingMessages.stream()
                    .map(e -> e.text)
                    .filter(t -> !t.isEmpty())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    public boolean hasContent() {
        synchronized (pendingMessages) {
            return pendingMessages.stream().anyMatch(e -> !e.text.isEmpty())
                    || !imageInfos.isEmpty();
        }
    }

    public record MessageEntry(String text, long timestamp) {}

    public record ImageInfo(String url, String file) {}
}
