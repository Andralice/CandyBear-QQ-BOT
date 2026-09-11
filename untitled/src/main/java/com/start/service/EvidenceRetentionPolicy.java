package com.start.service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Retention is selected from the actual local filesystem headroom. */
public record EvidenceRetentionPolicy(
        Duration rawEvidence,
        Duration imageAndAudio,
        Duration video,
        Duration derivedArtifacts,
        long usableBytes,
        boolean pressureMode) {

    private static final long MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024;

    public static EvidenceRetentionPolicy forPath(Path path) {
        long usable = -1;
        try {
            FileStore store = Files.getFileStore(path.toAbsolutePath());
            usable = store.getUsableSpace();
        } catch (IOException ignored) {
            // Keep the conservative normal policy when filesystem information is unavailable.
        }
        boolean pressure = usable >= 0 && usable < MIN_FREE_BYTES;
        if (pressure) {
            return new EvidenceRetentionPolicy(Duration.ofDays(180), Duration.ofDays(14),
                    Duration.ofDays(3), Duration.ofDays(2), usable, true);
        }
        return new EvidenceRetentionPolicy(Duration.ofDays(365), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(3), usable, false);
    }
}
