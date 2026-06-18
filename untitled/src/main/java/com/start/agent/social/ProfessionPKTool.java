package com.start.agent.social;

import com.start.agent.Tool;

import com.start.config.DatabaseConfig;
import com.start.handler.DailyProfessionHandler;
import com.start.handler.DailyProfessionHandler.ProfessionResult;
import com.start.repository.PKRepository;
import com.start.repository.UserAliasRepository;

import java.util.List;
import java.util.Map;

public class ProfessionPKTool implements Tool {

    private final PKRepository pkRepo = new PKRepository(DatabaseConfig.getDataSource());
    private final UserAliasRepository aliasRepo = new UserAliasRepository();

    @Override public String getName() { return "profession_pk"; }

    @Override public String getDescription() {
        return "查看PK状态或发起挑战。action: 'status'查今日剩余次数，'records'查今日PK记录。target_user_id和group_id用于查询指定用户的记录。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "status 或 records"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "target_user_id", Map.of("type", "string", "description", "要查询的QQ号")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "缺少 action 参数";

        String groupId = (String) args.get("group_id");
        String targetId = (String) args.get("target_user_id");

        if ("status".equals(action)) {
            if (targetId == null) return "需要 target_user_id";
            try {
                long uid = Long.parseLong(targetId);
                int today = pkRepo.todayPKCount(uid);
                int bully = pkRepo.todayBullyCount(uid);
                List<Long> targets = pkRepo.todayTargets(uid);

                ProfessionResult r = null;
                if (groupId != null) r = DailyProfessionHandler.drawForUser(uid, groupId);

                String name = aliasRepo.getBestAlias(targetId, groupId).orElse(targetId);
                StringBuilder sb = new StringBuilder();
                sb.append(name).append(" 今日PK: ").append(today).append("/5次");
                if (r != null) sb.append(" | 战力: ").append(r.combatPower).append(" | ").append(r.tier).append("阶");
                if (bully > 0) sb.append(" | ⚠️欺凌弱小x").append(bully);
                if (!targets.isEmpty()) sb.append(" | 已PK过: ").append(targets.size()).append("人");
                return sb.toString();
            } catch (NumberFormatException e) {
                return "无效QQ号";
            }
        }

        if ("records".equals(action)) {
            if (targetId == null) return "需要 target_user_id";
            try {
                long uid = Long.parseLong(targetId);
                int today = pkRepo.todayPKCount(uid);
                int bully = pkRepo.todayBullyCount(uid);
                List<Long> targets = pkRepo.todayTargets(uid);

                String name = aliasRepo.getBestAlias(targetId, groupId).orElse(targetId);
                StringBuilder sb = new StringBuilder();
                sb.append(name).append(" 今日PK记录: ").append(today).append("/5次，欺凌").append(bully).append("次");
                if (!targets.isEmpty()) {
                    sb.append("，已挑战: ");
                    for (long t : targets) {
                        sb.append(aliasRepo.getBestAlias(String.valueOf(t), groupId).orElse(String.valueOf(t))).append(" ");
                    }
                }
                sb.append("\n提醒用户：@对方 + PK 即可发起挑战。不可重复PK同一人，欺负弱者超过3次会扣战力！");
                return sb.toString();
            } catch (NumberFormatException e) {
                return "无效QQ号";
            }
        }

        return "未知 action: " + action + "，可选 status / records";
    }
}
