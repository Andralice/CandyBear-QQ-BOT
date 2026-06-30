package com.start.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一记忆服务。聚合多个 MemoryProvider，提供单一查询入口。
 * 不做存储，只做聚合查询。
 */
public class MemoryService {
    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final List<MemoryProvider> providers = new ArrayList<>();

    public void register(MemoryProvider provider) {
        providers.add(provider);
        logger.info("MemoryProvider registered: {}", provider.name());
    }

    /**
     * 用关键词列表检索所有 Provider，返回格式化的 prompt 上下文。
     * @return 格式化的记忆上下文，无结果时返回空字符串
     */
    public String queryForPrompt(String userId, String groupId, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return "";

        Set<String> seen = new LinkedHashSet<>(); // 按 content 去重
        List<MemoryEntry> merged = new ArrayList<>();

        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (merged.size() >= 10) break;

            for (MemoryProvider p : providers) {
                try {
                    List<MemoryEntry> results = p.search(MemoryQuery.of(userId, groupId, kw));
                    for (MemoryEntry e : results) {
                        String dedupKey = e.provider() + ":" + e.content();
                        if (seen.add(dedupKey)) {
                            merged.add(e);
                            if (merged.size() >= 10) break;
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("MemoryProvider {} search failed: {}", p.name(), ex.getMessage());
                }
            }
        }

        if (merged.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【关于该用户的长期记忆（自动召回）】");
        sb.append("\n以下是你之前记住的关于 ").append(userId).append(" 的信息，可在对话中自然引用：");
        for (int i = 0; i < merged.size(); i++) {
            MemoryEntry e = merged.get(i);
            sb.append("\n").append(i + 1).append(". [").append(e.type()).append("] ");
            sb.append(e.content());
            if (e.timestamp() > 0) {
                LocalDateTime t = LocalDateTime.ofEpochSecond(e.timestamp() / 1000, 0,
                        java.time.ZoneOffset.ofHours(8));
                sb.append(" （").append(t.format(FMT)).append("）");
            }
        }
        return sb.toString();
    }
}
