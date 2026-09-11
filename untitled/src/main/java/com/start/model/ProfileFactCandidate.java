package com.start.model;

import java.time.LocalDateTime;

/** Model output is stored as a candidate until deterministic rules promote it. */
public record ProfileFactCandidate(
        long id,
        String subjectUserId,
        String sourceGroupId,
        String kind,
        String epistemicType,
        String claimKey,
        String claim,
        String polarity,
        String status,
        String stability,
        double confidence,
        String scope,
        LocalDateTime firstObservedAt,
        LocalDateTime lastObservedAt,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        String auditRationale,
        Long analysisTaskId,
        String modelVersion,
        String dedupeKey) {
}
