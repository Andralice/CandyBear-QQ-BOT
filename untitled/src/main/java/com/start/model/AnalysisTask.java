package com.start.model;

import java.time.LocalDateTime;

/** A database-backed, leaseable analysis task. */
public record AnalysisTask(
        long id,
        String taskKey,
        String taskType,
        String status,
        int priority,
        String scopeGroupId,
        String scopeUserId,
        String inputRefsJson,
        int attemptCount,
        int maxAttempts,
        LocalDateTime notBefore,
        String leaseOwner,
        LocalDateTime leaseUntil) {
}
