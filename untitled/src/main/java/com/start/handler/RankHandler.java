package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.util.LuckUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * 群排行：发言榜、幸运榜、好感榜
 */
public class RankHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(RankHandler.class);

    private static final Set<String> TRIGGERS = Set.of(
            "发言排行", "发言榜", "水群排行", "水群榜",
            "幸运排行", "幸运榜", "运势排行", "运势榜",
            "好感排行", "好感榜", "好感度排行", "好感度榜",
            "群排行"
    );

    @Override
    public boolean match(JsonNode msg) {
        if (!"group".equals(msg.path("message_type").asText())) return false;
        String text = msg.path("raw_message").asText().trim();
        // 支持 @糖果熊 发言排行 这种格式
        for (String t : TRIGGERS) {
            if (text.contains(t)) return true;
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        String raw = msg.path("raw_message").asText().trim();
        long groupId = msg.path("group_id").asLong();
        String groupIdStr = String.valueOf(groupId);

        if (raw.contains("发言") || raw.contains("水群")) {
            bot.sendGroupReply(groupId, buildMessageRank(groupIdStr));
        } else if (raw.contains("幸运") || raw.contains("运势")) {
            bot.sendGroupReply(groupId, buildLuckRank(groupIdStr));
        } else if (raw.contains("好感")) {
            bot.sendGroupReply(groupId, buildAffinityRank(groupIdStr));
        } else {
            // 群排行 → 显示全部
            bot.sendGroupReply(groupId, buildMessageRank(groupIdStr));
        }
    }

    private String buildMessageRank(String groupId) {
        StringBuilder sb = new StringBuilder("💬 发言排行 TOP5：\n");
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, total_messages FROM users ORDER BY total_messages DESC LIMIT 5")) {
            try (ResultSet rs = ps.executeQuery()) {
                int i = 1;
                String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    int count = rs.getInt("total_messages");
                    sb.append(medals[i-1]).append(" ").append(uid).append(": ").append(count).append("条\n");
                    i++;
                }
            }
        } catch (Exception e) {
            logger.error("发言排行查询失败", e);
            return "排行查询失败~";
        }
        return sb.toString();
    }

    private String buildLuckRank(String groupId) {
        // 幸运值基于 userId+日期哈希，收集群内所有已知用户的幸运值排序
        StringBuilder sb = new StringBuilder("🍀 今日幸运排行 TOP5：\n");
        Map<String, Integer> luckMap = new TreeMap<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM users LIMIT 100")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    try {
                        luckMap.put(uid, LuckUtil.getDailyLuck(Long.parseLong(uid)));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.error("幸运排行查询失败", e);
        }

        int[] idx = {0};
        String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        luckMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    sb.append(medals[idx[0]]).append(" ").append(e.getKey())
                      .append(": ").append(e.getValue()).append("分\n");
                    idx[0]++;
                });
        return sb.toString();
    }

    private String buildAffinityRank(String groupId) {
        StringBuilder sb = new StringBuilder("💕 好感度排行 TOP5：\n");
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, affinity_score FROM user_affinity ORDER BY affinity_score DESC LIMIT 5")) {
            try (ResultSet rs = ps.executeQuery()) {
                int i = 1;
                String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
                while (rs.next()) {
                    sb.append(medals[i-1]).append(" ").append(rs.getString("user_id"))
                      .append(": ").append(rs.getInt("affinity_score")).append("分\n");
                    i++;
                }
            }
        } catch (Exception e) {
            logger.error("好感度排行查询失败", e);
            return "好感度排行查询失败~";
        }
        if (sb.toString().equals("💕 好感度排行 TOP5：\n")) {
            return "暂无好感度数据~";
        }
        return sb.toString();
    }
}
