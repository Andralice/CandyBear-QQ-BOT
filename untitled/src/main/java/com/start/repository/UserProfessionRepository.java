package com.start.repository;

import com.start.model.ProfessionDailyLog;
import com.start.model.UserProfession;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.sql.*;

public class UserProfessionRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public UserProfessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 查询或创建用户职业（按群隔离） */
    public UserProfession findOrCreate(long userId, String groupId) throws SQLException {
        UserProfession existing = findByUser(userId, groupId);
        if (existing != null) return existing;

        UserProfession fresh = new UserProfession();
        fresh.setUserId(userId);
        fresh.setGroupId(groupId);
        fresh.setProfessionPath(ProfessionPath.randomPath(userId));
        fresh.setProfessionName(ProfessionPath.entryName(fresh.getProfessionPath(), 1));
        fresh.setTier(1);
        fresh.setRarity("普通");
        fresh.setBestTier(1);
        fresh.setCombatPower(ProfessionPath.randomPower(1, userId, groupId));
        insert(fresh);
        return fresh;
    }

    /** 根据用户ID和群ID查询职业记录 */
    public UserProfession findByUser(long userId, String groupId) throws SQLException {
        String sql = "SELECT * FROM user_professions WHERE user_id = ? AND group_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** 插入新用户职业记录 */
    public void insert(UserProfession p) throws SQLException {
        String sql = "INSERT INTO user_professions (user_id, group_id, profession_path, profession_name, tier, rarity, combat_power, streak_good, streak_bad, best_tier) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, p.getUserId());
            ps.setString(2, p.getGroupId());
            ps.setString(3, p.getProfessionPath());
            ps.setString(4, p.getProfessionName());
            ps.setInt(5, p.getTier());
            ps.setString(6, p.getRarity());
            ps.setInt(7, p.getCombatPower());
            ps.setInt(8, p.getStreakGood());
            ps.setInt(9, p.getStreakBad());
            ps.setInt(10, p.getBestTier());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) p.setId(keys.getLong(1));
            }
        }
    }

    /** 更新用户职业记录 */
    public void update(UserProfession p) throws SQLException {
        String sql = "UPDATE user_professions SET profession_path=?, profession_name=?, tier=?, rarity=?, combat_power=?, streak_good=?, streak_bad=?, best_tier=?, updated_at=NOW() WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getProfessionPath());
            ps.setString(2, p.getProfessionName());
            ps.setInt(3, p.getTier());
            ps.setString(4, p.getRarity());
            ps.setInt(5, p.getCombatPower());
            ps.setInt(6, p.getStreakGood());
            ps.setInt(7, p.getStreakBad());
            ps.setInt(8, p.getBestTier());
            ps.setLong(9, p.getId());
            ps.executeUpdate();
        }
    }

    /** 将ResultSet映射为UserProfession对象 */
    private UserProfession mapRow(ResultSet rs) throws SQLException {
        UserProfession p = new UserProfession();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setGroupId(rs.getString("group_id"));
        p.setProfessionPath(rs.getString("profession_path"));
        p.setProfessionName(rs.getString("profession_name"));
        p.setTier(rs.getInt("tier"));
        p.setRarity(rs.getString("rarity"));
        p.setCombatPower(rs.getInt("combat_power"));
        p.setStreakGood(rs.getInt("streak_good"));
        p.setStreakBad(rs.getInt("streak_bad"));
        p.setBestTier(rs.getInt("best_tier"));
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) p.setUpdatedAt(ua.toLocalDateTime());
        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return p;
    }

    /** 查询用户在群内的战力排名（1-based，战力越高排名越前） */
    public int getGroupRank(long userId, String groupId) {
        String sql = "SELECT COUNT(*) + 1 FROM user_professions WHERE group_id = ? AND user_id != ? AND combat_power > (SELECT combat_power FROM user_professions WHERE user_id = ? AND group_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setLong(2, userId);
            ps.setLong(3, userId);
            ps.setString(4, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            // ignore, return 0
        }
        return 0;
    }

    /** 获取群内总参与人数 */
    public int getGroupTotal(String groupId) {
        String sql = "SELECT COUNT(*) FROM user_professions WHERE group_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    // ── 每日变动日志 ──

    /** 插入或更新每日职业变动日志（幂等写入） */
    public void upsertDailyLog(ProfessionDailyLog log) {
        String sql = "INSERT INTO profession_daily_logs (user_id, group_id, log_date, profession_path, profession_name, tier, rarity, yesterday_power, base_power, power_from_luck, power_from_pk, final_power, luck_value, change_summary) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE base_power=VALUES(base_power), power_from_luck=VALUES(power_from_luck), power_from_pk=VALUES(power_from_pk), final_power=VALUES(final_power), luck_value=VALUES(luck_value), change_summary=VALUES(change_summary)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, log.getUserId());
            ps.setString(2, log.getGroupId());
            ps.setDate(3, java.sql.Date.valueOf(log.getLogDate()));
            ps.setString(4, log.getProfessionPath());
            ps.setString(5, log.getProfessionName());
            ps.setInt(6, log.getTier());
            ps.setString(7, log.getRarity());
            ps.setInt(8, log.getYesterdayPower());
            ps.setInt(9, log.getBasePower());
            ps.setInt(10, log.getPowerFromLuck());
            ps.setInt(11, log.getPowerFromPk());
            ps.setInt(12, log.getFinalPower());
            ps.setInt(13, log.getLuckValue());
            ps.setString(14, log.getChangeSummary());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsert daily log failed", e);
        }
    }

    /** 查询指定日期的每日变动日志 */
    public ProfessionDailyLog getDailyLog(long userId, String groupId, LocalDate date) {
        String sql = "SELECT * FROM profession_daily_logs WHERE user_id=? AND group_id=? AND log_date=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, groupId);
            ps.setDate(3, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ProfessionDailyLog log = new ProfessionDailyLog();
                    log.setUserId(rs.getLong("user_id"));
                    log.setGroupId(rs.getString("group_id"));
                    log.setLogDate(rs.getDate("log_date").toLocalDate());
                    log.setProfessionPath(rs.getString("profession_path"));
                    log.setProfessionName(rs.getString("profession_name"));
                    log.setTier(rs.getInt("tier"));
                    log.setRarity(rs.getString("rarity"));
                    log.setYesterdayPower(rs.getInt("yesterday_power"));
                    log.setBasePower(rs.getInt("base_power"));
                    log.setPowerFromLuck(rs.getInt("power_from_luck"));
                    log.setPowerFromPk(rs.getInt("power_from_pk"));
                    log.setFinalPower(rs.getInt("final_power"));
                    log.setLuckValue(rs.getInt("luck_value"));
                    log.setChangeSummary(rs.getString("change_summary"));
                    return log;
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /** 更新每日日志中的PK战力变化 */
    public void updateDailyLogPK(long userId, String groupId, LocalDate date, int pkDelta, int newFinalPower) {
        String sql = "UPDATE profession_daily_logs SET power_from_pk = power_from_pk + ?, final_power = ? WHERE user_id=? AND group_id=? AND log_date=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pkDelta);
            ps.setInt(2, newFinalPower);
            ps.setLong(3, userId);
            ps.setString(4, groupId);
            ps.setDate(5, java.sql.Date.valueOf(date));
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    /** 脉系常量 */
    public static class ProfessionPath {
        public static final String[] PATHS = {
            "剑修", "法神", "刺客", "丹道", "御兽", "佛修", "符箓", "搞笑"
        };

        // 每个脉系的 1-5 阶职业名
        static final String[][] NAMES = {
            {"见习剑客", "御剑游侠", "剑心通明", "剑道宗师", "万剑归宗"},
            {"魔法学徒", "元素使", "大魔导师", "法神", "时空掌控者"},
            {"潜行者", "暗影猎手", "夜刃大师", "影舞者", "死亡领主"},
            {"采药童子", "炼丹师", "丹道大师", "丹圣", "造化丹尊"},
            {"驯兽学徒", "御兽师", "万兽之主", "龙骑统帅", "太古兽神"},
            {"沙弥", "苦行僧", "罗汉", "菩萨", "佛祖"},
            {"画符小童", "符箓师", "天符大师", "符道圣手", "道祖"},
            {"摸鱼学徒", "躺平真人", "社畜剑圣", "996符咒师", "带薪修仙者"}
        };

        static final String[] RARITY_NAMES = {"普通", "普通", "稀有", "史诗", "传说"};

        // 每个位阶的战力范围
        public static final int[][] POWER_RANGES = {
            {100, 300}, {300, 800}, {800, 2000}, {2000, 5000}, {5000, 10000}
        };

        /** 根据用户ID随机分配脉系（确定性算法） */
        public static String randomPath(long userId) {
            return PATHS[(int) (Math.abs(userId * 0x9E3779B9L) % PATHS.length)];
        }

        /** 获取指定脉系和阶位的职业名称 */
        public static String entryName(String path, int tier) {
            for (int i = 0; i < PATHS.length; i++) {
                if (PATHS[i].equals(path)) return NAMES[i][Math.min(tier, 5) - 1];
            }
            return NAMES[0][0];
        }

        /** 根据阶位获取稀有度名称 */
        public static String rarityForTier(int tier) {
            return RARITY_NAMES[Math.min(Math.max(tier, 1), 5) - 1];
        }

        /** 根据阶位和用户信息生成随机战力（确定性算法） */
        public static int randomPower(int tier, long userId, String groupId) {
            long seed = com.start.util.SeedUtil.seed(String.valueOf(userId), groupId, "power", java.time.LocalDate.now().toString());
            java.util.Random rand = new java.util.Random(seed);
            int[] range = POWER_RANGES[Math.min(Math.max(tier, 1), 5) - 1];
            return range[0] + rand.nextInt(range[1] - range[0] + 1);
        }
    }
}