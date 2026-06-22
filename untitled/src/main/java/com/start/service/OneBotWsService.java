package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OneBot WebSocket 服务
 */
public class OneBotWsService {
    private static final Logger logger = LoggerFactory.getLogger(OneBotWsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Main botClient;

    // ✅ 扩展缓存：存储完整成员信息（QQ → 昵称）
    private static final Map<Long, CachedGroupMembersFull> groupMemberFullCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRE_MS = 60 * 60 * 1000; // 1小时

    public OneBotWsService(Main botClient) {
        this.botClient = botClient;
    }

    // ===== 原有方法：仅返回 QQ 列表（保持兼容）=====
    public CompletableFuture<List<Long>> getGroupMemberQqListAsync(long groupId) {
        return getGroupMemberDisplayNamesAsync(groupId)
                .thenApply(map -> new ArrayList<>(map.keySet().stream()
                        .map(Long::parseLong)
                        .toList()));
    }

    // ===== 新增方法：获取 QQ → 显示名 映射 =====
    public CompletableFuture<Map<String, String>> getGroupMemberDisplayNamesAsync(long groupId) {
        long now = System.currentTimeMillis();

        CachedGroupMembersFull cached = groupMemberFullCache.get(groupId);
        if (cached != null && now < cached.expireTime) {
            logger.debug("✅ 使用缓存的群 {} 成员昵称映射（{} 人）", groupId, cached.qqToName.size());
            return CompletableFuture.completedFuture(new HashMap<>(cached.qqToName));
        }

        logger.info("🔄 正在加载群 {} 的完整成员信息...", groupId);

        ObjectNode params = MAPPER.createObjectNode();
        params.put("group_id", groupId);

        return botClient.callOneBotApi("get_group_member_list", params)
                .thenApply(response -> {
                    Map<String, String> qqToName = new HashMap<>();

                    if (response == null || !"ok".equals(response.path("status").asText())) {
                        logger.warn("❌ 群 {} 成员列表 API 失败", groupId);
                        return qqToName;
                    }

                    JsonNode data = response.path("data");
                    if (!data.isArray()) {
                        logger.warn("❌ 群 {} 返回数据不是数组", groupId);
                        return qqToName;
                    }

                    for (JsonNode member : data) {
                        long userId = member.path("user_id").asLong();
                        if (userId <= 10000) continue; // 过滤无效账号

                        String qqStr = String.valueOf(userId);
                        String card = member.path("card").asText();
                        String nickname = member.path("nickname").asText();
                        String displayName = !card.isEmpty() ? card : (!nickname.isEmpty() ? nickname : qqStr);

                        qqToName.put(qqStr, displayName);
                    }

                    groupMemberFullCache.put(groupId, new CachedGroupMembersFull(qqToName, now + CACHE_EXPIRE_MS));
                    logger.info("✅ 成功缓存群 {} 的 {} 名成员（含昵称）", groupId, qqToName.size());

                    return new HashMap<>(qqToName);
                });
    }

    // ===== 缓存结构：完整成员信息 =====
    private static class CachedGroupMembersFull {
        final Map<String, String> qqToName;
        final long expireTime;

        CachedGroupMembersFull(Map<String, String> qqToName, long expireTime) {
            this.qqToName = qqToName;
            this.expireTime = expireTime;
        }
    }

    // 同步方法（可选）
    public Map<String, String> getGroupMemberDisplayNames(long groupId) {
        try {
            return getGroupMemberDisplayNamesAsync(groupId).get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("⚠️ 同步获取群成员昵称失败", e);
            return Collections.emptyMap();
        }
    }
    /**
     * 获取单个群成员的头像 URL（通过 get_group_member_info）
     */
    public CompletableFuture<String> getGroupMemberAvatarUrlAsync(long groupId, long userId) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("group_id", groupId);
        params.put("user_id", userId);

        return botClient.callOneBotApi("get_group_member_info", params)
                .thenApply(response -> {
                    if (response == null || !"ok".equals(response.path("status").asText())) {
                        logger.warn("❌ 获取群 {} 成员 {} 头像信息失败", groupId, userId);
                        return getDefaultAvatarUrl(userId); // 回退到默认头像
                    }

                    JsonNode data = response.path("data");
                    String avatarUrl = data.path("avatar_url").asText();

                    if (avatarUrl.isEmpty()) {
                        logger.debug("群 {} 成员 {} 无 avatar_url，使用默认", groupId, userId);
                        return getDefaultAvatarUrl(userId);
                    }

                    return avatarUrl;
                });
    }

    private String getDefaultAvatarUrl(long userId) {
        // 腾讯官方默认头像（即使用户没设置也会返回此图）
        return "https://q1.qlogo.cn/g?b=qq&nk=" + userId + "&s=640";
    }
}