package com.start.model;

import java.time.LocalDateTime;

public record ProfileFactSummary(
        long id,
        String kind,
        String epistemicType,
        String claim,
        String stability,
        double confidence,
        String scope,
        LocalDateTime lastObservedAt,
        LocalDateTime validUntil) {
}
