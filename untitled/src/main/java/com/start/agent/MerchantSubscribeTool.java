package com.start.agent;

import com.start.repository.MerchantRepository;
import com.start.repository.MerchantRepository.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 远行商人订阅管理工具，供 AI Agent 调用。
 */
public class MerchantSubscribeTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(MerchantSubscribeTool.class);

    private final MerchantRepository repo;

    public MerchantSubscribeTool(MerchantRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "lokowang_merchant_subscribe"; }

    @Override
    public String getDescription() {
        return "管理远行商人订阅提醒。subscribe=订阅, unsubscribe=取消, view=查看当前群的订阅列表。" +
               "用户没指定商品时默认「棱镜球,炫彩精灵蛋,国王球」，并询问是否需要添加其他。用户没指定通知方式时默认 at。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(
            "action", Map.of("type", "string", "description", "subscribe / unsubscribe / view", "enum", List.of("subscribe", "unsubscribe", "view")),
            "group_id", Map.of("type", "number", "description", "群号。view 时不传表示查全部群"),
            "user_id", Map.of("type", "number", "description", "用户 QQ 号，subscribe/unsubscribe 时必填"),
            "keywords", Map.of("type", "string", "description", "关注的关键词，逗号分隔。空字符串=全部商品。未指定默认「棱镜球,炫彩精灵蛋,国王球」"),
            "notify_type", Map.of("type", "string", "description", "通知方式：at（群内@）或 pm（私聊），默认 at", "enum", List.of("at", "pm"))
        ), "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = String.valueOf(args.getOrDefault("action", "subscribe"));
        long groupId = parseLongSafe(args.get("group_id"));
        long userId = parseLongSafe(args.get("user_id"));

        if ("view".equals(action)) {
            return handleView(groupId);
        }
        if ("unsubscribe".equals(action)) {
            return handleUnsubscribe(groupId, userId);
        }
        return handleSubscribe(groupId, userId, args);
    }

    private String handleView(long groupId) {
        List<Subscription> subs;
        if (groupId > 0) {
            subs = repo.getEnabledSubscriptions(groupId);
        } else {
            subs = repo.getAllEnabledSubscriptions();
        }
        if (subs.isEmpty()) {
            return groupId > 0 ? "群 " + groupId + " 当前没有任何远行商人订阅。" : "当前没有任何远行商人订阅。";
        }
        StringBuilder sb = new StringBuilder("当前远行商人订阅列表：\n");
        for (Subscription s : subs) {
            sb.append("· 群").append(s.groupId).append(" QQ").append(s.userId).append(" → ");
            sb.append(s.matchAll ? "全部商品" : s.keywords);
            sb.append("（").append("pm".equals(s.notifyType) ? "私聊" : "@").append("）\n");
        }
        return sb.toString().trim();
    }

    private String handleUnsubscribe(long groupId, long userId) {
        if (userId == 0) return "❌ 取消订阅需要提供 user_id。";
        repo.deleteSubscription(groupId, userId);
        logger.info("AI取消订阅: group={}, user={}", groupId, userId);
        return "✅ 已取消群 " + groupId + " 用户 " + userId + " 的远行商人订阅。";
    }

    private String handleSubscribe(long groupId, long userId, Map<String, Object> args) {
        if (userId == 0) return "❌ 订阅需要提供 user_id。";
        boolean isPrivate = groupId == 0;

        String keywords = String.valueOf(args.getOrDefault("keywords", ""));
        if (keywords.isEmpty()) {
            keywords = "棱镜球,炫彩精灵蛋,国王球";
        }
        String notifyType = isPrivate ? "pm" : String.valueOf(args.getOrDefault("notify_type", "at"));
        boolean matchAll = "全部".equals(keywords.trim());

        repo.upsertSubscription(groupId, userId, keywords, matchAll, notifyType);
        String desc = matchAll ? "全部商品" : keywords;
        String method = isPrivate ? "私聊通知" : ("pm".equals(notifyType) ? "私聊通知" : "@提醒");
        logger.info("AI订阅: group={}, user={}, keywords={}, matchAll={}, notify={}",
                groupId, userId, keywords, matchAll, notifyType);
        return "✅ 已为群 " + groupId + " 用户 " + userId + " 订阅「" + desc + "」（" + method + "）。";
    }

    private long parseLongSafe(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }
}
