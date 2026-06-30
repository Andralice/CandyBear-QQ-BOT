package com.start.service;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理 ConversationState 生命周期。State 是临时的：首条消息创建，回复发送后销毁。
 */
public class ConversationManager {
    private static final Logger logger = LoggerFactory.getLogger(ConversationManager.class);

    private final ConcurrentHashMap<String, ConversationState> states = new ConcurrentHashMap<>();

    public ConversationState getOrCreate(String groupId, String userId) {
        String key = key(groupId, userId);
        ConversationState existing = states.get(key);
        if (existing != null) return existing;
        return states.computeIfAbsent(key, k -> new ConversationState(groupId, userId));
    }

    public ConversationState get(String groupId, String userId) {
        return states.get(key(groupId, userId));
    }

    public ConversationState remove(String groupId, String userId) {
        ConversationState removed = states.remove(key(groupId, userId));
        if (removed != null) {
            logger.debug("ConversationState removed: {}_{}", groupId, userId);
        }
        return removed;
    }

    private static String key(String groupId, String userId) {
        return groupId + "_" + userId;
    }
}
