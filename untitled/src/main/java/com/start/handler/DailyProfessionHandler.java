package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
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

        // 今日已抽取过，直接返回当前值，保证同一天内多次查询结果一致
        if (p.getUpdatedAt() != null && p.getUpdatedAt().toLocalDate().equals(LocalDate.now())) {
            String desc = descriptionFor(p.getProfessionPath(), p.getTier());
            return new ProfessionResult(p.getProfessionName(), p.getTier(), p.getRarity(),
                    desc, p.getCombatPower(), luck, "➡️ 今日已抽取");
        }

        int oldTier = p.getTier();
        int newTier = computeNewTier(oldTier, luck, p.getStreakGood(), p.getStreakBad());

        // 更新连击
        int streakGood = newTier > oldTier ? p.getStreakGood() + 1 : 0;
        int streakBad = newTier < oldTier ? p.getStreakBad() + 1 : 0;

        // 脉系内的职业名
        String name = UserProfessionRepository.ProfessionPath.entryName(p.getProfessionPath(), newTier);
        String rarity = UserProfessionRepository.ProfessionPath.rarityForTier(newTier);
        int power = UserProfessionRepository.ProfessionPath.randomPower(newTier, userId, groupId);
        String description = descriptionFor(p.getProfessionPath(), newTier);

        String changeDesc;
        if (newTier > oldTier) {
            changeDesc = streakGood >= 3 ? "🔥 三连升！运势爆棚！" : "⬆️ 运势旺盛，位阶提升！";
        } else if (newTier < oldTier) {
            changeDesc = streakBad >= 3 ? "💀 三连降…诸事不宜！" : "⬇️ 运势低迷，位阶滑落…";
        } else {
            changeDesc = "➡️ 今日运势平稳，修为巩固中";
        }

        // 持久化
        p.setProfessionName(name);
        p.setTier(newTier);
        p.setRarity(rarity);
        p.setCombatPower(power);
        p.setStreakGood(streakGood);
        p.setStreakBad(streakBad);
        try {
            repo.update(p);
        } catch (SQLException e) {
            logger.error("更新职业失败 userId={}", userId, e);
        }

        return new ProfessionResult(name, newTier, rarity, description, power, luck, changeDesc);
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
        return new ProfessionResult("见习剑客", 1, "普通", "初握剑柄，剑道漫漫", 150, luck, "初次踏入修行之路");
    }

    private static String descriptionFor(String path, int tier) {
        String[][] descs = {
            {"初握剑柄，剑道漫漫", "御剑飞行，行走江湖", "剑心澄澈，万物为剑", "开宗立派，剑道巅峰", "万剑臣服，剑道至尊"},
            {"初识元素，连火球术都未必能施展", "掌握四大元素，召唤风雨雷电", "魔力浩瀚，能施展禁咒", "元素之主，一念改天地", "超越时空，掌控一切"},
            {"擅长隐匿，但还不够致命", "如影随形，一击必杀", "黑夜主场，刀光无声", "刀尖起舞，死亡之舞", "执掌生死，暗影主宰"},
            {"背着竹篓，辨认灵草", "炼制基础丹药，救死扶伤", "丹火纯青，可炼九转金丹", "一粒丹成，起死回生", "以天地为炉，造化苍生"},
            {"只能驯服鸡鸭鹅", "能与灵兽沟通，驾驭猛兽", "万兽臣服，震天动地", "驾驭上古巨龙", "化身太古凶兽"},
            {"青灯古佛，诵读经文", "以苦为乐，金身不灭", "十八罗汉转世", "慈悲为怀，普度众生", "如来神掌定乾坤"},
            {"握着毛笔，照着画符", "绘制基础符箓，驱邪镇鬼", "笔落惊风雨，符成泣鬼神", "虚空画符，天地共鸣", "太上忘情，道法自然"},
            {"上班偷刷手机", "躺平就是胜利", "白天写码晚上练剑", "加班怨气驱动符箓", "上班修炼下班飞升"}
        };
        int pi = 0;
        for (int i = 0; i < UserProfessionRepository.ProfessionPath.PATHS.length; i++) {
            if (UserProfessionRepository.ProfessionPath.PATHS[i].equals(path)) { pi = i; break; }
        }
        return descs[pi][Math.min(tier, 5) - 1];
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

        ProfessionResult(String name, int tier, String rarity, String description,
                         int combatPower, int todayLuck, String changeDesc) {
            this.name = name;
            this.tier = tier;
            this.rarity = rarity;
            this.description = description;
            this.combatPower = combatPower;
            this.todayLuck = todayLuck;
            this.changeDesc = changeDesc;
        }
    }
}
