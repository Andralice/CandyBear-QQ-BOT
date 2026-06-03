package com.start.repository;

import com.start.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * 别称 + 两级地点存储。
 *
 * 地点规则：
 * - primary_location: 用户说"我在北京" → 覆盖旧 primary
 * - secondary_location: 用户说"查深圳天气" → 覆盖旧 secondary
 * - 查天气默认用 primary，无则用 secondary，都无则问用户
 *
 * 别称规则：
 * - BOT_ALIAS: 糖果熊自己的别称
 * - 同群同别称唯一，冲突拒绝
 */
public class UserAliasRepository extends BaseRepository {
    private static final Logger logger = LoggerFactory.getLogger(UserAliasRepository.class);

    /** 记录别称，返回 ok / conflict:uid / error:msg */
    public String recordAlias(String targetUserId, String groupId, String aliasName,
                              String aliasType, String setByUserId) {
        aliasName = aliasName.trim();
        String checkSql = groupId != null
                ? "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND group_id=? AND target_user_id!=? LIMIT 1"
                : "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND group_id IS NULL AND target_user_id!=? LIMIT 1";
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, aliasName);
            if (groupId != null) { ps.setString(2, groupId); ps.setString(3, targetUserId); }
            else { ps.setString(2, targetUserId); }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return "conflict:" + rs.getString("target_user_id");
            }
        } catch (SQLException e) { logger.error("检查别称冲突失败", e); return "error:查询失败"; }

        if ("SUBJECTIVE".equals(aliasType)) {
            executeUpdate("DELETE FROM user_aliases WHERE target_user_id=? AND group_id<=>? AND alias_type='SUBJECTIVE'",
                    targetUserId, groupId);
        }
        executeUpdate("INSERT INTO user_aliases (target_user_id,group_id,alias_name,alias_type,set_by_user_id) " +
                "VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE usage_count=usage_count+1",
                targetUserId, groupId, aliasName, aliasType, setByUserId);
        if ("BOT_ALIAS".equals(aliasType)) {
            executeUpdate("INSERT INTO user_aliases (target_user_id,group_id,alias_name,alias_type,set_by_user_id) " +
                    "VALUES (?,?,?,'BOT_ALIAS',?) ON DUPLICATE KEY UPDATE usage_count=usage_count+1",
                    targetUserId, groupId, aliasName, setByUserId);
        }
        logger.info("📝 别称: target={} alias={} type={}", targetUserId, aliasName, aliasType);
        return "ok";
    }

    /** 更新地点。primary 覆盖 primary，secondary 覆盖 secondary */
    public void updateLocation(String userId, String groupId, String location, boolean isPrimary) {
        String col = isPrimary ? "primary_location" : "secondary_location";
        String sql = "UPDATE user_aliases SET " + col + "=?, location_updated_at=NOW() WHERE target_user_id=? AND (group_id=? OR group_id IS NULL)";
        int rows = executeUpdate(sql, location.trim(), userId, groupId).getDataOrElse(0);
        if (rows == 0) {
            executeUpdate("INSERT INTO user_aliases (target_user_id,group_id,alias_name,alias_type,set_by_user_id," + col + ",location_updated_at) " +
                    "VALUES (?,?,'__location_only__','OBJECTIVE',?,?,NOW())", userId, groupId, userId, location.trim());
        }
        logger.info("📍 地点{}: user={} group={} loc={}", isPrimary ? "(主)" : "(次)", userId, groupId, location);
    }

    /** 获取最佳地点：primary > secondary，按群过滤 */
    public Optional<String> getLocation(String userId, String groupId) {
        var r = executeQuerySingle(
                "SELECT primary_location, secondary_location FROM user_aliases WHERE target_user_id=? " +
                "AND (group_id=? OR group_id IS NULL) " +
                "AND (primary_location IS NOT NULL OR secondary_location IS NOT NULL) " +
                "ORDER BY location_updated_at DESC LIMIT 1",
                rs -> {
                    try {
                        String p = rs.getString("primary_location");
                        if (p != null && !p.isEmpty()) return p;
                        return rs.getString("secondary_location");
                    } catch (SQLException e) { return null; }
                }, userId, groupId);
        return Optional.ofNullable(r.isSuccess() ? r.getData() : null);
    }

    /** 最佳别称：主观 > 客观 > 无，按群过滤，排除占位符 */
    public Optional<String> getBestAlias(String targetUserId, String groupId) {
        for (String type : List.of("SUBJECTIVE", "OBJECTIVE")) {
            var r = executeQuerySingle(
                    "SELECT alias_name FROM user_aliases WHERE target_user_id=? AND alias_type=? " +
                    "AND (group_id=? OR group_id IS NULL) AND alias_name != '__location_only__' LIMIT 1",
                    rs -> { try { return rs.getString("alias_name"); } catch (SQLException e) { return null; } },
                    targetUserId, type, groupId);
            if (r.isSuccess() && r.getData() != null) return Optional.of(r.getData());
        }
        return Optional.empty();
    }

    /** 获取机器人的所有别称 */
    public List<String> getBotAliases() {
        return executeQuery(
                "SELECT alias_name FROM user_aliases WHERE alias_type='BOT_ALIAS'",
                rs -> { try { return rs.getString("alias_name"); } catch (SQLException e) { return null; } })
                .getDataOrElse(Collections.emptyList());
    }

    /** 通过别称反查用户（排除机器人别称），group_id 宽松匹配 */
    public Optional<String> resolveAlias(String aliasName, String groupId) {
        // 优先精确匹配 group_id，否则忽略 group_id 查
        var r = executeQuerySingle(
                "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND (group_id=? OR group_id IS NULL) " +
                "AND alias_type != 'BOT_ALIAS' LIMIT 1",
                rs -> { try { return rs.getString("target_user_id"); } catch (SQLException e) { return null; } },
                aliasName, groupId);
        if (r.isSuccess() && r.getData() != null) return Optional.of(r.getData());

        // 宽松匹配：忽略 group_id
        r = executeQuerySingle(
                "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND alias_type != 'BOT_ALIAS' LIMIT 1",
                rs -> { try { return rs.getString("target_user_id"); } catch (SQLException e) { return null; } },
                aliasName);
        return Optional.ofNullable(r.isSuccess() ? r.getData() : null);
    }

    /** 修改别称名（把旧别称改成新别称） */
    public String updateAlias(String targetUserId, String groupId, String oldAlias, String newAlias) {
        oldAlias = oldAlias.trim();
        newAlias = newAlias.trim();
        // 先检查新别称是否已被占用
        String checkSql = groupId != null
                ? "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND group_id=? AND target_user_id!=? LIMIT 1"
                : "SELECT target_user_id FROM user_aliases WHERE alias_name=? AND group_id IS NULL AND target_user_id!=? LIMIT 1";
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, newAlias);
            if (groupId != null) { ps.setString(2, groupId); ps.setString(3, targetUserId); }
            else { ps.setString(2, targetUserId); }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return "conflict:" + rs.getString("target_user_id");
            }
        } catch (SQLException e) { logger.error("检查别称冲突失败", e); return "error:查询失败"; }

        int rows = executeUpdate(
                "UPDATE user_aliases SET alias_name=? WHERE target_user_id=? AND alias_name=? AND (group_id=? OR group_id IS NULL)",
                newAlias, targetUserId, oldAlias, groupId).getDataOrElse(0);
        if (rows == 0) return "not_found:未找到别称「" + oldAlias + "」";
        logger.info("✏️ 别称更新: {} {} → {}", targetUserId, oldAlias, newAlias);
        return "ok";
    }

    /** 删除一个别称 */
    public String deleteAlias(String targetUserId, String groupId, String aliasName) {
        aliasName = aliasName.trim();
        int rows = executeUpdate(
                "DELETE FROM user_aliases WHERE target_user_id=? AND alias_name=? AND (group_id=? OR group_id IS NULL)",
                targetUserId, aliasName, groupId).getDataOrElse(0);
        if (rows == 0) return "not_found:未找到别称「" + aliasName + "」";
        logger.info("🗑️ 别称删除: {} {} -> {}", targetUserId, groupId, aliasName);
        return "ok";
    }

    /** 获取群内所有别称+地点信息 */
    public Map<String, AliasInfo> getGroupAliasInfoMap(String groupId) {
        Map<String, AliasInfo> map = new LinkedHashMap<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target_user_id, alias_name, alias_type, usage_count, primary_location, secondary_location " +
                     "FROM user_aliases WHERE group_id=? OR group_id IS NULL " +
                     "ORDER BY FIELD(alias_type,'SUBJECTIVE','OBJECTIVE','BOT_ALIAS'), usage_count DESC")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("target_user_id");
                    AliasInfo info = map.computeIfAbsent(uid, k -> new AliasInfo());
                    String aName = rs.getString("alias_name");
                    if (info.bestAlias == null && !"__location_only__".equals(aName)) info.bestAlias = aName;
                    String loc1 = rs.getString("primary_location");
                    String loc2 = rs.getString("secondary_location");
                    if (info.primaryLocation == null && loc1 != null && !loc1.isEmpty())
                        info.primaryLocation = loc1;
                    if (info.secondaryLocation == null && loc2 != null && !loc2.isEmpty())
                        info.secondaryLocation = loc2;
                    if (!"__location_only__".equals(aName)) info.aliases.add(aName);
                }
            }
        } catch (SQLException e) { logger.error("查询群别称失败", e); }
        return map;
    }

    public static class AliasInfo {
        public String bestAlias;
        public String primaryLocation;
        public String secondaryLocation;
        public List<String> aliases = new ArrayList<>();
    }
}
