package com.start.service;

import com.start.repository.MediaArtifactRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Auditable, non-destructive retention: raw fields are redacted and derived rows are marked expired. */
public final class EvidenceRetentionService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(EvidenceRetentionService.class);
    private final MessageRepository messageRepository = new MessageRepository();
    private final MediaArtifactRepository artifactRepository = new MediaArtifactRepository();
    private final ProfileFactRepository factRepository =
            new ProfileFactRepository(com.start.config.DatabaseConfig.getDataSource());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Evidence-Retention");
        thread.setDaemon(true);
        return thread;
    });
    private volatile EvidenceRetentionPolicy policy;

    public void start() {
        policy = EvidenceRetentionPolicy.forPath(Path.of(System.getProperty("user.dir", ".")));
        logger.info("证据保留策略: raw={}d image/audio={}d video={}d derived={}d factTemp={}d factStable={}d usableBytes={} pressureMode={}",
                policy.rawEvidence().toDays(), policy.imageAndAudio().toDays(), policy.video().toDays(),
                policy.derivedArtifacts().toDays(), policy.temporaryFactValidity().toDays(),
                policy.stableFactValidity().toDays(), policy.usableBytes(), policy.pressureMode());
        scheduler.scheduleWithFixedDelay(this::runOnceSafely, 10, 24, TimeUnit.HOURS);
    }

    public void runOnceSafely() {
        try { runOnce(); }
        catch (Exception e) { logger.warn("证据保留清理失败: {}", e.getMessage()); }
    }

    public void runOnce() throws Exception {
        EvidenceRetentionPolicy current = EvidenceRetentionPolicy.forPath(
                Path.of(System.getProperty("user.dir", ".")));
        policy = current;
        LocalDateTime now = LocalDateTime.now();
        int redacted = messageRepository.redactRawEvidenceBefore(now.minus(current.rawEvidence()));
        int images = artifactRepository.expireArtifactsBefore("IMAGE_DESCRIPTION", now.minus(current.imageAndAudio()));
        int audio = artifactRepository.expireArtifactsBefore("AUDIO_TRANSCRIPT", now.minus(current.imageAndAudio()));
        int video = artifactRepository.expireArtifactsBefore("VIDEO_DESCRIPTION", now.minus(current.video()));
        int derived = artifactRepository.expireArtifactsBefore("LINK_PREVIEW", now.minus(current.derivedArtifacts()))
                + artifactRepository.expireArtifactsBefore("FORWARD_CHAIN", now.minus(current.derivedArtifacts()))
                + artifactRepository.expireArtifactsBefore("FILE_TEXT", now.minus(current.derivedArtifacts()));
        int facts = factRepository.expireInactiveFacts(now,
                now.minus(current.temporaryFactValidity()), now.minus(current.stableFactValidity()));
        if (redacted + images + audio + video + derived + facts > 0) {
            logger.info("证据保留清理完成: rawRedacted={}, image={}, audio={}, video={}, derived={}, factsExpired={}",
                    redacted, images, audio, video, derived, facts);
        }
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
