package com.start.agent.social;

import com.start.agent.Tool;

import com.start.config.DatabaseConfig;
import com.start.repository.UserAliasRepository;
import com.start.util.LuckUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class LuckTool implements Tool {
    private final UserAliasRepository aliasRepo = new UserAliasRepository();

    @Override public String getName() { return "get_luck"; }

    @Override public String getDescription() {
        return "查幸运值。target_user_id 填QQ号，如果不知道QQ号用 target_name 填昵称或别称。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "target_user_id", Map.of("type", "string", "description", "QQ号"),
                        "target_name", Map.of("type", "string", "description", "昵称或别称，不知道QQ号时填")
                ),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        String targetId = (String) args.get("target_user_id");
        String targetName = (String) args.get("target_name");

        // 解析名称→QQ
        if (targetId == null || targetId.isEmpty()) {
            if (targetName == null || targetName.isEmpty()) return "需要 target_user_id 或 target_name";
            // 先查别称表
            var resolved = aliasRepo.resolveAlias(targetName.trim(), "0");
            if (resolved.isPresent()) {
                targetId = resolved.get();
            } else {
                // 再查 users 昵称表
                try (Connection c = DatabaseConfig.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT user_id FROM users WHERE nickname=? LIMIT 1")) {
                    ps.setString(1, targetName.trim());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) targetId = rs.getString("user_id");
                    }
                } catch (Exception ignored) {}
            }
            if (targetId == null) return "不认识「" + targetName + "」，可能是新人或者还没说过话";
        }

        try {
            long uid = Long.parseLong(targetId);
            int luck = LuckUtil.getDailyLuck(uid);
            var spell = LuckUtil.getDailySpell(uid);
            // 尝试获取显示名
            String display = aliasRepo.getBestAlias(targetId, "0").orElse(targetId);
            return String.format("%s 今日幸运值：%d | %s | %s | %s", display, luck, spell.mood(), spell.doSpell(), spell.avoidSpell());
        } catch (NumberFormatException e) {
            return "无效的 QQ 号";
        }
    }
}
