package com.start.model;

/** Ordered, immutable OneBot message segment captured as raw evidence. */
public record MessagePart(
        int partIndex,
        String partType,
        String rawPartJson,
        String textValue,
        String sourceUrl,
        String providerFileId,
        String targetId,
        String mimeType,
        Long byteSize,
        String availabilityStatus) {
}
