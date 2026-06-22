package com.start.repository;

import com.start.service.BotMemoryService;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 糖果熊自身记忆的数据库持久化。
 */
public class BotMemoryRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public BotMemoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(String groupId, BotMemoryService.EntryType type, String target, String detail) throws SQLException {
        String sql = "INSERT INTO bot_memories (group_id, entry_type, target, detail) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, type.name());
            ps.setString(3, target);
            ps.setString(4, detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail);
            ps.executeUpdate();
        }
    }

    /** 查询最近记忆，返回格式与 BotMemoryService.MemoryEntry 一致 */
    public List<String> query(String groupId, int count, String typeFilter, String keyword) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM bot_memories WHERE group_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(groupId);

        if (typeFilter != null && !typeFilter.isBlank()) {
            sql.append(" AND entry_type = ?");
            params.add(typeFilter.toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (detail LIKE ? OR target LIKE ?)");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }

        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(count);

        List<String> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long ago = (System.currentTimeMillis() - rs.getTimestamp("created_at").getTime()) / 1000;
                String time = ago < 60 ? ago + "秒前" : ago < 3600 ? (ago / 60) + "分钟前" : (ago / 3600) + "小时前";
                results.add(time + " | " + rs.getString("entry_type") + " | "
                        + (rs.getString("target") != null ? rs.getString("target") : "")
                        + " | " + rs.getString("detail"));
            }
        }
        return results;
    }
}
