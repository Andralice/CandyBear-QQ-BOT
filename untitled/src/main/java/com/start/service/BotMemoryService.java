package com.start.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 糖果熊短期记忆：记录最近说的话、做的事、调的工具。
 * 每个群保留最近 80 条，AI 可通过 query_memory 工具查询。
 */
public class BotMemoryService {

    private final Map<String, Deque<MemoryEntry>> groupMemory = new ConcurrentHashMap<>();
    private static final int MAX_ENTRIES = 80;

    public enum EntryType {
        SAID,          // 群内发言
        PRIVATE_SENT,  // 发了私聊
        TOOL_CALLED,   // 调用了工具
        POKED,         // 戳了人
        VOICE,         // 发了语音
        REMINDER_SET,  // 设置了提醒
        GAME_STARTED   // 开始了游戏
    }

    public record MemoryEntry(long timestamp, EntryType type, String target, String detail) {
        public String toString() {
            long ago = (System.currentTimeMillis() - timestamp) / 1000;
            String time = ago < 60 ? ago + "秒前" : ago < 3600 ? (ago / 60) + "分钟前" : (ago / 3600) + "小时前";
            return time + " | " + type + " | " + (target != null ? target : "") + " | " + detail;
        }
    }

    public void record(String groupId, EntryType type, String target, String detail) {
        Deque<MemoryEntry> q = groupMemory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());
        q.addLast(new MemoryEntry(System.currentTimeMillis(), type, target, detail));
        while (q.size() > MAX_ENTRIES) q.removeFirst();
    }

    /** 查询最近的记忆 */
    public String query(String groupId, int count, String typeFilter, String keyword) {
        Deque<MemoryEntry> q = groupMemory.get(groupId);
        if (q == null || q.isEmpty()) return "你还没有做过任何事，没有记忆记录。如实告诉用户即可，不要编理由。";

        List<MemoryEntry> list = new ArrayList<>(q);
        Collections.reverse(list); // 最新在前

        StringBuilder sb = new StringBuilder("糖果熊最近做的事：\n");
        int shown = 0;
        for (MemoryEntry e : list) {
            if (typeFilter != null && !typeFilter.isEmpty() && !e.type.name().contains(typeFilter.toUpperCase())) continue;
            if (keyword != null && !keyword.isEmpty() && !e.detail.contains(keyword) && (e.target == null || !e.target.contains(keyword))) continue;
            sb.append("- ").append(e.toString()).append("\n");
            shown++;
            if (count > 0 && shown >= count) break;
        }
        if (shown == 0) sb.append("（没有匹配的记录，如实告诉用户，不要编理由）");
        return sb.toString();
    }
}
