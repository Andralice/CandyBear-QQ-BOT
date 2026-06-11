package com.start.agent;

import com.start.repository.UserAliasRepository;

import java.util.*;

/**
 * 别称 + 地点管理工具。支持：
 * - record_alias: 记录别称（SUBJECTIVE/OBJECTIVE/BOT_ALIAS）
 * - set_primary_location: 第一等级地点（用户说"我在XX"）
 * - set_secondary_location: 第二等级地点（查询某地天气）
 * - resolve_alias: 通过别称查用户
 * - get_bot_aliases: 查糖果熊自己的别称列表
 */
public class UserAliasTool implements Tool {
    private static final String SUPERUSER_QQ = "0"; // 请在 application.properties 中设置 admin.qq
    private final UserAliasRepository aliasRepo;
    private final String botQq;

    public UserAliasTool(UserAliasRepository aliasRepo, String botQq) {
        this.aliasRepo = aliasRepo;
        this.botQq = botQq;
    }

    @Override public String getName() { return "manage_alias"; }

    @Override
    public String getDescription() {
        return "管理别称和地点。action: record_alias(记别称), update_alias(改别称,参数old_alias+new_alias), delete_alias(删别称), set_primary_location(设主地点), " +
               "set_secondary_location(设次地点), resolve_alias(查别称对应谁), get_bot_aliases(查糖果熊的别称)。" +
               "当有人说'以后叫我XX'时用 record_alias SUBJECTIVE，" +
               "有人说'他叫XX'时用 record_alias OBJECTIVE，" +
               "有人说'我叫你XX吧'（给糖果熊起别称）时用 record_alias BOT_ALIAS，" +
               "有人说'我在北京'时用 set_primary_location，" +
               "查询某地天气后自动用 set_secondary_location 记录。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "操作类型"),
                        "target_user_id", Map.of("type", "string", "description", "目标用户QQ"),
                        "alias_name", Map.of("type", "string", "description", "别称"),
                        "alias_type", Map.of("type", "string", "description", "SUBJECTIVE/OBJECTIVE/BOT_ALIAS"),
                        "location", Map.of("type", "string", "description", "地点名称"),
                        "group_id", Map.of("type", "string", "description", "群ID，私聊填null"),
                        "set_by_user_id", Map.of("type", "string", "description", "发起操作的用户QQ")
                ),
                "required", Arrays.asList("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "缺少 action 参数";
        return switch (action) {
            case "record_alias" -> doRecordAlias(args);
            case "update_alias" -> doUpdateAlias(args);
            case "delete_alias" -> doDeleteAlias(args);
            case "set_primary_location" -> doSetLocation(args, true);
            case "set_secondary_location" -> doSetLocation(args, false);
            case "resolve_alias" -> doResolve(args);
            case "get_bot_aliases" -> doGetBotAliases();
            default -> "未知操作: " + action;
        };
    }

    private String doRecordAlias(Map<String, Object> args) {
        String targetId = (String) args.get("target_user_id");
        String groupId = (String) args.get("group_id");
        String alias = (String) args.get("alias_name");
        String type = (String) args.get("alias_type");
        String setBy = (String) args.getOrDefault("set_by_user_id", targetId);

        if (targetId == null || alias == null || type == null)
            return "缺少参数 target_user_id/alias_name/alias_type";
        if ("null".equals(groupId)) groupId = null;
        if (!Set.of("SUBJECTIVE", "OBJECTIVE", "BOT_ALIAS").contains(type))
            return "alias_type 须为 SUBJECTIVE/OBJECTIVE/BOT_ALIAS";

        // 如果给糖果熊起别称，target 设为 bot QQ
        if ("BOT_ALIAS".equals(type) && (targetId == null || targetId.equals(botQq))) {
            targetId = botQq;
        }

        String result = aliasRepo.recordAlias(targetId, groupId, alias, type, setBy);
        if (result.startsWith("conflict:"))
            return "别称「" + alias + "」已被 " + result.substring(9) + " 占用，请换一个。";
        if (result.startsWith("error:"))
            return "记录失败：" + result.substring(6);

        String label = switch (type) { case "SUBJECTIVE" -> "主观"; case "OBJECTIVE" -> "客观"; default -> "糖果熊"; };
        return "已记录" + label + "别称：" + targetId + " → 「" + alias + "」";
    }

    private String doUpdateAlias(Map<String, Object> args) {
        String targetId = (String) args.get("target_user_id");
        String groupId = (String) args.get("group_id");
        String oldAlias = (String) args.get("old_alias");
        String newAlias = (String) args.get("new_alias");
        String requesterId = (String) args.get("requester_user_id");
        if (targetId == null || oldAlias == null || newAlias == null)
            return "缺少参数 target_user_id/old_alias/new_alias";
        if (requesterId != null && !requesterId.equals(targetId) && !SUPERUSER_QQ.equals(requesterId))
            return "只有本人才能修改自己的别称哦~";
        if ("null".equals(groupId)) groupId = null;

        String result = aliasRepo.updateAlias(targetId, groupId, oldAlias, newAlias);
        if (result.startsWith("conflict:"))
            return "新别称「" + newAlias + "」已被占用，请换一个。";
        if (result.startsWith("not_found:"))
            return result.substring(10);
        return "已将「" + oldAlias + "」改为「" + newAlias + "」";
    }

    private String doDeleteAlias(Map<String, Object> args) {
        String targetId = (String) args.get("target_user_id");
        String groupId = (String) args.get("group_id");
        String alias = (String) args.get("alias_name");
        String requesterId = (String) args.get("requester_user_id");
        if (targetId == null || alias == null)
            return "缺少参数 target_user_id/alias_name";
        if (requesterId != null && !requesterId.equals(targetId) && !SUPERUSER_QQ.equals(requesterId))
            return "只有本人才能删除自己的别称哦~";
        if ("null".equals(groupId)) groupId = null;

        String result = aliasRepo.deleteAlias(targetId, groupId, alias);
        if (result.startsWith("not_found:"))
            return result.substring(10);
        return "已删除别称「" + alias + "」";
    }

    private String doSetLocation(Map<String, Object> args, boolean isPrimary) {
        String userId = (String) args.getOrDefault("target_user_id", args.get("set_by_user_id"));
        String loc = (String) args.get("location");
        if (userId == null || loc == null || loc.trim().isEmpty())
            return "缺少参数 target_user_id/location";
        aliasRepo.updateLocation(userId, (String) args.getOrDefault("group_id", "0"), loc.trim(), isPrimary);
        String tier = isPrimary ? "主要" : "次要";
        return "已记录 " + userId + " 的" + tier + "地点：" + loc.trim();
    }

    private String doResolve(Map<String, Object> args) {
        String alias = (String) args.get("alias_name");
        String groupId = (String) args.get("group_id");
        if ("null".equals(groupId)) groupId = null;
        if (alias == null) return "缺少 alias_name";

        if (aliasRepo.getBotAliases().contains(alias))
            return "「" + alias + "」就是糖果熊自己";

        var uid = aliasRepo.resolveAlias(alias, groupId);
        if (uid.isEmpty()) {
            // 别称表没找到，再查 users 昵称表
            try (var c = com.start.config.DatabaseConfig.getConnection();
                 var ps = c.prepareStatement("SELECT user_id FROM users WHERE nickname=? LIMIT 1")) {
                ps.setString(1, alias.trim());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) uid = java.util.Optional.of(rs.getString("user_id"));
                }
            } catch (Exception ignored) {}
        }
        if (uid.isPresent()) {
            String qq = uid.get();
            var loc = aliasRepo.getLocation(qq, groupId != null ? groupId : "0");
            return "「" + alias + "」的QQ是" + qq + "。" +
                   "请在回复中直接 @ 他：[CQ:at,qq=" + qq + "] " + alias + "，不要直接输出QQ号。";
        }
        return "不知道「" + alias + "」是谁，也许是新朋友？";
    }

    private String doGetBotAliases() {
        List<String> aliases = aliasRepo.getBotAliases();
        if (aliases.isEmpty()) return "目前还没有人给我起别称，就叫糖果熊吧。";
        return "大家叫我：" + String.join("、", aliases) + "，还有糖果熊。";
    }
}
