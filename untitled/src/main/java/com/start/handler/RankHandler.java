package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.repository.GroupMessageStatsRepository;
import com.start.repository.UserAliasRepository;
import com.start.util.LuckUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class RankHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(RankHandler.class);
    private static final UserAliasRepository aliasRepo = new UserAliasRepository();
    private static final int TOP_N = 15;

    private static final Set<String> TRIGGERS = Set.of(
            "发言排行", "发言榜", "水群排行", "水群榜",
            "今日发言", "今日排行", "今天发言",
            "本周发言", "本周排行", "这周发言",
            "幸运排行", "幸运榜", "运势排行", "运势榜",
            "好感排行", "好感榜", "好感度排行", "好感度榜",
            "群cp", "CP排行", "谁最配", "谁和谁最配", "社交关系",
            "职业排行", "职业榜", "战力排行", "战力榜",
            "群排行", "排行榜", "有什么榜", "榜单", "排名"
    );

    // 匹配 "幸运排行-3" 这类详情查询
    private static final java.util.regex.Pattern DETAIL_PATTERN =
            java.util.regex.Pattern.compile("(幸运|发言|好运|好感|职业|战力).*-(\\d+)");

    @Override
    public boolean match(JsonNode msg) {
        if (!"group".equals(msg.path("message_type").asText())) return false;
        String text = msg.path("raw_message").asText().trim();
        if (!"group".equals(msg.path("message_type").asText())) return false;
        String raw = msg.path("raw_message").asText().trim();
        // 详情查询："幸运排行-3"
        if (DETAIL_PATTERN.matcher(raw).find()) return true;
        String plain = com.start.util.MessageUtil.extractPlainText(msg.path("message")).trim();
        // 精确匹配关键词
        for (String t : TRIGGERS) if (plain.equals(t)) return true;
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        String raw = msg.path("raw_message").asText().trim();
        long groupId = msg.path("group_id").asLong();
        String gid = String.valueOf(groupId);

        // 详情查询 "幸运排行-3"
        var detailMatcher = DETAIL_PATTERN.matcher(raw);
        if (detailMatcher.find()) {
            String type = detailMatcher.group(1);
            int rank = Integer.parseInt(detailMatcher.group(2));
            if (rank < 1 || rank > 15) { bot.sendGroupReply(groupId, "排名序号1-15哦"); return; }
            String userId = getUserIdByRank(gid, type, rank);
            if (userId == null) { bot.sendGroupReply(groupId, "没有第" + rank + "名数据~"); return; }
            String card = buildProfileCard(bot, groupId, gid, type, rank);
            bot.sendGroupReply(groupId, card);
            // 异步发送头像（避免 WebSocket 线程死锁）
            final long fGroupId = groupId;
            final long fUid;
            try { fUid = Long.parseLong(userId); } catch (NumberFormatException e) { return; }
            new Thread(() -> {
                try {
                    String url = getAvatarUrl(bot, fGroupId, fUid);
                    if (url != null && !url.isEmpty()) {
                        bot.sendGroupReply(fGroupId, "[CQ:image,file=" + url + "]");
                    }
                } catch (Exception ignored) {}
            }, "avatar-fetcher").start();
            return;
        }

        String feature = "群排行";
        if (raw.contains("发言") || raw.contains("水群")) {
            String period = "total";
            if (raw.contains("今日") || raw.contains("今天")) { period = "today"; feature = "今日发言"; }
            else if (raw.contains("本周") || raw.contains("这周")) { period = "week"; feature = "本周发言"; }
            else feature = "发言排行";
            bot.sendGroupReply(groupId, buildMessageRank(gid, period));
        } else if (raw.contains("幸运") || raw.contains("运势")) {
            feature = "幸运排行";
            bot.sendGroupReply(groupId, buildLuckRank(gid));
        } else if (raw.contains("好感")) {
            feature = "好感排行";
            bot.sendGroupReply(groupId, buildAffinityRank(gid));
        } else if (raw.contains("职业") || raw.contains("战力")) {
            feature = "职业排行";
            bot.sendGroupReply(groupId, buildProfessionRank(gid));
        } else if (raw.contains("cp") || raw.contains("CP") || raw.contains("配") || raw.contains("社交")) {
            feature = "群CP";
            bot.sendGroupReply(groupId, buildCPRank(gid));
        } else if (raw.contains("榜") || raw.contains("排名")) {
            feature = "排行榜帮助";
            bot.sendGroupReply(groupId, buildHelp());
        } else {
            bot.sendGroupReply(groupId, buildMessageRank(gid, "total"));
        }
        // 记录到 AI 上下文
        bot.getBaiLianService().recordBotAction(gid, String.valueOf(msg.path("user_id").asLong()),
                msg.path("sender").path("nickname").asText(""), feature, "");
    }

    // ==== 静态方法供 RankTool 调用 ====

    public static String buildMessageRankStatic(String groupId) { return buildMessageRank(groupId, "total"); }
    public static String buildLuckRankStatic(String groupId) { return buildLuckRank(groupId); }
    public static String buildAffinityRankStatic(String groupId) { return buildAffinityRank(groupId); }

    // ==== 内部实现 ====

    private String buildProfessionRank(String groupId) {
        Map<String, ProfessionScore> map = new LinkedHashMap<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    try {
                        long id = Long.parseLong(uid);
                        var p = DailyProfessionHandler.drawForUser(id, groupId);
                        int power = p.combatPower;
                        map.put(uid, new ProfessionScore(p.name, p.rarity, p.tier, power));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) { logger.error("职业排行查询失败", e); }
        if (map.isEmpty()) return "暂无本群活跃数据~";

        int showN = Math.min(TOP_N, map.size());
        StringBuilder sb = new StringBuilder("⚔️ 今日职业战力排行 TOP").append(showN).append("：\n");
        int[] idx = {0};
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        map.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().power, a.getValue().power))
                .limit(showN)
                .forEach(e -> {
                    var ps = e.getValue();
                    sb.append(idx[0] <= 2 ? medals[idx[0]] : medals[idx[0]]+" ")
                      .append(displayName(e.getKey(), groupId))
                      .append(": 【").append(ps.rarity).append("】").append(ps.name)
                      .append("(").append(ps.tier).append("阶) 战力:").append(ps.power).append("\n");
                    idx[0]++;
                });
        return sb.toString();
    }

    private record ProfessionScore(String name, String rarity, int tier, int power) {}

    /** 获取群成员头像 URL */
    private static String getMemberAvatar(Main bot, long groupId, String userId) {
        try {
            return bot.getOneBotWsService().getGroupMemberDisplayNames(groupId).toString();
        } catch (Exception e) { return ""; }
    }

    /** 获取排名第N名的用户ID */
    private static String getUserIdByRank(String gid, String type, int rank) {
        if (type.contains("幸运") || type.contains("好运")) {
            var list = getLuckList(gid);
            return rank <= list.size() ? list.get(rank - 1).getKey() : null;
        } else if (type.contains("发言")) {
            var list = GroupMessageStatsRepository.getMessageRank(gid, "total");
            return rank <= list.size() ? list.get(rank - 1).getKey() : null;
        } else if (type.contains("好感")) {
            var list = getAffinityList(gid);
            return rank <= list.size() ? list.get(rank - 1).getKey() : null;
        } else if (type.contains("职业") || type.contains("战力")) {
            var list = getProfessionList(gid);
            return rank <= list.size() ? list.get(rank - 1).getKey() : null;
        }
        return null;
    }

    /** 构建个人详情卡片 */
    private String buildProfileCard(Main bot, long groupId, String gid, String type, int rank) {
        String userId = getUserIdByRank(gid, type, rank);
        if (userId == null) return "没有第" + rank + "名数据~";

        String name = displayName(userId, gid);
        int luck = 0;
        String profession = "";
        int power = 0;
        int affinity = 0;
        int msgCount = 0;
        String location = "";

        try {
            long uid = Long.parseLong(userId);
            luck = LuckUtil.getDailyLuck(uid);
            var spell = LuckUtil.getDailySpell(uid);
            var p = DailyProfessionHandler.drawForUser(uid, gid);
            power = p.combatPower;
            profession = "【" + p.rarity + "】" + p.name + "(" + p.tier + "阶)";
            // 好感度
            try (java.sql.Connection c = DatabaseConfig.getConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement(
                         "SELECT affinity_score FROM user_affinity WHERE user_id=? AND group_id=? LIMIT 1")) {
                ps.setString(1, userId); ps.setString(2, gid);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) affinity = rs.getInt("affinity_score");
                }
            } catch (Exception ignored) {}
            // 发言数
            try (java.sql.Connection c = DatabaseConfig.getConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement(
                         "SELECT SUM(message_count) FROM group_message_stats WHERE user_id=? AND group_id=?")) {
                ps.setString(1, userId); ps.setString(2, gid);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) msgCount = rs.getInt(1);
                }
            } catch (Exception ignored) {}
            // 所在地
            var loc = new UserAliasRepository().getLocation(userId, gid);
            if (loc.isPresent()) location = loc.get();
            return name + " | " + userId + "\n" +
                   "🍀 幸运:" + luck + " " + spell.doSpell() + "\n" +
                   "⚔️ " + profession + " 战力:" + power + "\n" +
                   "💕 好感:" + affinity + " | 💬 " + msgCount + "条" +
                   (!location.isEmpty() ? "\n📍 " + location : "");
        } catch (NumberFormatException e) { return "QQ号解析错误"; }
    }

    private static String getAvatarUrl(Main bot, long groupId, long userId) {
        try {
            var future = bot.getOneBotWsService().getGroupMemberAvatarUrlAsync(groupId, userId);
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) { return ""; }
    }

    private static List<Map.Entry<String, Integer>> getLuckList(String groupId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (java.sql.Connection c = DatabaseConfig.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    try { map.put(uid, LuckUtil.getDailyLuck(Long.parseLong(uid))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {}
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
    }

    private static List<Map.Entry<String, Integer>> getAffinityList(String groupId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (java.sql.Connection c = DatabaseConfig.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, affinity_score FROM user_affinity WHERE group_id=? ORDER BY affinity_score DESC")) {
            ps.setString(1, groupId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) map.put(rs.getString("user_id"), rs.getInt("affinity_score"));
            }
        } catch (Exception e) {}
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
    }

    private static List<Map.Entry<String, Integer>> getProfessionList(String groupId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (java.sql.Connection c = DatabaseConfig.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    try { map.put(uid, DailyProfessionHandler.getCombatPower(Long.parseLong(uid), groupId)); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {}
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
    }

    private String buildHelp() {
        return "📊 可用排行榜：\n" +
               "💬 发言榜 — 说\"发言排行\"/\"今日发言\"/\"本周发言\"\n" +
               "🍀 幸运榜 — 说\"幸运排行\"\n" +
               "💕 好感榜 — 说\"好感排行\"\n" +
               "💑 群CP — 说\"群CP\"查看谁和谁最配\n" +
               "⚔️ 职业排行 — 说\"职业排行\"查看今日职业战力榜";
    }

    private String buildCPRank(String groupId) {
        var pairs = CPTracker.getTopPairs(groupId, 10);
        if (pairs.isEmpty()) return "💑 暂无CP数据，多@互动几次就有了~";
        StringBuilder sb = new StringBuilder("💑 群CP热度 TOP10：\n");
        int i = 1;
        for (var p : pairs) {
            String nameA = displayName(p.userA(), groupId);
            String nameB = displayName(p.userB(), groupId);
            sb.append(i).append(". ").append(nameA).append(" ❤️ ").append(nameB)
              .append("（互动").append(p.count()).append("次）\n");
            i++;
        }
        return sb.toString();
    }

    private static Map<String, String> memberNickCache = Collections.emptyMap();
    private static long memberNickCacheTime = 0;

    private static String displayName(String userId, String groupId) {
        var alias = aliasRepo.getBestAlias(userId, groupId);
        if (alias.isPresent()) return alias.get();
        // 群成员昵称缓存
        if (memberNickCache.containsKey(userId)) return memberNickCache.get(userId);
        // 数据库昵称
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT nickname FROM users WHERE user_id=? LIMIT 1")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String nick = rs.getString("nickname");
                    if (nick != null && !nick.isEmpty() && !"未知用户".equals(nick)) return nick;
                }
            }
        } catch (Exception ignored) {}
        return userId;
    }

    /** 刷新群成员昵称缓存并更新数据库 */
    public static void refreshMemberNicks(Main bot, long groupId) {
        if (System.currentTimeMillis() - memberNickCacheTime < 300_000) return;
        try {
            var map = bot.getOneBotWsService().getGroupMemberDisplayNames(groupId);
            if (map != null && !map.isEmpty()) {
                memberNickCache = map;
                memberNickCacheTime = System.currentTimeMillis();
                // 同步写到数据库，解决昵称缺失问题
                try (Connection c = DatabaseConfig.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "INSERT INTO users (user_id, nickname) VALUES (?, ?) ON DUPLICATE KEY UPDATE nickname = VALUES(nickname)")) {
                    for (var e : map.entrySet()) {
                        if (e.getValue() == null || e.getValue().isEmpty() || e.getValue().equals(e.getKey())) continue;
                        ps.setString(1, e.getKey());
                        ps.setString(2, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { logger.debug("刷新群成员昵称失败: {}", e.getMessage()); }
    }

    private static String buildMessageRank(String groupId, String period) {
        String label = GroupMessageStatsRepository.getPeriodLabel(period);
        var list = GroupMessageStatsRepository.getMessageRank(groupId, period);
        if (list.isEmpty()) return "💬 " + label + "暂无发言数据~";
        int showN = Math.min(TOP_N, list.size());
        StringBuilder sb = new StringBuilder("💬 ").append(label).append("发言排行 TOP").append(showN).append("：\n");
        int i = 1;
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        for (var e : list) {
            if (i > showN) break;
            sb.append(i <= 3 ? medals[i-1] : medals[i-1]+" ").append(displayName(e.getKey(), groupId))
              .append(": ").append(e.getValue()).append("条\n");
            i++;
        }
        return sb.toString();
    }

    private static String buildLuckRank(String groupId) {
        Map<String, Integer> luckMap = new LinkedHashMap<>();
        List<String> userIds = new ArrayList<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) userIds.add(rs.getString("user_id"));
            }
        } catch (Exception e) { logger.error("幸运排行查询失败", e); }

        for (String uid : userIds) {
            try { luckMap.put(uid, LuckUtil.getDailyLuck(Long.parseLong(uid))); }
            catch (NumberFormatException ignored) {}
        }
        if (luckMap.isEmpty()) return "🍀 暂无本群活跃数据~";

        int showN = Math.min(TOP_N, luckMap.size());
        StringBuilder sb = new StringBuilder("🍀 今日幸运排行 TOP").append(showN).append("：\n");
        int[] idx = {0};
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        luckMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(showN)
                .forEach(e -> {
                    sb.append(idx[0] <= 2 ? medals[idx[0]] : medals[idx[0]]+" ")
                      .append(displayName(e.getKey(), groupId))
                      .append(": ").append(e.getValue()).append("分\n");
                    idx[0]++;
                });
        return sb.toString();
    }

    private static String buildAffinityRank(String groupId) {
        List<String[]> rows = new ArrayList<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, affinity_score FROM user_affinity WHERE group_id=? ORDER BY affinity_score DESC LIMIT " + TOP_N)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString("user_id"), String.valueOf(rs.getInt("affinity_score"))});
                }
            }
        } catch (Exception e) { logger.error("好感度排行查询失败", e); return "好感度排行查询失败~"; }
        if (rows.isEmpty()) return "暂无好感度数据~";
        int showN = rows.size();
        StringBuilder sb = new StringBuilder("💕 好感度排行 TOP").append(showN).append("：\n");
        int i = 1;
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        for (String[] row : rows) {
            if (i > showN) break;
            sb.append(i <= 3 ? medals[i-1] : medals[i-1]+" ").append(displayName(row[0], groupId))
              .append(": ").append(row[1]).append("分\n");
            i++;
        }
        return sb.toString();
    }
}
