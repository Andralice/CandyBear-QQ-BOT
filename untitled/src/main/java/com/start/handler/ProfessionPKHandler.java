package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.model.PKRecord;
import com.start.repository.PKRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserProfessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * 职业 PK 处理器。
 * 指令格式：PK @某人 或 @某人 PK
 * 每日限5次，不可重复PK同一人。
 * 欺凌检测：连续攻击低2阶以上的对手3次触发惩罚。
 */
public class ProfessionPKHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProfessionPKHandler.class);
    private static final int DAILY_LIMIT = 5;
    private static final int BULLY_THRESHOLD = 3;

    private final PKRepository pkRepo = new PKRepository(DatabaseConfig.getDataSource());
    private final UserProfessionRepository profRepo = new UserProfessionRepository(DatabaseConfig.getDataSource());
    private final UserAliasRepository aliasRepo = new UserAliasRepository();
    private final Random rng = new Random();

    @Override
    public boolean match(JsonNode message) {
        String text = extractText(message);
        if (text == null) return false;
        String t = text.trim();
        // 匹配 "@xxx PK" 或 "PK @xxx"
        return hasAtAndKeyword(t, "pk") || hasAtAndKeyword(t, "PK") || hasAtAndKeyword(t, "Pk");
    }

    private boolean hasAtAndKeyword(String text, String kw) {
        return text.contains("[CQ:at,") && text.toLowerCase().contains(kw.toLowerCase());
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        long groupId = extractGroupId(message);
        long attackerId = extractUserId(message);

        // 解析被 @ 的目标
        Long targetId = extractAtTarget(message);
        if (targetId == null) {
            bot.sendGroupReply(groupId, "[CQ:at,qq=" + attackerId + "] 请 @ 你想要挑战的对手！");
            return;
        }
        if (targetId == attackerId) {
            bot.sendGroupReply(groupId, "[CQ:at,qq=" + attackerId + "] 不能和自己 PK 哦~");
            return;
        }

        // 获取双方职业
        var atkResult = DailyProfessionHandler.drawForUser(attackerId, String.valueOf(groupId));
        var defResult = DailyProfessionHandler.drawForUser(targetId, String.valueOf(groupId));

        if (atkResult == null || defResult == null) {
            bot.sendGroupReply(groupId, "[CQ:at,qq=" + attackerId + "] 双方都需要先抽取职业才能 PK！发送「抽职业」");
            return;
        }

        // 检查每日限制
        int todayCount = pkRepo.todayPKCount(attackerId);
        if (todayCount >= DAILY_LIMIT) {
            bot.sendGroupReply(groupId, "[CQ:at,qq=" + attackerId + "] 今日 PK 次数已用完（" + DAILY_LIMIT + "次/天），明天再来吧！");
            return;
        }

        // 检查是否重复PK同一人
        List<Long> todayTargets = pkRepo.todayTargets(attackerId);
        if (todayTargets.contains(targetId)) {
            bot.sendGroupReply(groupId, "[CQ:at,qq=" + attackerId + "] 今天已经挑战过 ta 了，换个人吧！");
            return;
        }

        // ──── PK 计算 ────
        int aTier = atkResult.tier;
        int dTier = defResult.tier;
        int diff = aTier - dTier; // 正值=攻击方等级更高

        double winRate;
        int rewardOnWin;
        int penaltyOnLose;
        boolean isBully = false;

        if (diff >= 2) {
            winRate = 0.80;
            rewardOnWin = 0;
            penaltyOnLose = -100;
            isBully = true;
        } else if (diff == 1) {
            winRate = 0.65;
            rewardOnWin = 50;
            penaltyOnLose = -70;
        } else if (diff == 0) {
            winRate = 0.50;
            rewardOnWin = 80;
            penaltyOnLose = -50;
        } else if (diff == -1) {
            winRate = 0.35;
            rewardOnWin = 110;
            penaltyOnLose = -40;
        } else { // diff <= -2
            winRate = 0.10;
            rewardOnWin = 500;
            penaltyOnLose = -30;
        }

        boolean win = rng.nextDouble() < winRate;
        int powerChange = win ? rewardOnWin : penaltyOnLose;

        // 欺凌检测
        int bullyCount = pkRepo.todayBullyCount(attackerId);
        String bullyMsg = "";
        if (isBully && win) {
            bullyCount++; // 本次也算
            if (bullyCount >= BULLY_THRESHOLD) {
                powerChange -= 100;
                bullyMsg = "\n⚠️ 欺凌弱小！你已欺压低阶对手" + bullyCount + "次，额外扣除100战力！";
            }
        }

        // 更新战力
        int newPower = Math.max(50, atkResult.combatPower + powerChange);

        // 持久化 PK 记录
        PKRecord record = new PKRecord();
        record.setAttackerId(attackerId);
        record.setDefenderId(targetId);
        record.setGroupId(String.valueOf(groupId));
        record.setAttackerTier(aTier);
        record.setDefenderTier(dTier);
        record.setWin(win);
        record.setPowerChange(powerChange);
        record.setBully(isBully && win);
        record.setPkDate(LocalDate.now());
        pkRepo.insert(record);

        // 更新攻击方战力到 DB + 同步每日日志
        try {
            var p = profRepo.findByUser(attackerId, String.valueOf(groupId));
            if (p != null) {
                p.setCombatPower(newPower);
                profRepo.update(p);
                // 同步每日变动日志
                profRepo.updateDailyLogPK(attackerId, String.valueOf(groupId), LocalDate.now(), powerChange, newPower);
            }
        } catch (Exception e) {
            logger.error("更新PK后战力失败", e);
        }

        // ──── 生成结果消息 ────
        String atkName = aliasRepo.getBestAlias(String.valueOf(attackerId), String.valueOf(groupId))
                .orElse(String.valueOf(attackerId));
        String defName = aliasRepo.getBestAlias(String.valueOf(targetId), String.valueOf(groupId))
                .orElse(String.valueOf(targetId));

        StringBuilder sb = new StringBuilder();
        sb.append("[CQ:at,qq=").append(attackerId).append("]\n");
        sb.append("⚔️ ").append(atkName).append("【").append(atkResult.rarity).append("·").append(atkResult.name).append("】")
                .append(" VS ")
                .append(defName).append("【").append(defResult.rarity).append("·").append(defResult.name).append("】")
                .append("\n\n");

        if (win) {
            sb.append("🎉 挑战成功！（胜率 ").append((int)(winRate*100)).append("%）\n");
        } else {
            sb.append("💔 挑战失败…（胜率 ").append((int)(winRate*100)).append("%）\n");
        }

        String arrow = powerChange >= 0 ? "+" : "";
        sb.append("战力变化：").append(arrow).append(powerChange)
                .append("（").append(atkResult.combatPower).append(" → ").append(newPower).append("）");

        if (isBully && win && rewardOnWin == 0) {
            sb.append("\n💢 虐菜不加战力！");
        }
        sb.append(bullyMsg);

        sb.append("\n\n📊 今日剩余PK次数：").append(DAILY_LIMIT - todayCount - 1);

        bot.sendGroupReply(groupId, sb.toString());
        logger.info("⚔️ PK: {}[{}阶] vs {}[{}阶] win={} diff={} power={}",
                attackerId, aTier, targetId, dTier, win, diff, powerChange);
    }

    // ── 消息解析 ──

    private String extractText(JsonNode msg) {
        try {
            if (msg.has("raw_message")) return msg.get("raw_message").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private long extractGroupId(JsonNode msg) {
        try {
            if (msg.has("group_id")) return msg.get("group_id").asLong();
            if (msg.has("sender") && msg.get("sender").has("group_id"))
                return msg.get("sender").get("group_id").asLong();
        } catch (Exception ignored) {}
        return 0;
    }

    private long extractUserId(JsonNode msg) {
        try {
            if (msg.has("user_id")) return msg.get("user_id").asLong();
            if (msg.has("sender") && msg.get("sender").has("user_id"))
                return msg.get("sender").get("user_id").asLong();
        } catch (Exception ignored) {}
        return 0;
    }

    /** 从消息中提取第一个 @ 目标的 QQ 号 */
    public static Long extractAtTarget(JsonNode msg) {
        try {
            if (msg.has("message")) {
                JsonNode arr = msg.get("message");
                if (arr.isArray()) {
                    for (JsonNode seg : arr) {
                        if ("at".equals(seg.path("type").asText())) {
                            String qq = seg.path("data").path("qq").asText();
                            if (qq != null && !qq.isEmpty()) return Long.parseLong(qq);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
