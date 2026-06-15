package com.start.repository;

import com.start.model.UserProfession;

import javax.sql.DataSource;
import java.sql.*;

public class UserProfessionRepository {

    private final DataSource dataSource;

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
        fresh.setCombatPower(ProfessionPath.randomPower(1, userId, groupId));
        insert(fresh);
        return fresh;
    }

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

    public void insert(UserProfession p) throws SQLException {
        String sql = "INSERT INTO user_professions (user_id, group_id, profession_path, profession_name, tier, rarity, combat_power, streak_good, streak_bad) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) p.setId(keys.getLong(1));
            }
        }
    }

    public void update(UserProfession p) throws SQLException {
        String sql = "UPDATE user_professions SET profession_path=?, profession_name=?, tier=?, rarity=?, combat_power=?, streak_good=?, streak_bad=?, updated_at=NOW() WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getProfessionPath());
            ps.setString(2, p.getProfessionName());
            ps.setInt(3, p.getTier());
            ps.setString(4, p.getRarity());
            ps.setInt(5, p.getCombatPower());
            ps.setInt(6, p.getStreakGood());
            ps.setInt(7, p.getStreakBad());
            ps.setLong(8, p.getId());
            ps.executeUpdate();
        }
    }

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
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) p.setUpdatedAt(ua.toLocalDateTime());
        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return p;
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
        static final int[][] POWER_RANGES = {
            {100, 300}, {300, 800}, {800, 2000}, {2000, 5000}, {5000, 10000}
        };

        public static String randomPath(long userId) {
            return PATHS[(int) (Math.abs(userId * 0x9E3779B9L) % PATHS.length)];
        }

        public static String entryName(String path, int tier) {
            for (int i = 0; i < PATHS.length; i++) {
                if (PATHS[i].equals(path)) return NAMES[i][Math.min(tier, 5) - 1];
            }
            return NAMES[0][0];
        }

        public static String rarityForTier(int tier) {
            return RARITY_NAMES[Math.min(Math.max(tier, 1), 5) - 1];
        }

        public static int randomPower(int tier, long userId, String groupId) {
            long seed = com.start.util.SeedUtil.seed(String.valueOf(userId), groupId, "power", java.time.LocalDate.now().toString());
            java.util.Random rand = new java.util.Random(seed);
            int[] range = POWER_RANGES[Math.min(Math.max(tier, 1), 5) - 1];
            return range[0] + rand.nextInt(range[1] - range[0] + 1);
        }
    }
}
