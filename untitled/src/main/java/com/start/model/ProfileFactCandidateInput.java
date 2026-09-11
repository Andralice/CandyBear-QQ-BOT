package com.start.model;

import java.time.LocalDateTime;
import java.util.List;

/** Validated boundary object for one model-proposed fact. */
public record ProfileFactCandidateInput(
        String subjectUserId,
        String sourceGroupId,
        String kind,
        String epistemicType,
        String claimKey,
        String claim,
        String polarity,
        String stability,
        double confidence,
        String scope,
        LocalDateTime observedAt,
        LocalDateTime validUntil,
        String auditRationale,
        Long analysisTaskId,
        String modelVersion,
        List<Long> evidenceMessageIds,
        String modality,
        String quoteOrSpanJson,
        String availabilitySnapshot) {
}
