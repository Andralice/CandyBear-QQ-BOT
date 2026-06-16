package com.start.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvolutionRecordRepository {

    private static final Logger logger = LoggerFactory.getLogger(EvolutionRecordRepository.class);

    private final DataSource dataSource;

    public EvolutionRecordRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(String targetFile, String reason, String result, String errorMessage, boolean gitPushed) {
        String sql = "INSERT INTO evolution_records (target_file, reason, result, error_message, git_pushed) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetFile);
            ps.setString(2, reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
            ps.setString(3, result);
            ps.setString(4, errorMessage != null && errorMessage.length() > 2000 ? errorMessage.substring(0, 2000) : errorMessage);
            ps.setBoolean(5, gitPushed);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("记录进化历史失败: {}", e.getMessage());
        }
    }

    /** 查询最近 N 条进化记录 */
    public List<Map<String, Object>> queryRecent(int limit) {
        String sql = "SELECT * FROM evolution_records ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("target_file", rs.getString("target_file"));
                row.put("reason", rs.getString("reason"));
                row.put("result", rs.getString("result"));
                row.put("error_message", rs.getString("error_message"));
                row.put("git_pushed", rs.getBoolean("git_pushed"));
                row.put("created_at", rs.getTimestamp("created_at").toString());
                results.add(row);
            }
        } catch (SQLException e) {
            logger.warn("查询进化历史失败: {}", e.getMessage());
        }
        return results;
    }

    /** 按结果类型统计 */
    public Map<String, Integer> countByResult() {
        String sql = "SELECT result, COUNT(*) AS cnt FROM evolution_records GROUP BY result";
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString("result"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            logger.warn("进化统计失败: {}", e.getMessage());
        }
        return map;
    }
}
