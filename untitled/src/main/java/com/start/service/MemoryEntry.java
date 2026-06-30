package com.start.service;

/** 统一记忆条目 */
public record MemoryEntry(String type, String content, String provider, long timestamp) {
    public static MemoryEntry of(String type, String content, String provider) {
        return new MemoryEntry(type, content, provider, System.currentTimeMillis());
    }
}
