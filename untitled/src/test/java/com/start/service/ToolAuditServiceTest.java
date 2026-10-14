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
    void executionStatusUsesTopLevelEnvelopeRatherThanChatContents() {
        assertEquals("查询消息上下文失败", ToolAuditService.failureReason("query_message_context",
                "{\"status\":\"ERROR\",\"error\":\"查询消息上下文失败\"}"));
        assertEquals("暂不可用", ToolAuditService.failureReason("query_thread",
                "{\"success\":false,\"error\":\"暂不可用\"}"));
        assertNull(ToolAuditService.failureReason("query_message_context",
                "{\"status\":\"OK\",\"items\":[{\"content\":\"失败了，黑名单不可用\",\"status\":\"ERROR\"}]}"));
        assertNull(ToolAuditService.failureReason("query_message_context", "没有消息"));
        assertEquals("画图失败：服务超时", ToolAuditService.failureReason("generate_image", "画图失败：服务超时"));
        assertNull(ToolAuditService.failureReason("search_chat_history", "画图失败：服务超时"));
    }

    @Test
    void auditPersistsReturnedErrorAsFailureEvenWhenCallerSaysSuccess() throws Exception {
        var captured = new java.util.concurrent.LinkedBlockingQueue<com.start.model.ToolAuditLog>();
        var repo = new com.start.repository.ToolAuditLogRepository(null) {
            @Override public void insert(com.start.model.ToolAuditLog log) { captured.add(log); }
        };
        var field = ToolAuditService.class.getDeclaredField("instance");
        field.setAccessible(true);
        Object previous = field.get(null);
        var constructor = ToolAuditService.class.getDeclaredConstructor(com.start.repository.ToolAuditLogRepository.class);
        constructor.setAccessible(true);
        var service = constructor.newInstance(repo);
        field.set(null, service);
        try {
            service.record("query_message_context", "u", "g", "s", "{}",
                    "{\"status\":\"ERROR\",\"error\":\"查询消息上下文失败\"}", false, true, null, 5);
            var log = captured.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(log);
            org.junit.jupiter.api.Assertions.assertFalse(log.isSuccess());
            org.junit.jupiter.api.Assertions.assertFalse(log.isRejected());
            assertEquals("查询消息上下文失败", log.getErrorMessage());
        } finally {
            ToolAuditService.shutdown();
            field.set(null, previous);
        }
    }

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
