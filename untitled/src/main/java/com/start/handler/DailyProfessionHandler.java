package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.model.ProfessionDailyLog;
import com.start.model.UserProfession;
import com.start.repository.UserProfessionRepository;
import com.start.util.LuckUtil;
import com.start.util.SeedUtil;
import com.start.vision.ImageRenderer;
import com.start.vision.ProfessionCardTemplate;
import com.start.vision.ProfessionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽职业（运势驱动位阶波动，DB 持久化有状态）
 */
public class DailyProfessionHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyProfessionHandler.class);

    private static final Set<String> TRIGGERS = Set.of(
            "今日职业", "抽职业", "我的职业", "今日命格", "抽命格", "抽取"
    );

    private static final UserProfessionRepository repo = new UserProfessionRepository(DatabaseConfig.getDataSource());

    private final Map<String, ProfessionResult> dailyCache = new ConcurrentHashMap<>();
    private final ImageRenderer renderer = ImageRenderer.getInstance();

    @Override
    public boolean match(JsonNode message) {
        if (!"group".equals(message.path("message_type").asText())) return false;
        return TRIGGERS.contains(message.path("raw_message").asText().trim());
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String groupId = message.get("group_id").asText();
        long userId = message.get("user_id").asLong();
        String cacheKey = groupId + ":" + userId + ":" + LocalDate.now();

        ProfessionResult result = dailyCache.get(cacheKey);
        if (result == null) {
            result = drawForUser(userId, groupId);
            dailyCache.put(cacheKey, result);
        }

        ProfessionData data = new ProfessionData(
                String.valueOf(userId),
                result.name,
                result.tier,
                getTierName(result.tier),
                result.description,
                result.rarity,
                result.combatPower
        );
        data.professionPath = result.path;
        data.todayLuck = result.todayLuck;
        data.changeDesc = result.changeDesc;
        data.streakGood = result.streakGood;
        data.streakBad = result.streakBad;
        data.bestTier = result.bestTier;
        data.bestTierName = getTierName(result.bestTier);
        data.groupRank = result.groupRank;

        String base64 = renderer.renderToBase64(new ProfessionCardTemplate(), data);
        if (base64 != null) {
            bot.sendGroupReply(Long.parseLong(groupId), "[CQ:image,file=base64://" + base64 + "]");
        } else {
            bot.sendGroupReply(Long.parseLong(groupId),
                    "✨ " + result.changeDesc + "\n【" + result.rarity + "】" +
                    result.name + "（" + getTierName(result.tier) + "）战力：" + result.combatPower);
        }

        logger.info("👤 群{} 用户{} 职业={} {}阶 [{}] 战力={} 运势={} {}",
                groupId, userId, result.name, result.tier, result.rarity,
                result.combatPower, result.todayLuck, result.changeDesc);
    }

    // ===== 核心逻辑：运势驱动位阶波动 =====

    /** 为用户抽取今日职业（有状态，运势驱动）。供 Handler、Tool、Rank 共用。 */
    public static ProfessionResult drawForUser(long userId, String groupId) {
        int luck = LuckUtil.getDailyLuck(userId);
        UserProfession p;
        try {
            p = repo.findOrCreate(userId, groupId);
        } catch (SQLException e) {
            logger.error("加载职业失败 userId={}", userId, e);
            // fallback：新号初始状态
            return fallbackResult(userId, luck);
        }

        // 特殊活动日：全员紫色史诗（必须在缓存判断之前，确保已抽过的人也能享受）
        boolean eventDay = LocalDate.now().getMonthValue() == 6 && LocalDate.now().getDayOfMonth() == 17;

        // 修正迁移前存量数据：bestTier 不应低于当前 tier
        int safeBestTier = Math.max(p.getTier(), p.getBestTier());

        // 今日已抽取过，直接返回当前值，保证同一天内多次查询结果一致
        // 但活动日需要纠正位阶、稀有度、战力（活动日之前可能已抽为非紫色），并回写DB
        if (p.getUpdatedAt() != null && p.getUpdatedAt().toLocalDate().equals(LocalDate.now())) {
            if (eventDay && (p.getTier() != 4 || p.getCombatPower() < 4500)) {
                p.setTier(4);
                p.setRarity("史诗");
                p.setProfessionName(UserProfessionRepository.ProfessionPath.entryName(p.getProfessionPath(), 4));
                p.setBestTier(Math.max(4, p.getBestTier()));
                p.setCombatPower(eventMaxPower(userId, groupId));
                try { repo.update(p); } catch (SQLException ignored) {}
            }
            int displayTier = eventDay ? 4 : p.getTier();
            String displayRarity = eventDay ? "史诗" : p.getRarity();
            String displayName = eventDay
                    ? UserProfessionRepository.ProfessionPath.entryName(p.getProfessionPath(), 4)
                    : p.getProfessionName();
            String desc = descriptionFor(p.getProfessionPath(), displayTier);
            String change = eventDay ? "💜 紫色史诗日！全员位阶飙升！" : "➡️ 今日已抽取";
            return new ProfessionResult(displayName, displayTier, displayRarity,
                    desc, p.getCombatPower(), luck, change,
                    p.getProfessionPath(), p.getStreakGood(), p.getStreakBad(),
                    Math.max(displayTier, safeBestTier), 0);
        }

        int oldTier = p.getTier();
        int newTier = computeNewTier(oldTier, luck, p.getStreakGood(), p.getStreakBad());

        if (eventDay) {
            newTier = 4;
        }

        // ── 运气战力大幅漂移 ──
        int yesterdayPower = p.getCombatPower();
        int power;
        int powerFromLuck;
        if (eventDay) {
            // 活动日：战力拉满到四阶最大值附近
            power = eventMaxPower(userId, groupId);
            powerFromLuck = power - yesterdayPower;
        } else if (newTier != oldTier) {
            // 位阶变化：战力量子跃迁到新位阶范围
            int baseNew = UserProfessionRepository.ProfessionPath.randomPower(newTier, userId, groupId);
            power = baseNew;
            powerFromLuck = power - yesterdayPower;
        } else {
            // 同位阶：运气驱动战功大幅漂移（±10%~35%）
            long driftSeed = SeedUtil.seed(String.valueOf(userId), groupId, "luckdrift", LocalDate.now().toString());
            Random driftRng = new Random(driftSeed);
            double drift;
            if (luck >= 80)      drift = 0.20 + driftRng.nextDouble() * 0.15;
            else if (luck >= 60) drift = 0.05 + driftRng.nextDouble() * 0.15;
            else if (luck >= 40) drift = -0.10 + driftRng.nextDouble() * 0.15;
            else if (luck >= 20) drift = -0.20 + driftRng.nextDouble() * 0.15;
            else                 drift = -0.35 + driftRng.nextDouble() * 0.20;

            powerFromLuck = (int)(yesterdayPower * drift);
            power = Math.max(50, yesterdayPower + powerFromLuck);
        }

        // 更新连击
        int streakGood = newTier > oldTier ? p.getStreakGood() + 1 : 0;
        int streakBad = newTier < oldTier ? p.getStreakBad() + 1 : 0;

        // 脉系内的职业名
        String name = UserProfessionRepository.ProfessionPath.entryName(p.getProfessionPath(), newTier);
        String rarity = UserProfessionRepository.ProfessionPath.rarityForTier(newTier);
        String description = descriptionFor(p.getProfessionPath(), newTier);

        String changeDesc;
        StringBuilder summaryBuilder = new StringBuilder();
        if (eventDay) {
            changeDesc = "💜 紫色史诗日！全员位阶飙升！";
        } else if (newTier > oldTier) {
            changeDesc = streakGood >= 3 ? "🔥 三连升！运势爆棚！" : "⬆️ 运势旺盛，位阶提升！";
        } else if (newTier < oldTier) {
            changeDesc = streakBad >= 3 ? "💀 三连降…诸事不宜！" : "⬇️ 运势低迷，位阶滑落…";
        } else {
            changeDesc = "➡️ 今日运势平稳，修为巩固中";
        }

        // 变动摘要
        if (eventDay) {
            summaryBuilder.append("紫色史诗日！位阶").append(oldTier).append("→4，战力").append(yesterdayPower).append("→").append(power).append("（拉满");
        } else if (newTier != oldTier) {
            summaryBuilder.append("位阶").append(oldTier).append("→").append(newTier).append("，战力").append(yesterdayPower).append("→").append(power).append("（跃迁");
        } else {
            summaryBuilder.append("同位阶，战力").append(yesterdayPower).append("→").append(power).append("（运气漂移");
        }
        String arrow = powerFromLuck >= 0 ? "+" : "";
        summaryBuilder.append(arrow).append(powerFromLuck).append("）");

        // 追踪历史最高位阶（safeBestTier 已修正迁移前的存量数据）
        int bestTier = Math.max(newTier, safeBestTier);

        // 持久化
        p.setProfessionName(name);
        p.setTier(newTier);
        p.setRarity(rarity);
        p.setCombatPower(power);
        p.setStreakGood(streakGood);
        p.setStreakBad(streakBad);
        p.setBestTier(bestTier);
        try {
            repo.update(p);
        } catch (SQLException e) {
            logger.error("更新职业失败 userId={}", userId, e);
        }

        // ── 记录每日变动日志 ──
        ProfessionDailyLog dailyLog = new ProfessionDailyLog();
        dailyLog.setUserId(userId);
        dailyLog.setGroupId(groupId);
        dailyLog.setLogDate(LocalDate.now());
        dailyLog.setProfessionPath(p.getProfessionPath());
        dailyLog.setProfessionName(name);
        dailyLog.setTier(newTier);
        dailyLog.setRarity(rarity);
        dailyLog.setYesterdayPower(yesterdayPower);
        dailyLog.setBasePower(power);
        dailyLog.setPowerFromLuck(powerFromLuck);
        dailyLog.setPowerFromPk(0);
        dailyLog.setFinalPower(power);
        dailyLog.setLuckValue(luck);
        dailyLog.setChangeSummary(summaryBuilder.toString());
        try {
            repo.upsertDailyLog(dailyLog);
        } catch (Exception e) {
            logger.error("记录每日日志失败 userId={}", userId, e);
        }

        // 查询群排名
        int groupRank = 0;
        try {
            groupRank = repo.getGroupRank(userId, groupId);
        } catch (Exception ignored) {}

        return new ProfessionResult(name, newTier, rarity, description, power, luck, changeDesc,
                p.getProfessionPath(), streakGood, streakBad, bestTier, groupRank);
    }

    /** 今日战力（供 Rank 等外部调用） */
    public static int getCombatPower(long userId, String groupId) {
        return drawForUser(userId, groupId).combatPower;
    }

    // ===== 位阶波动算法 =====

    /**
     * 基于运势计算新位阶。
     * 运势 >= 80: 升阶概率 40%（含 10% 跳2阶），不降
     * 运势 >= 60: 升阶 20%，保持 75%，降阶 5%
     * 运势 >= 40: 升阶 10%，保持 80%，降阶 10%
     * 运势 >= 20: 升阶 5%，保持 75%，降阶 20%
     * 运势 <  20: 升阶 0%，保持 60%，降阶 40%（含 10% 跳降2阶）
     * 连续好运 3+ 天 → 升阶加权 +10%
     * 连续霉运 3+ 天 → 降阶加权 +10%
     */
    static int computeNewTier(int currentTier, int luck, int streakGood, int streakBad) {
        String today = LocalDate.now().toString();
        long seed = SeedUtil.seed(String.valueOf(currentTier), "drift", today, String.valueOf(luck));
        Random rng = new Random(seed);
        int roll = rng.nextInt(100);

        int upChance = 0, downChance = 0, jumpUp = 0, jumpDown = 0;

        if (luck >= 80) {
            upChance = 30; jumpUp = 10; downChance = 0;
        } else if (luck >= 60) {
            upChance = 15; jumpUp = 5; downChance = 5;
        } else if (luck >= 40) {
            upChance = 10; jumpUp = 0; downChance = 10;
        } else if (luck >= 20) {
            upChance = 5; jumpUp = 0; downChance = 20;
        } else {
            upChance = 0; jumpUp = 0; downChance = 30; jumpDown = 10;
        }

        // 连击加成
        if (streakGood >= 3) { upChance += 10; }
        if (streakBad >= 3) { downChance += 10; }

        int stayChance = 100 - upChance - jumpUp - downChance - jumpDown;

        int delta;
        int sum = 0;
        if (roll < (sum += jumpUp)) delta = 2;
        else if (roll < (sum += upChance)) delta = 1;
        else if (roll < (sum += stayChance)) delta = 0;
        else if (roll < (sum += downChance)) delta = -1;
        else if (roll < (sum += jumpDown)) delta = -2;
        else delta = 0;

        return Math.max(1, Math.min(5, currentTier + delta));
    }

    // ===== 辅助 =====

    private static ProfessionResult fallbackResult(long userId, int luck) {
        return new ProfessionResult("见习剑客", 1, "普通", "三尺青锋初在手，不知天高与地厚", 150, luck, "初次踏入修行之路",
                "剑修", 0, 0, 1, 0);
    }

    private static String descriptionFor(String path, int tier) {
        String[][] descs = {
            // 剑修 — 从懵懂到剑道至尊
            {"三尺青锋初在手，不知天高与地厚",
             "御剑乘风踏歌行，一壶浊酒走天涯",
             "剑心澄澈如秋水，心中有剑胜有剑",
             "一剑曾当百万师，开宗立派我为峰",
             "万剑归宗我为尊，九天十地尽低眉"},
            // 法神 — 从学徒到时空之主
            {"指尖跃动第一缕光，元素之门悄然开",
             "风雷为剑雨为盾，四大元素听我令",
             "一念风起云涌，一怒天崩地裂",
             "翻手为云覆手雨，法则因我而改写",
             "时间长河任遨游，三千世界一掌中"},
            // 刺客 — 从潜行到死亡主宰
            {"藏身于暗影之中，伺机一击便远遁",
             "夜幕是我的披风，月光是我的刀锋",
             "月黑风高杀人夜，刀光过处已封喉",
             "于刀尖起舞，于死亡边缘漫步",
             "生死簿上朱笔落，阎王见我亦低头"},
            // 丹道 — 从采药到造化丹尊
            {"竹篓斜挎入云山，识得百草便是缘",
             "炉火纯青炼金丹，一粒入口百病消",
             "九转丹成逆生死，乾坤鼎中续长生",
             "身化乾坤炉，天地为药炼神通",
             "天地为炉造化为工，万物众生皆可炼"},
            // 御兽 — 从驯鸡到太古兽神
            {"山野小兽伴身旁，一声轻唤便来投",
             "笛声悠悠百兽至，猛虎亦作膝下猫",
             "万兽奔腾我为王，一声长啸震山林",
             "巨龙展翼破苍穹，龙炎焚尽世间敌",
             "化身混沌太古兽，一口吞天噬日月"},
            // 佛修 — 从小沙弥到佛祖
            {"青灯古佛诵经文，扫地恐伤蝼蚁命",
             "身经万苦铸金身，步步莲花向菩提",
             "降魔杵下群魔伏，一声佛号渡苍生",
             "大慈大悲救苦难，千处祈求千处应",
             "天上天下唯我尊，如来一掌定乾坤"},
            // 符箓 — 从画符到道祖
            {"朱砂为墨黄纸为媒，一笔一画通鬼神",
             "灵符到处邪祟散，镇宅驱鬼保安宁",
             "笔落惊风雨，符成泣鬼神，天地动",
             "虚空为纸道为墨，天地共鸣万物臣",
             "人法地，地法天，天法道，道法自然"},
            // 搞笑 — 从摸鱼到带薪修仙
            {"上班只为等下班，摸鱼方是人生道",
             "能躺绝不坐，能摸绝不卷，躺平真谛",
             "白天码字夜练剑，社畜亦有剑圣梦",
             "加班怨气化为符，贴谁谁就陪我熬",
             "上班摸鱼即修炼，工资照拿道行涨"}
        };
        int pi = 0;
        for (int i = 0; i < UserProfessionRepository.ProfessionPath.PATHS.length; i++) {
            if (UserProfessionRepository.ProfessionPath.PATHS[i].equals(path)) { pi = i; break; }
        }
        return descs[pi][Math.min(tier, 5) - 1];
    }

    /** 活动日战力：四阶最大值范围（4500~5000），种子确定当天不变 */
    private static int eventMaxPower(long userId, String groupId) {
        long seed = SeedUtil.seed(String.valueOf(userId), groupId, "event", LocalDate.now().toString());
        return 4500 + new Random(seed).nextInt(501);
    }

    static String getTierName(int tier) {
        return switch (tier) {
            case 1 -> "一阶·初窥门径";
            case 2 -> "二阶·登堂入室";
            case 3 -> "三阶·融会贯通";
            case 4 -> "四阶·炉火纯青";
            case 5 -> "五阶·登峰造极";
            default -> "未知位阶";
        };
    }

    // ===== 返回类型 =====

    public static class ProfessionResult {
        public final String name;
        public final int tier;
        public final String rarity;
        public final String description;
        public final int combatPower;
        public final int todayLuck;
        public final String changeDesc;
        public final String path;
        public final int streakGood;
        public final int streakBad;
        public final int bestTier;
        public final int groupRank;

        ProfessionResult(String name, int tier, String rarity, String description,
                         int combatPower, int todayLuck, String changeDesc,
                         String path, int streakGood, int streakBad, int bestTier, int groupRank) {
            this.name = name;
            this.tier = tier;
            this.rarity = rarity;
            this.description = description;
            this.combatPower = combatPower;
            this.todayLuck = todayLuck;
            this.changeDesc = changeDesc;
            this.path = path;
            this.streakGood = streakGood;
            this.streakBad = streakBad;
            this.bestTier = bestTier;
            this.groupRank = groupRank;
        }
    }
}
