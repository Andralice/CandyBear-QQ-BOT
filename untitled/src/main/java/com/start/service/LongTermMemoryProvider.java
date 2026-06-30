package com.start.service;

import com.start.config.DatabaseConfig;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * LongTermMemoryRepository 的 MemoryProvider 适配器。
 */
public class LongTermMemoryProvider implements MemoryProvider {

    private final LongTermMemoryRepository repo;

    public LongTermMemoryProvider() {
        this.repo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
    }

    @Override
    public String name() { return "long_term"; }

    @Override
    public List<MemoryEntry> search(MemoryQuery query) {
        List<MemoryEntry> entries = new ArrayList<>();
        try {
            List<LongTermMemory> results = repo.search(query.userId(), query.groupId(),
                    query.keyword(), query.limit());
            for (LongTermMemory m : results) {
                long ts = m.getCreatedAt() != null
                        ? m.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                        : 0;
                entries.add(new MemoryEntry(
                        m.getMemoryType() != null ? m.getMemoryType() : "fact",
                        m.getContent(),
                        name(),
                        ts));
            }
        } catch (SQLException e) {
            // 静默处理，调用方会记录 warn
            throw new RuntimeException(e);
        }
        return entries;
    }
}
