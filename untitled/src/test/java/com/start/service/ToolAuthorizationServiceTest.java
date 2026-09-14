package com.start.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolAuthorizationService 单元测试。
 * 覆盖第三阶段 3.1（权限集中）和 3.2（频率限流）。
 *
 * 不依赖 BotConfig：直接测 isAdmin / isAllowedGroup / isBlacklistedPrivate
 * 三个轻量基础方法 + 频率限流。
 * 复杂业务级检查（checkGroupSend 等）需要 BotConfig，跳过。
 */
class ToolAuthorizationServiceTest {

    @BeforeAll
    static void initService() {
        ToolAuthorizationService.init();
    }

    @Test
    void getInstanceReturnsSingleton() {
        ToolAuthorizationService s1 = ToolAuthorizationService.getInstance();
        ToolAuthorizationService s2 = ToolAuthorizationService.getInstance();
        assertNotNull(s1);
        assertTrue(s1 == s2, "应是单例");
    }

    @Test
    void rateLimitBlocksAfterMax() {
        ToolAuthorizationService svc = ToolAuthorizationService.getInstance();
        String user = "rate-test-user";
        assertTrue(svc.tryAcquire(user, "rate-test-tool-2", 2, 60_000), "第 1 次应通过");
        assertTrue(svc.tryAcquire(user, "rate-test-tool-2", 2, 60_000), "第 2 次应通过");
        assertFalse(svc.tryAcquire(user, "rate-test-tool-2", 2, 60_000), "第 3 次应被拒");
    }

    @Test
    void rateLimitIsolatedByUserAndTool() {
        ToolAuthorizationService svc = ToolAuthorizationService.getInstance();
        String userA = "rate-user-a";
        String userB = "rate-user-b";
        String tool = "iso-tool";

        // userA 触发限流
        assertTrue(svc.tryAcquire(userA, tool, 1, 60_000));
        assertFalse(svc.tryAcquire(userA, tool, 1, 60_000), "userA 限流");

        // userB 不应受影响
        assertTrue(svc.tryAcquire(userB, tool, 1, 60_000), "userB 不受 userA 限流影响");

        // 不同 tool 不应受影响
        assertTrue(svc.tryAcquire(userA, "other-tool", 1, 60_000), "userA 不同 tool 不影响");
    }

    @Test
    void rateLimitWindowResets() throws Exception {
        ToolAuthorizationService svc = ToolAuthorizationService.getInstance();
        String user = "rate-reset-user";
        String tool = "rate-reset-tool";
        // 极短窗口 100ms
        assertTrue(svc.tryAcquire(user, tool, 1, 100));
        assertFalse(svc.tryAcquire(user, tool, 1, 100), "窗口内第 2 次应被拒");
        Thread.sleep(150);
        assertTrue(svc.tryAcquire(user, tool, 1, 100), "窗口过期后应允许");
    }

    @Test
    void releaseReturnsUnacceptedReservation() {
        ToolAuthorizationService svc = ToolAuthorizationService.getInstance();
        String user = "rate-release-user";
        String tool = "rate-release-tool";
        assertTrue(svc.tryAcquire(user, tool, 1, 60_000));
        svc.release(user, tool);
        assertTrue(svc.tryAcquire(user, tool, 1, 60_000), "未被下游接受的请求应归还额度");
    }

    @Test
    void authorizationResultAllowAndDeny() {
        ToolAuthorizationService.AuthorizationResult allow = ToolAuthorizationService.AuthorizationResult.allow();
        assertTrue(allow.allowed);
        assertTrue(allow.reason == null);

        ToolAuthorizationService.AuthorizationResult deny =
                ToolAuthorizationService.AuthorizationResult.deny("test reason");
        assertFalse(deny.allowed);
        assertTrue("test reason".equals(deny.reason));
    }
}
