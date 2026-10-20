package com.start.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Evidence attached to one group conversation session.
 *
 * The evidence describes what was observed or what an external source said;
 * it is deliberately not a personal-memory fact about CandyBear.
 */
public record GroupConversationEvidence(
        String evidenceId,
        String sessionId,
        Type type,
        String sourceMessageId,
        String imageMessageId,
        int imageIndex,
        String content,
        String sourceUrl,
        double confidence,
        Status status,
        long createdAt,
        boolean allowSummary
) {
    public enum Type {
        IMAGE_DESCRIPTION,
        IMAGE_WEB_SEARCH,
        WEB_PAGE,
        GROUP_MESSAGE,
        UNVERIFIED_EXTERNAL_CONTENT
    }

    public enum Status {
        CONFIRMED,
        UNVERIFIED,
        FAILED
    }

    public GroupConversationEvidence {
        evidenceId = clean(evidenceId);
        sessionId = clean(sessionId);
        type = type == null ? Type.UNVERIFIED_EXTERNAL_CONTENT : type;
        sourceMessageId = clean(sourceMessageId);
        imageMessageId = clean(imageMessageId);
        imageIndex = Math.max(0, imageIndex);
        content = limit(content, 2_000);
        sourceUrl = limit(sourceUrl, 1_000);
        confidence = Math.max(0D, Math.min(1D, confidence));
        status = status == null ? Status.UNVERIFIED : status;
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
    }

    public static GroupConversationEvidence of(String sessionId, Type type, String sourceMessageId,
                                                String imageMessageId, int imageIndex, String content,
                                                String sourceUrl, double confidence, Status status,
                                                boolean allowSummary) {
        String fingerprint = String.join("|", clean(sessionId), type == null ? "" : type.name(),
                clean(sourceMessageId), clean(imageMessageId), String.valueOf(imageIndex), clean(content));
        return new GroupConversationEvidence(UUID.nameUUIDFromBytes(
                        fingerprint.getBytes(StandardCharsets.UTF_8)).toString(), sessionId, type,
                sourceMessageId, imageMessageId, imageIndex, content, sourceUrl, confidence, status,
                System.currentTimeMillis(), allowSummary);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String limit(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
