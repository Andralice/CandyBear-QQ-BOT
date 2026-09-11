package com.start.model;

import java.time.LocalDateTime;

/** Read-only parser result with explicit availability. */
public record MediaArtifactSummary(
        long id,
        long partId,
        String artifactType,
        String parserName,
        String parserVersion,
        String status,
        String contentJson,
        Double confidence,
        String language,
        String errorCode,
        String errorDetail,
        LocalDateTime completedAt) {
}
