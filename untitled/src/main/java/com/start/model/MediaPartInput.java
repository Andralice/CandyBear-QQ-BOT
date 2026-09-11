package com.start.model;

/** Persisted message-part metadata used by parsers without exposing raw payloads to tools. */
public record MediaPartInput(
        long partId,
        long messageId,
        String partType,
        String rawPartJson,
        String textValue,
        String sourceUrl,
        String providerFileId,
        String targetId,
        String mimeType,
        String availabilityStatus) {
}
