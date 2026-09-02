package com.start.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorMonitorQuotaCooldownTest {

    @Test
    void quotaCooldownIsLongerThanOneMonitorInterval() throws Exception {
        Field cooldown = ErrorMonitorService.class.getDeclaredField("AUDIT_QUOTA_COOLDOWN_MS");
        cooldown.setAccessible(true);
        long cooldownMs = cooldown.getLong(null);

        Field interval = ErrorMonitorService.class.getDeclaredField("SCAN_INTERVAL_MINUTES");
        interval.setAccessible(true);
        int scanIntervalMinutes = interval.getInt(null);

        assertTrue(cooldownMs > scanIntervalMinutes * 60_000L,
                "配额冷却时间必须长于单次监控间隔，避免每轮重复请求");
    }

    @Test
    void quotaCooldownSkipsAuditCallUntilExpiry() throws Exception {
        ErrorMonitorService service = new ErrorMonitorService(null);
        Field cooldownUntil = ErrorMonitorService.class.getDeclaredField("auditQuotaCooldownUntil");
        cooldownUntil.setAccessible(true);
        cooldownUntil.setLong(service, System.currentTimeMillis() + 60_000L);

        Method callAuditApi = ErrorMonitorService.class.getDeclaredMethod("callAuditApi", java.util.List.class);
        callAuditApi.setAccessible(true);
        callAuditApi.invoke(service, java.util.List.of("test error"));

        assertTrue(cooldownUntil.getLong(service) > System.currentTimeMillis(),
                "冷却期内不应清除配额冷却状态");
        Field deferred = ErrorMonitorService.class.getDeclaredField("deferredAuditErrors");
        deferred.setAccessible(true);
        assertTrue(((Deque<?>) deferred.get(service)).contains("test error"),
                "冷却期间发现的错误必须保留，待 API 恢复后补审");
    }
}
