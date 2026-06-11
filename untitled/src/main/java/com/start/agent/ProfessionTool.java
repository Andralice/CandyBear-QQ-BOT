package com.start.agent;

import com.start.config.DatabaseConfig;
import com.start.handler.DailyProfessionHandler;
import com.start.repository.UserAliasRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class ProfessionTool implements Tool {
    private final UserAliasRepository aliasRepo = new UserAliasRepository();

    @Override public String getName() { return "get_profession"; }

    @Override public String getDescription() {
        return "查职业和战力。group_id 填当前群号，target_user_id 填QQ号，不知道QQ号用 target_name 填昵称或别称。";
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
            return String.format("%s 【%s】%s（%d阶）战力：%d | 运势%d %s",
                    display, result.rarity, result.name, result.tier,
                    result.combatPower, result.todayLuck, result.changeDesc);
        } catch (NumberFormatException e) {
            return "无效的 QQ 号";
        }
    }
}
