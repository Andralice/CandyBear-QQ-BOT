package com.start.service;

import com.start.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时配置服务 — 热重载提示词、工具描述等。
 * 内存缓存 + DB 持久化，改完即生效，无需重启。
 *
 * 提示词保护：
 * - system_prompt_override / system_prompt_patch 写入前自动备份旧值
 * - 非归儿发起的提示词修改 → 暂存为提案，需归儿确认后生效
 */
public class RuntimeConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeConfigService.class);
    private static final Set<String> PROMPT_KEYS = Set.of("system_prompt_override", "system_prompt_patch");

    private final DataSource dataSource;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 待确认提案：id → Proposal */
    private final Map<Integer, Proposal> proposals = new ConcurrentHashMap<>();
    private final AtomicInteger proposalSeq = new AtomicInteger(1);

    public RuntimeConfigService() {
        this.dataSource = DatabaseConfig.getDataSource();
        loadAll();
    }

    // ==================== 基础读写 ====================

    public String get(String key) {
        return cache.get(key);
    }

    public String get(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    /** 直接写入（含自动备份），仅归儿走此路径 */
    public String directSet(String key, String value, String updatedBy) {
        if (PROMPT_KEYS.contains(key)) {
            backupOldValue(key);
        }
        boolean ok = set(key, value, updatedBy);
        if (ok) {
            logger.info("配置已直接更新: {} by {}", key, updatedBy);
            return "配置已更新: " + key + "（已生效）\n旧值已备份，可用 update_config action=restore key=" + key + " 回滚。";
        }
        return "配置更新失败: " + key;
    }

    private boolean set(String key, String value, String updatedBy) {
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
            return true;
        } catch (SQLException e) {
            logger.error("更新配置失败: {}", key, e);
            return false;
        }
    }

    // ==================== 自动备份 ====================

    private void backupOldValue(String key) {
        String oldValue = cache.get(key);
        if (oldValue == null || oldValue.isEmpty()) return;
        String backupKey = key + ".bak." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO bot_config (config_key, config_value, updated_by) VALUES (?, ?, 'backup')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, backupKey);
                ps.setString(2, oldValue);
                ps.executeUpdate();
            }
            cache.put(backupKey, oldValue);
            logger.info("提示词已备份: {}", backupKey);
            // 保留最近 5 个备份
            cleanupBackups(key);
        } catch (SQLException e) {
            logger.warn("备份失败: {}", key, e);
        }
    }

    private void cleanupBackups(String key) {
        String prefix = key + ".bak.";
        List<String> bakKeys = new ArrayList<>();
        for (String k : cache.keySet()) {
            if (k.startsWith(prefix)) bakKeys.add(k);
        }
        bakKeys.sort(Comparator.reverseOrder());
        for (int i = 5; i < bakKeys.size(); i++) {
            cache.remove(bakKeys.get(i));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM bot_config WHERE config_key = ?")) {
                ps.setString(1, bakKeys.get(i));
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 回滚到最近一次备份 */
    public String restore(String key) {
        String prefix = key + ".bak.";
        List<String> bakKeys = new ArrayList<>();
        for (String k : cache.keySet()) {
            if (k.startsWith(prefix)) bakKeys.add(k);
        }
        if (bakKeys.isEmpty()) return "没有找到 " + key + " 的备份。";
        bakKeys.sort(Comparator.reverseOrder());
        String latestBak = bakKeys.get(0);
        String oldValue = cache.get(latestBak);
        if (oldValue == null) return "备份值已丢失。";
        return directSet(key, oldValue, "restore");
    }

    // ==================== 提案暂存（提示词保护） ====================

    /**
     * 暂存一个提案，返回提案 ID。人工确认后才能生效。
     */
    public String propose(String key, String value, String proposer) {
        int id = proposalSeq.getAndIncrement();
        Proposal p = new Proposal(id, key, value, proposer);
        proposals.put(id, p);
        logger.info("提案已暂存: #{} key={} proposer={}", id, key, proposer);
        return "提案 #" + id + " 已暂存，等待归儿确认。\n" +
               "提案内容: [" + key + "] → " + (value.length() > 100 ? value.substring(0, 100) + "..." : value) + "\n" +
               "归儿回复「确认#" + id + "」生效，「撤回#" + id + "」丢弃。";
    }

    /** 列出所有待确认提案 */
    public String listProposals() {
        if (proposals.isEmpty()) return "当前没有待确认的提案。";
        StringBuilder sb = new StringBuilder("待确认提案:\n");
        for (Proposal p : proposals.values()) {
            String preview = p.value.length() > 80 ? p.value.substring(0, 80) + "..." : p.value;
            sb.append("  #").append(p.id).append(" [").append(p.key).append("] ")
              .append(preview).append(" (").append(p.proposer).append(")\n");
        }
        return sb.toString();
    }

    /** 确认提案 → 写入生效 */
    public String approveProposal(int id, String approver) {
        Proposal p = proposals.remove(id);
        if (p == null) return "提案 #" + id + " 不存在或已处理。";
        backupOldValue(p.key);
        boolean ok = set(p.key, p.value, approver);
        if (ok) {
            logger.info("提案 #{} 已确认生效: {} by {}", id, p.key, approver);
            return "提案 #" + id + " 已生效: " + p.key;
        }
        return "提案 #" + id + " 写入失败。";
    }

    /** 拒绝提案 → 丢弃 */
    public String rejectProposal(int id) {
        Proposal p = proposals.remove(id);
        if (p == null) return "提案 #" + id + " 不存在或已处理。";
        logger.info("提案 #{} 已拒绝: {}", id, p.key);
        return "提案 #" + id + " 已丢弃。";
    }

    // ==================== 列表 ====================

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

    // ==================== 内部类 ====================

    private static class Proposal {
        final int id;
        final String key;
        final String value;
        final String proposer;
        Proposal(int id, String key, String value, String proposer) {
            this.id = id;
            this.key = key;
            this.value = value;
            this.proposer = proposer;
        }
    }
}
