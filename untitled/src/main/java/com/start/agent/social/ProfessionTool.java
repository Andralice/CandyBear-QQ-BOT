package com.start.agent.social;

import com.start.agent.Tool;

import com.start.config.DatabaseConfig;
import com.start.handler.DailyProfessionHandler;
import com.start.model.ProfessionDailyLog;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserProfessionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;

public class ProfessionTool implements Tool {
    private final UserAliasRepository aliasRepo = new UserAliasRepository();
    private final UserProfessionRepository profRepo = new UserProfessionRepository(DatabaseConfig.getDataSource());

    @Override public String getName() { return "get_profession"; }

    @Override public String getDescription() {
        return "查职业和今日战力变动明细。group_id 填当前群号，target_user_id 填QQ号，不知道QQ号用 target_name 填昵称或别称。返回数据含运气漂移和PK造成的战力增减明细。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "target_user_id", Map.of("type", "string", "description", "QQ号"),
                        "target_name", Map.of("type", "string", "description", "昵称或别称")
                ),
                "required", List.of("group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String targetId = (String) args.get("target_user_id");
        String targetName = (String) args.get("target_name");

        if (groupId == null || groupId.isEmpty()) return "缺少 group_id";
        if (targetId == null || targetId.isEmpty()) {
            if (targetName == null || targetName.isEmpty()) return "需要 target_user_id 或 target_name";
            var resolved = aliasRepo.resolveAlias(targetName, groupId);
            if (resolved.isPresent()) {
                targetId = resolved.get();
            } else {
                try (Connection c = DatabaseConfig.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT user_id FROM users WHERE nickname=? LIMIT 1")) {
                    ps.setString(1, targetName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) targetId = rs.getString("user_id");
                    }
                } catch (Exception ignored) {}
            }
            if (targetId == null) return "不认识「" + targetName + "」";
        }

        try {
            long uid = Long.parseLong(targetId);
            var result = DailyProfessionHandler.drawForUser(uid, groupId);
            String display = aliasRepo.getBestAlias(targetId, groupId).orElse(targetId);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s 【%s】%s（%d阶）%s脉 | 当前战力：%d | 运势：%d | %s",
                    display, result.rarity, result.name, result.tier,
                    result.path, result.combatPower, result.todayLuck, result.changeDesc));

            if (result.streakGood >= 3) sb.append(" | 🔥三连升");
            if (result.streakBad >= 3) sb.append(" | 💀三连降");
            if (result.groupRank > 0) sb.append(" | 群排名第").append(result.groupRank).append("名");
            if (result.bestTier > result.tier)
                sb.append(" | 历史巅峰").append(result.bestTier).append("阶");

            // ── 今日变动明细（从每日日志读取） ──
            ProfessionDailyLog log = profRepo.getDailyLog(uid, groupId, LocalDate.now());
            if (log != null) {
                sb.append("\n📊 今日战力变动明细：");
                sb.append("\n  昨日战力：").append(log.getYesterdayPower());
                sb.append(" → 今日基准：").append(log.getBasePower());
                String luckArrow = log.getPowerFromLuck() >= 0 ? "+" : "";
                sb.append("（运气漂移 ").append(luckArrow).append(log.getPowerFromLuck()).append("）");
                if (log.getPowerFromPk() != 0) {
                    String pkArrow = log.getPowerFromPk() >= 0 ? "+" : "";
                    sb.append("\n  PK变动：").append(pkArrow).append(log.getPowerFromPk());
                }
                sb.append("\n  当前最终战力：").append(log.getFinalPower());
            }

            return sb.toString();
        } catch (NumberFormatException e) {
            return "无效的 QQ 号";
        }
    }
}
