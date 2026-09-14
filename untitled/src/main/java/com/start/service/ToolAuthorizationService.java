package com.start.service;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用集中授权 + 频率限流服务（第三阶段 3.1 / 3.2）。
 *
 * 集中处理：
 * - 当前用户身份（realUserId）
 * - 当前群身份（groupId）
 * - 管理员权限
 * - 群白名单
 * - 私聊黑名单
 * - 工具调用频率
 *
 * 工具不再直接读 BotConfig，转调本 service。返回 AuthorizationResult，
 * 业务侧只需判 allowed 即可。
 */
public class ToolAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(ToolAuthorizationService.class);

    private static volatile ToolAuthorizationService instance;

    // (userId + "|" + toolName) → 最近调用时间戳 deque
    private final Map<String, Deque<Long>> rateLimitHistory = new ConcurrentHashMap<>();

    private ToolAuthorizationService() {}

    public static synchronized void init() {
        if (instance == null) {
            instance = new ToolAuthorizationService();
            logger.info("ToolAuthorizationService 已初始化");
        }
    }

    public static ToolAuthorizationService getInstance() {
        return instance;
    }

    // ===== 基础权限（轻量） =====

    public boolean isAdmin(String userId) {
        if (userId == null || userId.isBlank()) return false;
        try {
            return userId.equals(String.valueOf(BotConfig.getAdminQq()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isAllowedGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) return false;
        try {
            return WhitelistService.getInstance().isAllowedGroup(Long.parseLong(groupId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isBlacklistedPrivate(String userId) {
        if (userId == null || userId.isBlank()) return false;
        try {
            return BotConfig.getPrivateBlacklist().contains(Long.parseLong(userId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ===== 频率限流（3.2 工具级） =====

    /**
     * 检查 (userId, toolName) 是否在窗口期内调用次数未超 maxPerWindow。
     * 通过返回 true 并记录本次调用时间；返回 false 表示触发限流。
     */
    public boolean tryAcquire(String userId, String toolName, int maxPerWindow, long windowMs) {
        if (userId == null || toolName == null) return true;  // 兜底放行
        String key = userId + "|" + toolName;
        long now = System.currentTimeMillis();
        Deque<Long> history = rateLimitHistory.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (history) {
            // 清掉过期的
            while (!history.isEmpty() && now - history.peekFirst() > windowMs) {
                history.pollFirst();
            }
            if (history.size() >= maxPerWindow) {
                return false;
            }
            history.addLast(now);
            return true;
        }
    }

    /** Return one reservation when the downstream operation was never accepted. */
    public void release(String userId, String toolName) {
        if (userId == null || toolName == null) return;
        Deque<Long> history = rateLimitHistory.get(userId + "|" + toolName);
        if (history == null) return;
        synchronized (history) {
            if (!history.isEmpty()) history.pollLast();
            if (history.isEmpty()) rateLimitHistory.remove(userId + "|" + toolName, history);
        }
    }

    // ===== 业务级授权检查（统一返回） =====

    /**
     * 群发消息授权检查。返回 AuthorizationResult。
     * 检查项：群白名单 + 私聊黑名单（caller）+ 频率限流。
     */
    public AuthorizationResult checkGroupSend(String targetGroupId, String message, String callerUserId) {
        // 1. 目标群必须在白名单
        if (!isAllowedGroup(targetGroupId)) {
            return AuthorizationResult.deny("目标群不在白名单中");
        }
        // 2. 调用者不能在私聊黑名单
        if (isBlacklistedPrivate(callerUserId)) {
            return AuthorizationResult.deny("调用者已被限制使用此功能");
        }
        // 3. 频率限流：每用户每分钟最多 5 次 send_group_msg
        if (!tryAcquire(callerUserId, "send_group_msg", 5, TimeUnit.MINUTES.toMillis(1))) {
            return AuthorizationResult.deny("群发消息频率超限（每分钟最多 5 次）");
        }
        return AuthorizationResult.allow();
    }

    /**
     * 私聊消息授权检查。
     * 检查项：私聊黑名单（caller）+ 频率限流。
     */
    public AuthorizationResult checkPrivateSend(String targetUserId, String message, String callerUserId) {
        if (callerUserId == null) {
            return AuthorizationResult.deny("无法确定发起者身份");
        }
        if (isBlacklistedPrivate(callerUserId)) {
            return AuthorizationResult.deny("私聊功能不可用：你已被限制使用此功能");
        }
        if (!tryAcquire(callerUserId, "send_private_msg", 5, TimeUnit.MINUTES.toMillis(1))) {
            return AuthorizationResult.deny("私聊频率超限（每分钟最多 5 次）");
        }
        return AuthorizationResult.allow();
    }

    /**
     * 知识库管理授权检查。requireAdmin=true 时要求调用者是管理员。
     * 检查顺序：先黑名单后 admin（黑名单优先）。
     */
    public AuthorizationResult checkKnowledgeMutation(String callerUserId, boolean requireAdmin) {
        if (isBlacklistedPrivate(callerUserId)) {
            return AuthorizationResult.deny("你已被限制使用此功能");
        }
        if (requireAdmin && !isAdmin(callerUserId)) {
            return AuthorizationResult.deny("只有归儿才能修改知识库");
        }
        return AuthorizationResult.allow();
    }

    // ===== 返回类型 =====

    public static class AuthorizationResult {
        public final boolean allowed;
        public final String reason;

        private AuthorizationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static AuthorizationResult allow() {
            return new AuthorizationResult(true, null);
        }

        public static AuthorizationResult deny(String reason) {
            return new AuthorizationResult(false, reason);
        }
    }
}
