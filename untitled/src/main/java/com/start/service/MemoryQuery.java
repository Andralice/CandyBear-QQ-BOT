package com.start.service;

/** 记忆查询参数 */
public record MemoryQuery(String userId, String groupId, String keyword, int limit) {
    public MemoryQuery {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
    }

    public static MemoryQuery of(String userId, String groupId, String keyword) {
        return new MemoryQuery(userId, groupId, keyword, 5);
    }
}
