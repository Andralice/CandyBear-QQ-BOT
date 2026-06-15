package com.start.service;

import com.start.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时配置服务 — 热重载提示词、工具描述等。
 * 内存缓存 + DB 持久化，改完即生效，无需重启。
 */
public class RuntimeConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final DataSource dataSource;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public RuntimeConfigService() {
        this.dataSource = DatabaseConfig.getDataSource();
        loadAll();
    }

    /** 获取配置值，不存在返回 null */
    public String get(String key) {
        return cache.get(key);
    }

    /** 获取配置值，不存在返回默认值 */
    public String get(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    /** 设置配置（内存 + DB），立即生效 */
    public boolean set(String key, String value, String updatedBy) {
        cache.put(key, value);
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO bot_config (config_key, config_value, updated_by) VALUES (?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), updated_by = VALUES(updated_by)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, updatedBy != null ? updatedBy : "system");
                ps.executeUpdate();
            }
            logger.info("配置已更新: {} by {}", key, updatedBy);
            return true;
        } catch (SQLException e) {
            logger.error("更新配置失败: {}", key, e);
            return false;
        }
    }

    /** 列出所有配置键 */
    public Map<String, String> listAll() {
        return Map.copyOf(cache);
    }

    private void loadAll() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT config_key, config_value FROM bot_config")) {
            while (rs.next()) {
                cache.put(rs.getString("config_key"), rs.getString("config_value"));
            }
            logger.info("已加载 {} 条运行时配置", cache.size());
        } catch (SQLException e) {
            logger.warn("加载运行时配置失败（表可能尚未创建）: {}", e.getMessage());
        }
    }
}
