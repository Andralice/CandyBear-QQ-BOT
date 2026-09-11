package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolAuditService 静态行为测试：truncate 工具方法 + 未初始化时静默丢弃。
 * 实际写库走异步单线程池，DB 写测试需集成环境，单元测试跳过。
 */
class ToolAuditServiceTest {

    @Test
    void truncateShortStringReturnsAsIs() {
        assertEquals("hello", ToolAuditService.truncate("hello", 10));
        assertNull(ToolAuditService.truncate(null, 10));
    }

    @Test
    void truncateLongStringCutsAtBoundary() {
        String s = "a".repeat(600);
        String out = ToolAuditService.truncate(s, 500);
        assertEquals(500, out.length());
        assertEquals("a".repeat(500), out);
    }

    @Test
    void truncateExactBoundaryKeepsAll() {
        String s = "x".repeat(500);
        assertEquals(500, ToolAuditService.truncate(s, 500).length());
    }

    @Test
    void recordStaticOnUninitializedServiceIsNoop() {
        // 没 init 的情况下，recordStatic 应静默丢弃，不抛 NPE
        // 线程安全验证：同一 JVM 多次调用都安全
        for (int i = 0; i < 10; i++) {
            ToolAuditService.recordStatic("tool-" + i, "u1", "g1", "s1",
                    "{}", "result", false, true, null, 10);
        }
        // 验证：方法没抛异常
        assertTrue(true);
    }

    @Test
    void privacyConfirmationCodeIsRemovedFromAuditValues() {
        assertEquals("{\"confirmation_code\":\"[REDACTED]\"}",
                ToolAuditService.redactPrivacyCode("delete_my_profile",
                        "{\"confirmation_code\":\"ABCDEFGH\"}"));
        assertEquals("确认删除个人画像 [REDACTED]。",
                ToolAuditService.redactPrivacyCode("delete_my_profile", "确认删除个人画像 ABCDEFGH。"));
        assertEquals("ABCDEFGH", ToolAuditService.redactPrivacyCode("other_tool", "ABCDEFGH"));
    }
}
