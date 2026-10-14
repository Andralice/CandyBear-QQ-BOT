package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import com.start.util.MessageUtil;

import javax.sql.DataSource;
import java.net.URI;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Read-only, in-memory replay of persisted group messages. */
public final class ManualMessageReplayService implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONTEXT_ROWS = 100;
    private static final int MAX_RANGE_ROWS = 501;
    private static final int MAX_OPEN_SESSIONS = 20;
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_CONTEXT_CHARS = 24_000;
    private static final int MAX_MESSAGE_CHARS = 12_000;
    private static final int MAX_REPLAY_INLINE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REPLAY_INLINE_SNAPSHOT_BYTES = 20 * 1024 * 1024;
    private static final long VISION_WAIT_MS = 4_000L;
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final Pattern CQ_IMAGE = Pattern.compile("\\[CQ:image(?:,([^\\]]*))?\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");
    private static final Pattern REMOTE_REASON = Pattern.compile("REMOTE_([A-Z_]+)");

    private final DataSource dataSource;
    private final Supplier<ReplayProfile> profileSupplier;
    private final ReplayGenerator generator;
    private final ConversationInterpreter candidateInterpreter;
    private final AgnesMediaService mediaService = new AgnesMediaService();
    private final ConcurrentHashMap<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    public ManualMessageReplayService(DataSource dataSource, Supplier<ReplayProfile> profileSupplier,
                                      ReplayGenerator generator) {
        this(dataSource, profileSupplier, generator, null);
    }

    public ManualMessageReplayService(DataSource dataSource, Supplier<ReplayProfile> profileSupplier,
                                      ReplayGenerator generator, ConversationInterpreter candidateInterpreter) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.candidateInterpreter = candidateInterpreter;
    }

    public StartResult start(String groupId, long fromLocalMessageId, long toLocalMessageId) {
        return start(groupId, fromLocalMessageId, toLocalMessageId, ReplayMode.HISTORICAL);
    }

    public StartResult start(String groupId, long fromLocalMessageId, long toLocalMessageId,
                             ReplayMode mode) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (mode == null) throw new ReplayException(400, "invalid_replay_mode");
        if (fromLocalMessageId <= 0 || toLocalMessageId < fromLocalMessageId) {
            throw new ReplayException(400, "invalid_message_range");
        }

        ReplayProfile profile;
        try {
            profile = profileSupplier.get();
        } catch (Exception e) {
            throw new ReplayException(503, "replay_model_unavailable");
        }
        if (profile == null || profile.modelId() == null || profile.modelId().isBlank()
                || profile.candidate() == null || profile.candidate().apiKey().isBlank()) {
            throw new ReplayException(503, "replay_model_unavailable");
        }

        Snapshot snapshot = loadSnapshot(normalizedGroupId, fromLocalMessageId, toLocalMessageId);
        if (snapshot.range().size() >= MAX_RANGE_ROWS) {
            throw new ReplayException(413, "replay_range_too_large_max_500_messages");
        }
        if (snapshot.range().stream().noneMatch(message -> message.localId() == fromLocalMessageId)
                || snapshot.range().stream().noneMatch(message -> message.localId() == toLocalMessageId)) {
            throw new ReplayException(404, "range_endpoint_not_found_or_unavailable");
        }
        if (snapshot.range().stream().noneMatch(message -> !message.bot())) {
            throw new ReplayException(400, "range_has_no_human_messages");
        }

        String replayId = UUID.randomUUID().toString();
        ReplaySession session = new ReplaySession(normalizedGroupId, snapshot.before(),
                snapshot.range(), snapshot.replyTargets(), profile, mode,
                snapshot.loadedAt(), System.currentTimeMillis() + SESSION_TTL_MS);
        synchronized (sessions) {
            pruneExpired();
            if (sessions.size() >= MAX_OPEN_SESSIONS) {
                throw new ReplayException(429, "too_many_replay_sessions");
            }
            sessions.put(replayId, session);
        }
        return new StartResult(replayId, normalizedGroupId, mode.name(), fromLocalMessageId, toLocalMessageId,
                profile.modelId(), profile.candidate().id(), safeEndpoint(profile.candidate().endpoint()),
                profile.candidate().protocol().name(), profile.candidate().apiKeyEnv(),
                snapshot.range().stream().map(ReplayMessage::view).toList(),
                nextHumanMessage(session).localId(), snapshot.loadedAt());
    }

    public AdvanceResult advance(String replayId, long localMessageId) {
        ReplaySession session = requireSession(replayId);
        synchronized (session) {
            session.expiresAtMs = System.currentTimeMillis() + SESSION_TTL_MS;
            if (session.failed) throw new ReplayException(409, "replay_session_requires_restart_after_model_error");
            if (session.mode != ReplayMode.HISTORICAL) {
                throw new ReplayException(409, "advance_only_available_in_historical_mode");
            }
            if (session.lastAdvanceResult != null
                    && session.lastAdvanceResult.localMessageId() == localMessageId) {
                return session.lastAdvanceResult;
            }

            ReplayMessage current = nextHumanMessage(session);
            if (current.localId() != localMessageId) {
                throw new ReplayException(409, "out_of_order_message_expected_" + current.localId());
            }

            session.lastProcessedLocalId = current.localId();
            ReplayMessage next = nextHumanMessageOrNull(session);
            session.lastAdvanceResult = new AdvanceResult(session.mode.name(), current.localId(),
                    next == null ? null : next.localId(), session.snapshotAt);
            return session.lastAdvanceResult;
        }
    }

    public InjectionResult inject(String replayId, long localMessageId) {
        return inject(replayId, localMessageId, false);
    }

    public InjectionResult inject(String replayId, long localMessageId, boolean evaluateProactiveCandidate) {
        ReplaySession session = requireSession(replayId);
        synchronized (session) {
            session.expiresAtMs = System.currentTimeMillis() + SESSION_TTL_MS;
            if (session.failed) throw new ReplayException(409, "replay_session_requires_restart_after_model_error");
            if (session.lastResult != null && session.lastResult.localMessageId() == localMessageId) {
                return session.lastResult;
            }

            ReplayMessage current = nextHumanMessage(session);
            if (current.localId() != localMessageId) {
                throw new ReplayException(409, "out_of_order_message_expected_" + current.localId());
            }

            Turn turn = buildTurn(session, current);
            long startedAt = System.nanoTime();
            String triggerEvent = "NOT_EVALUATED";
            boolean proactiveCandidate = false;
            if (evaluateProactiveCandidate) {
                if (candidateInterpreter == null) {
                    throw new ReplayException(503, "proactive_candidate_replay_unavailable");
                }
                var interpreted = candidateInterpreter.interpret(session.groupId, current.userId(),
                        current.nickname(), current.content(), current.content(), current.atUserIds());
                triggerEvent = interpreted.event().name();
                proactiveCandidate = interpreted.event() == ConversationEvent.PROBABILISTIC;
                if (!proactiveCandidate) {
                    session.lastProcessedLocalId = current.localId();
                    long latencyMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
                    ReplayMessage next = nextHumanMessageOrNull(session);
                    session.lastResult = new InjectionResult(session.mode.name(), current.localId(), "NO_CANDIDATE",
                            "", session.profile.modelId(), session.profile.candidate().id(),
                            safeEndpoint(session.profile.candidate().endpoint()),
                            session.profile.candidate().apiKeyEnv(), latencyMs, turn.contextMessageIds(),
                            turn.transcript(), turn.modelUserText(), turn.turnEvidence(),
                            current.replyProviderMessageId(), current.replyToRawId(), current.replyReference(),
                            current.replyReferenceSource(), current.replyToLocalId(), current.replyResolutionStatus(),
                            turn.imageEvidence(), turn.omittedContextMessageIds(), "",
                            session.profile.candidate().protocol().name(), 0, "", session.snapshotAt,
                            next == null ? null : next.localId(), triggerEvent, false, false, null, false);
                    return session.lastResult;
                }
                turn = withCandidatePrompt(turn, interpreted.prompt());
            }
            ReplayGeneration generation;
            try {
                generation = generator.generate(turn, session.profile);
            } catch (Exception e) {
                // A timeout may occur after the provider accepted the request. Do not silently retry and double-charge.
                session.failed = true;
                throw new ReplayException(502, "model_request_failed_session_closed_to_further_injections");
            }

            String reply = generation == null ? "" : generation.reply();
            reply = reply == null ? "" : reply.trim();
            boolean silent = reply.isBlank() || reply.equals("<NO_REPLY>");
            session.groupSceneMemory.update(session.groupId,
                    generation == null ? null : generation.groupAssessment(), current.createdAt().toEpochMilli());
            if (session.mode == ReplayMode.COUNTERFACTUAL) {
                session.generatedReplies.put(current.localId(), silent ? "" : reply);
            }
            session.lastProcessedLocalId = current.localId();
            long latencyMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
            ReplayMessage next = nextHumanMessageOrNull(session);
            session.lastResult = new InjectionResult(session.mode.name(), current.localId(), silent ? "SILENT" : "REPLY",
                    silent ? "" : reply, session.profile.modelId(), session.profile.candidate().id(),
                    safeEndpoint(session.profile.candidate().endpoint()), session.profile.candidate().apiKeyEnv(), latencyMs,
                    turn.contextMessageIds(), turn.transcript(),
                    generation == null ? turn.modelUserText() : generation.modelUserText(), turn.turnEvidence(),
                    turn.replyProviderMessageId(), turn.replyToRawId(), turn.replyReference(),
                    turn.replyReferenceSource(), turn.replyToLocalId(), turn.replyResolutionStatus(),
                    turn.imageEvidence(), turn.omittedContextMessageIds(),
                    generation == null ? "" : generation.systemPrompt(),
                    generation == null ? "" : generation.protocol(),
                    generation == null ? 0 : generation.attachedImageCount(),
                    generation == null ? "" : generation.requestBodySha256(),
                    session.snapshotAt,
                    next == null ? null : next.localId(), triggerEvent, proactiveCandidate, true,
                    generation == null ? null : generation.groupAssessment(),
                    generation != null && generation.replyToCurrentMessage());
            return session.lastResult;
        }
    }

    private static Turn withCandidatePrompt(Turn turn, String candidatePrompt) {
        String prompt = turn.prompt() + "\n\n" + candidatePrompt;
        return new Turn(turn.groupId(), turn.localMessageId(), turn.userId(), turn.nickname(), prompt,
                turn.atUserIds(), true, turn.isAtBot(), turn.addressedToBotByName(), turn.transcript(),
                turn.turnEvidence(), turn.timestamp(), turn.imageDataUris(), turn.contextMessageIds(),
                turn.replyProviderMessageId(), turn.replyToRawId(), turn.replyReference(),
                turn.replyReferenceSource(), turn.replyToLocalId(), turn.replyResolutionStatus(),
                turn.imageEvidence(), turn.omittedContextMessageIds(), turn.searchImages(),
                turn.shortTermGroupScene());
    }

    public boolean close(String replayId) {
        return replayId != null && sessions.remove(replayId) != null;
    }

    @Override
    public void close() {
        sessions.clear();
        mediaService.close();
    }

    private Turn buildTurn(ReplaySession session, ReplayMessage current) {
        ReplayMessage replyTarget = current.replyToLocalId() == null
                ? null : session.messagesById.get(current.replyToLocalId());
        List<ReplayMessage> criticalMessages = new ArrayList<>();
        criticalMessages.add(current);
        if (replyTarget != null) criticalMessages.add(replyTarget);
        criticalMessages = awaitPendingVision(criticalMessages);
        Map<Long, ReplayMessage> refreshed = criticalMessages.stream()
                .collect(Collectors.toMap(ReplayMessage::localId, value -> value, (a, b) -> b));
        current = refreshed.getOrDefault(current.localId(), current);
        if (replyTarget != null) replyTarget = refreshed.getOrDefault(replyTarget.localId(), replyTarget);
        Map<Long, ReplayMessage> evidenceMessages = new java.util.HashMap<>(session.messagesById);
        evidenceMessages.put(current.localId(), current);
        if (replyTarget != null) evidenceMessages.put(replyTarget.localId(), replyTarget);

        List<ContextLine> lines = new ArrayList<>();
        for (ReplayMessage message : session.before) {
            lines.add(ContextLine.from(refreshed.getOrDefault(message.localId(), message), false));
        }
        if (session.mode == ReplayMode.HISTORICAL) {
            for (ReplayMessage message : session.range) {
                if (message.localId() >= current.localId()) break;
                lines.add(ContextLine.from(refreshed.getOrDefault(message.localId(), message), false));
            }
        } else {
            boolean replayRangeHasHuman = false;
            for (ReplayMessage message : session.range) {
                if (message.localId() >= current.localId()) break;
                if (message.bot()) {
                    if (!replayRangeHasHuman) {
                        lines.add(ContextLine.from(refreshed.getOrDefault(message.localId(), message), false));
                    }
                    continue;
                }
                replayRangeHasHuman = true;
                lines.add(ContextLine.from(refreshed.getOrDefault(message.localId(), message), false));
                String generated = session.generatedReplies.get(message.localId());
                if (generated != null && !generated.isBlank()) {
                    lines.add(new ContextLine("replay:" + message.localId(), message.localId(),
                            String.valueOf(BotConfig.getBotQq()), BotConfig.getBotName(), generated,
                            message.createdAt(), null, null, null, null, null, null, "NONE", List.of(), true, false,
                            List.of(), true, false));
                }
            }
        }

        Long replyTargetId = replyTarget == null ? null : replyTarget.localId();
        if (replyTarget != null && lines.stream().noneMatch(line -> Objects.equals(line.localId(), replyTargetId))) {
            lines.add(0, ContextLine.from(replyTarget, true));
        }

        List<String> omittedIds = new ArrayList<>();
        while (lines.size() > 1 && formatEvidence(lines, current, replyTarget, evidenceMessages)
                .length() > MAX_CONTEXT_CHARS) {
            int remove = 0;
            while (remove < lines.size() && lines.get(remove).pinned()) remove++;
            if (remove >= lines.size()) break;
            ContextLine removed = lines.remove(remove);
            if (!removed.synthetic()) omittedIds.add(removed.id());
        }
        String transcript = formatTranscript(lines);
        List<String> ids = new ArrayList<>(lines.size() + 1);
        lines.forEach(line -> ids.add(line.id()));
        ids.add(String.valueOf(current.localId()));

        GroupAddressingEvidence.LeadingAddress address = GroupAddressingEvidence.detect(current.content());
        boolean atBot = current.atUserIds().contains(BotConfig.getBotQq());
        boolean replyToBot = replyTarget != null && replyTarget.bot();
        boolean addressedToBotByName = address.present() && address.bot();
        boolean explicitAddress = atBot || replyToBot || addressedToBotByName;
        String evidence = formatEvidence(lines, current, replyTarget, atBot, replyToBot, addressedToBotByName,
                omittedIds, evidenceMessages);
        Map<String, Object> failureDetails = replayEvidenceDetails(current, ids, transcript, evidence);
        if (current.replyReference() != null && current.replyToLocalId() == null) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(failureDetails);
            details.put("replyReference", current.replyReference());
            details.put("resolutionStatus", current.replyResolutionStatus());
            throw new ReplayException(424, "reply_target_unresolved", details);
        }

        List<ReplayImage> relevantImages = new ArrayList<>();
        if (replyTarget != null) relevantImages.addAll(replyTarget.images());
        relevantImages.addAll(current.images());
        if (relevantImages.size() > 3) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(failureDetails);
            details.put("replyTargetLocalMessageId", replyTarget == null ? 0 : replyTarget.localId());
            details.put("imageCount", relevantImages.size());
            throw new ReplayException(413, "replay_relevant_image_count_exceeds_3", details);
        }
        List<String> imageDataUris = new ArrayList<>();
        List<com.start.runtime.conversation.ConversationSession.SearchImage> searchImages = new ArrayList<>();
        Map<String, ImageEvidenceView> imageViewsByKey = new java.util.LinkedHashMap<>();
        for (ContextLine line : lines) {
            if (!line.synthetic()) {
                for (ReplayImage image : line.images()) {
                    imageViewsByKey.put(imageKey(image), image.view(!image.description().isBlank(), false,
                            "NOT_ATTEMPTED", ""));
                }
            }
        }
        for (ReplayImage image : relevantImages) {
            imageViewsByKey.putIfAbsent(imageKey(image), image.view(!image.description().isBlank(), false));
        }
        StringBuilder imageInputNotes = new StringBuilder();
        StringBuilder actualImageEvidence = new StringBuilder();
        int imageOrdinal = 0;
        for (ReplayImage image : relevantImages) {
            boolean isCurrent = image.messageLocalId() == current.localId();
            String dataUri = null;
            String sourceLoadStatus = "NOT_ATTEMPTED";
            String sourceLoadErrorCode = "";
            if (BotConfig.isBaiLianMultimodalEnabled() && "AVAILABLE".equals(image.inlinePayloadStatus())) {
                dataUri = image.inlineDataUri();
                sourceLoadStatus = "INLINE_ATTACHED";
            } else if (BotConfig.isBaiLianMultimodalEnabled() && !image.sourceUrl().isBlank()) {
                try {
                    dataUri = mediaService.loadReferenceImage(image.sourceUrl(), false);
                    sourceLoadStatus = "ATTACHED";
                } catch (Exception e) {
                    sourceLoadStatus = "FAILED";
                    sourceLoadErrorCode = safeImageLoadErrorCode(e);
                }
            } else if (!BotConfig.isBaiLianMultimodalEnabled()
                    && (!image.sourceUrl().isBlank() || !"NONE".equals(image.inlinePayloadStatus()))) {
                sourceLoadStatus = "DISABLED";
                sourceLoadErrorCode = "MULTIMODAL_DISABLED";
            } else if (!"NONE".equals(image.inlinePayloadStatus())) {
                sourceLoadStatus = "INLINE_REJECTED";
                sourceLoadErrorCode = image.inlinePayloadStatus();
            } else {
                sourceLoadErrorCode = "SOURCE_REFERENCE_MISSING";
            }
            boolean attached = dataUri != null && !dataUri.isBlank();
            if (!attached && image.description().isBlank()) {
                Map<String, Object> details = new java.util.LinkedHashMap<>(failureDetails);
                details.put("messageLocalId", image.messageLocalId());
                details.put("partId", image.partId() == null ? 0 : image.partId());
                details.put("artifactId", image.artifactId() == null ? 0 : image.artifactId());
                details.put("visionStatus", image.status());
                details.put("errorCode", image.errorCode() == null ? "" : image.errorCode());
                imageViewsByKey.put(imageKey(image), image.view(false, false,
                        sourceLoadStatus, sourceLoadErrorCode));
                details.put("imageEvidence", List.copyOf(imageViewsByKey.values()));
                throw new ReplayException(424, "required_image_evidence_unavailable", details);
            }
            imageViewsByKey.put(imageKey(image), image.view(!image.description().isBlank(), attached,
                    sourceLoadStatus, sourceLoadErrorCode));
            if (attached) {
                imageDataUris.add(dataUri);
                imageOrdinal++;
                searchImages.add(new com.start.runtime.conversation.ConversationSession.SearchImage(
                        dataUri, "", imageOrdinal, image.partIndex() + 1));
                if (imageInputNotes.length() > 0) imageInputNotes.append('\n');
                imageInputNotes.append("【原图输入 ").append(imageOrdinal).append("：local_id=")
                        .append(image.messageLocalId()).append("，part_id=")
                        .append(image.partId() == null ? "未知" : image.partId()).append("】");
                if (actualImageEvidence.length() > 0) actualImageEvidence.append('\n');
                actualImageEvidence.append("- input_index=").append(imageOrdinal)
                        .append("; local_id=").append(image.messageLocalId())
                        .append("; part_id=").append(image.partId() == null ? "legacy" : image.partId())
                        .append("; source_sha256=").append(image.sha256().isBlank() ? "未记录" : image.sha256());
            } else if (isCurrent) {
                imageInputNotes.append("\n【当前消息图片视觉说明，来源 local_id=")
                        .append(image.messageLocalId()).append("，artifact_id=")
                        .append(image.artifactId() == null ? "legacy" : image.artifactId())
                        .append("】").append(image.description());
            }
        }

        String prompt = current.content() + imageInputNotes;
        evidence += "\n【模型实际收到的图片输入】\n"
                + (actualImageEvidence.length() == 0 ? "无原图；仅使用上方注明的文字视觉说明或缺失状态。"
                : actualImageEvidence);
        GroupSceneMemory.Snapshot priorScene = session.groupSceneMemory.get(
                session.groupId, current.createdAt().toEpochMilli());
        String shortTermScene = priorScene == null ? ""
                : priorScene.toPromptText(current.createdAt().toEpochMilli());
        return new Turn(session.groupId, current.localId(), current.userId(), current.nickname(), prompt,
                current.atUserIds(), false, atBot, addressedToBotByName, transcript, evidence,
                current.createdAt(), imageDataUris, ids, current.replyProviderMessageId(),
                current.replyToRawId(), current.replyReference(), current.replyReferenceSource(),
                current.replyToLocalId(), current.replyResolutionStatus(),
                List.copyOf(imageViewsByKey.values()), omittedIds, List.copyOf(searchImages), shortTermScene);
    }

    private static Map<String, Object> replayEvidenceDetails(ReplayMessage current, List<String> ids,
                                                              String transcript, String turnEvidence) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("modelCallMade", false);
        details.put("localMessageId", current.localId());
        details.put("replyProviderMessageId", current.replyProviderMessageId() == null ? "" : current.replyProviderMessageId());
        details.put("replyToRawId", current.replyToRawId() == null ? "" : current.replyToRawId());
        details.put("replyReference", current.replyReference() == null ? "" : current.replyReference());
        details.put("replyReferenceSource", current.replyReferenceSource());
        details.put("replyToLocalMessageId", current.replyToLocalId() == null ? "" : current.replyToLocalId());
        details.put("replyResolutionStatus", current.replyResolutionStatus());
        details.put("contextMessageIds", List.copyOf(ids));
        details.put("transcript", transcript);
        details.put("turnEvidence", turnEvidence);
        return details;
    }

    private static String imageKey(ReplayImage image) {
        return image.messageLocalId() + ":" + (image.partId() == null ? "legacy" : image.partId())
                + ":" + image.partIndex();
    }

    static String safeImageLoadErrorCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) return "HOST_LOOKUP_FAILED";
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return "TIMEOUT";
            if (cause instanceof ConnectException) return "CONNECTION_FAILED";
        }
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            Matcher remoteReason = REMOTE_REASON.matcher(message);
            return "HTTP_" + status.group(1) + (remoteReason.find() ? "_" + remoteReason.group(1) : "");
        }
        if (message.contains("不是可下载地址")) return "UNSUPPORTED_SOURCE_SCHEME";
        if (message.contains("地址无效")) return "INVALID_SOURCE_REFERENCE";
        if (message.contains("必须可从公网访问")) return "NON_PUBLIC_HOST_BLOCKED";
        if (message.contains("超过大小限制")) return "IMAGE_TOO_LARGE";
        if (message.contains("不是支持的 PNG/JPEG/GIF/WebP")) return "UNSUPPORTED_IMAGE_FORMAT";
        if (message.contains("参考图片为空")) return "EMPTY_IMAGE";
        return "DOWNLOAD_FAILED";
    }

    private static String safeImageSourceScheme(String source) {
        if (source == null || source.isBlank()) return "NONE";
        int colon = source.indexOf(':');
        if (colon <= 0 || colon > 20) return "NO_SCHEME";
        String scheme = source.substring(0, colon);
        return scheme.matches("[A-Za-z][A-Za-z0-9+.-]*") ? scheme.toLowerCase(java.util.Locale.ROOT) : "OTHER";
    }

    private static String safeImageSourceHost(String source) {
        try {
            URI uri = URI.create(source);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return "";
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> safeImageQueryParameterNames(String source) {
        try {
            String query = URI.create(source).getRawQuery();
            if (query == null || query.isBlank()) return List.of();
            TreeSet<String> names = new TreeSet<>();
            for (String parameter : query.split("&")) {
                String name = parameter.split("=", 2)[0];
                if (name.matches("[A-Za-z0-9_.-]{1,40}")) names.add(name.toLowerCase(java.util.Locale.ROOT));
            }
            return List.copyOf(names);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String safeReplayContent(String content) {
        String withImageMarkers = CQ_IMAGE.matcher(content == null ? "" : content).replaceAll("[图片]");
        return MessageUtil.extractPlainText(withImageMarkers);
    }

    private static String formatEvidence(List<ContextLine> lines, ReplayMessage current, ReplayMessage replyTarget,
                                         Map<Long, ReplayMessage> messagesById) {
        return formatEvidence(lines, current, replyTarget, false, false, false, List.of(), messagesById);
    }

    private static String formatEvidence(List<ContextLine> lines, ReplayMessage current, ReplayMessage replyTarget,
                                         boolean atBot, boolean replyToBot, boolean addressedByName,
                                         List<String> omittedIds, Map<Long, ReplayMessage> messagesById) {
        StringBuilder evidence = new StringBuilder("【回放数据库群聊证据链】来源=154服务器只读快照；local_id 均为本地 messages.id。"
                + "按时间顺序阅读；reply_provider_message_id 是明确的平台消息 ID，reply_to_id_raw 是数据库原值；"
                + "旧数据仅有 reply_to_id 时，先按当前群平台消息 ID 查找，再兼容本地行 ID；reply_to_local_id 是解析结果。"
                + "未附带的原图不代表模型看过。\n");
        evidence.append("本轮地址事实：@糖果熊=").append(atBot)
                .append("，引用糖果熊=").append(replyToBot)
                .append("，称呼糖果熊=").append(addressedByName).append('\n');
        ContextLine previous = null;
        for (int i = 0; i < lines.size(); i++) {
            ContextLine line = lines.get(i);
            appendEvidenceLine(evidence, i + 1, line, false, previous, messagesById);
            previous = line;
        }
        appendEvidenceLine(evidence, lines.size() + 1, ContextLine.from(current, false), true,
                previous, messagesById);
        if (replyTarget != null && lines.stream().noneMatch(line -> line.localId() == replyTarget.localId())) {
            evidence.append("直接引用原文 local_id=").append(replyTarget.localId()).append("：")
                    .append(evidenceText(replyTarget.content(), 1_000));
            appendImageEvidence(evidence, replyTarget.images());
            evidence.append('\n');
        }
        if (!omittedIds.isEmpty()) evidence.append("因模型上下文预算未纳入的更早消息 local_id：")
                .append(String.join(",", omittedIds)).append("；不可据此推断这些消息已被模型读取。\n");
        return evidence.toString().trim();
    }

    private static void appendEvidenceLine(StringBuilder evidence, int sequence, ContextLine line,
                                           boolean current, ContextLine previous,
                                           Map<Long, ReplayMessage> messagesById) {
        ReplayMessage replyTarget = line.replyToLocalId() == null ? null : messagesById.get(line.replyToLocalId());
        String relation = "FIRST_IN_WINDOW";
        if (previous != null) {
            if (Objects.equals(line.replyToLocalId(), previous.localId())) relation = "REPLY_TO_PREVIOUS";
            else if (line.replyToLocalId() != null) relation = "REPLY_TO_OTHER_MESSAGE";
            else if (mentions(line.atUserIds(), previous.userId())) relation = "MENTIONS_PREVIOUS_SENDER";
            else if (line.userId().equals(previous.userId())) relation = "SAME_SENDER_CONTINUATION_CANDIDATE";
            else relation = "SPEAKER_CHANGED_NO_PROTOCOL_LINK";
        }
        evidence.append("- seq=").append(sequence).append(current ? " [本轮待处理]" : "")
                .append("; local_id=").append(line.id())
                .append("; provider_message_id=").append(orUnknown(line.providerMessageId()))
                .append("; time=").append(line.createdAt())
                .append("; sender_id=").append(orUnknown(line.userId()))
                .append("; sender_name=").append(orUnknown(line.nickname()))
                .append("; sender_role=").append(line.bot() ? "糖果熊" : "群友")
                .append("; relation=").append(relation)
                .append("; at_user_ids=").append(line.atUserIds())
                .append("; reply_provider_message_id=").append(orUnknown(line.replyProviderMessageId()))
                .append("; reply_to_id_raw=").append(orUnknown(line.replyToRawId()))
                .append("; reply_reference_source=").append(line.replyReferenceSource())
                .append("; reply_reference=").append(orUnknown(line.replyReference()))
                .append("; reply_resolution=").append(line.replyResolutionStatus())
                .append("; reply_to_local_id=").append(line.replyToLocalId() == null ? "无" : line.replyToLocalId());
        if (replyTarget != null) {
            evidence.append("; reply_to_sender=").append(replyTarget.nickname()).append("(QQ=")
                    .append(replyTarget.userId()).append("); reply_to_content=")
                    .append(evidenceText(replyTarget.content(), 1_000));
            appendImageEvidence(evidence, replyTarget.images());
        } else if (line.replyReference() != null) {
            evidence.append("; reply_target=UNRESOLVED");
        }
        evidence.append("; content=").append(evidenceText(line.content(), 1_000));
        if (line.contentTruncated()) evidence.append(" [原始消息超过回放上限，内容已截断]");
        appendImageEvidence(evidence, line.images());
        evidence.append('\n');
    }

    private static void appendImageEvidence(StringBuilder out, List<ReplayImage> images) {
        for (ReplayImage image : images) {
            out.append("; image[part_id=").append(image.partId() == null ? "legacy" : image.partId())
                    .append(",part_index=").append(image.partIndex())
                    .append(",artifact_id=").append(image.artifactId() == null ? "无" : image.artifactId())
                    .append(",vision_status=").append(image.status())
                    .append(",source_reference_present=").append(!image.sourceUrl().isBlank()
                            || !"NONE".equals(image.inlinePayloadStatus()))
                    .append(",inline_payload=").append(image.inlinePayloadStatus())
                    .append(",inline_bytes=").append(image.inlinePayloadBytes())
                    .append(",source_sha256=").append(image.sha256().isBlank() ? "未记录" : image.sha256())
                    .append(",parser=").append(orUnknown(image.parserName()))
                    .append("/").append(orUnknown(image.parserVersion()))
                    .append(",completed_at=").append(image.completedAt() == null ? "未知" : image.completedAt());
            if (image.errorCode() != null && !image.errorCode().isBlank()) {
                out.append(",error_code=").append(image.errorCode());
            }
            if (!image.description().isBlank()) {
                out.append(",image_content=").append(evidenceText(image.description(), 1_000));
            } else {
                out.append(",image_content=无可验证识图结果");
            }
            out.append(']');
        }
    }

    private static String evidenceText(String value, int limit) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…[截断]";
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private static String formatTranscript(List<ContextLine> lines) {
        StringBuilder out = new StringBuilder();
        for (ContextLine line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append('[').append(MESSAGE_TIME.format(line.createdAt())).append("] id=").append(line.id())
                    .append(' ').append(line.nickname()).append("(QQ=").append(line.userId()).append(')');
            if (line.replyToLocalId() != null) out.append(" 回复local_id=").append(line.replyToLocalId());
            if (!line.atUserIds().isEmpty()) out.append(" @").append(line.atUserIds());
            out.append("：").append(line.content());
            for (ReplayImage image : line.images()) {
                out.append(" [图片证据 status=").append(image.status());
                if (!image.description().isBlank()) out.append("，视觉说明：").append(evidenceText(image.description(), 1_000));
                else out.append("，无可验证视觉说明");
                if (image.artifactId() != null) out.append("，artifact_id=").append(image.artifactId());
                out.append(']');
            }
        }
        return out.toString();
    }

    private ReplayMessage nextHumanMessage(ReplaySession session) {
        ReplayMessage next = nextHumanMessageOrNull(session);
        if (next != null) return next;
        throw new ReplayException(409, "no_more_human_messages");
    }

    private ReplayMessage nextHumanMessageOrNull(ReplaySession session) {
        return session.range.stream()
                .filter(message -> message.localId() > session.lastProcessedLocalId && !message.bot())
                .findFirst().orElse(null);
    }

    private ReplaySession requireSession(String replayId) {
        ReplaySession session = replayId == null ? null : sessions.get(replayId);
        if (session == null) throw new ReplayException(404, "replay_session_not_found_or_expired");
        if (session.expiresAtMs < System.currentTimeMillis()) {
            sessions.remove(replayId, session);
            throw new ReplayException(404, "replay_session_not_found_or_expired");
        }
        return session;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtMs < now);
    }

    private Snapshot loadSnapshot(String groupId, long fromId, long toId) {
        Instant snapshotAt = Instant.now();
        String columns = "id,user_id,group_id,content,raw_segments_json,raw_event_json,image_data,"
                + "is_robot_reply,sender_type,provider_message_id,reply_to_id,reply_provider_message_id,"
                + "availability_status,created_at";
        String common = " FROM messages WHERE group_id=? AND is_private=FALSE AND retracted_at IS NULL " +
                "AND redacted_at IS NULL ";
        List<ReplayMessage> before = new ArrayList<>();
        List<ReplayMessage> range = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + columns + common + "AND id<? ORDER BY id DESC LIMIT ?")) {
                    statement.setString(1, groupId);
                    statement.setLong(2, fromId);
                    statement.setInt(3, MAX_CONTEXT_ROWS);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) before.add(mapMessage(rs));
                    }
                }
                Collections.reverse(before);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + columns + common + "AND id>=? AND id<=? ORDER BY id ASC LIMIT ?")) {
                    statement.setString(1, groupId);
                    statement.setLong(2, fromId);
                    statement.setLong(3, toId);
                    statement.setInt(4, MAX_RANGE_ROWS);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) range.add(mapMessage(rs));
                    }
                }
                List<ReplayMessage> seed = new ArrayList<>(before);
                seed.addAll(range);
                List<ReplayMessage> all = new ArrayList<>(seed);
                all.addAll(loadReplyTargets(connection, groupId, seed, columns, common));
                List<ReplayMessage> resolved = resolveReplyLinks(all);
                List<ReplayMessage> withImages = attachImageEvidence(connection, resolved);
                Set<Long> inlineCandidates = new java.util.LinkedHashSet<>();
                for (ReplayMessage message : range) {
                    if (message.bot()) continue;
                    inlineCandidates.add(message.localId());
                    if (message.replyToLocalId() != null) inlineCandidates.add(message.replyToLocalId());
                }
                List<ReplayMessage> withInlineImages = attachInlineImagePayloads(connection, groupId,
                        withImages, inlineCandidates);
                Map<Long, ReplayMessage> byId = withInlineImages.stream().collect(Collectors.toMap(
                        ReplayMessage::localId, value -> value, (a, b) -> a));
                before.replaceAll(message -> byId.get(message.localId()));
                range.replaceAll(message -> byId.get(message.localId()));
                Set<Long> baseIds = seed.stream().map(ReplayMessage::localId).collect(Collectors.toSet());
                Set<Long> targetIds = new java.util.LinkedHashSet<>();
                for (ReplayMessage message : seed) {
                    if (message.replyToLocalId() != null && !baseIds.contains(message.replyToLocalId())) {
                        targetIds.add(message.replyToLocalId());
                    }
                }
                List<ReplayMessage> replyTargets = targetIds.stream().map(byId::get)
                        .filter(Objects::nonNull).toList();
                connection.rollback();
                return new Snapshot(List.copyOf(before), List.copyOf(range), replyTargets, snapshotAt);
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            }
        } catch (ReplayException e) {
            throw e;
        } catch (Exception e) {
            throw new ReplayException(503, "database_snapshot_read_failed");
        }
    }

    private static ReplayMessage mapMessage(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String userId = rs.getString("user_id");
        String content = rs.getString("content");
        String rawSegments = rs.getString("raw_segments_json");
        String rawEvent = rs.getString("raw_event_json");
        String imageData = rs.getString("image_data");
        boolean bot = rs.getBoolean("is_robot_reply") || "BOT".equalsIgnoreCase(rs.getString("sender_type"));
        String replyToRawId = rs.getString("reply_to_id");
        String replyProviderMessageId = trimToNull(rs.getString("reply_provider_message_id"));
        Timestamp created = rs.getTimestamp("created_at");
        Instant createdAt = created == null ? Instant.EPOCH : created.toInstant();
        JsonNode segments = parseJson(rawSegments);
        List<Long> atUserIds = MessageUtil.extractAts(segments);
        String nickname = senderNickname(rawEvent, userId);
        String safeContent = safeReplayContent(content);
        boolean contentTruncated = safeContent.length() > MAX_MESSAGE_CHARS;
        if (safeContent.length() > MAX_MESSAGE_CHARS) {
            safeContent = safeContent.substring(0, MAX_MESSAGE_CHARS) + "…（消息已截断）";
        }
        List<ReplayImage> legacyImages = legacyImageEvidence(id, content, segments, imageData);
        replyToRawId = trimToNull(replyToRawId);
        String reference = replyProviderMessageId != null ? replyProviderMessageId : replyToRawId;
        String referenceSource = replyProviderMessageId != null ? "REPLY_PROVIDER_MESSAGE_ID"
                : replyToRawId != null ? "REPLY_TO_ID_LEGACY_AMBIGUOUS" : "NONE";
        return new ReplayMessage(id, userId == null ? "" : userId, nickname, safeContent, bot,
                trimToNull(rs.getString("provider_message_id")), replyProviderMessageId,
                replyToRawId, reference, referenceSource, null,
                reference == null ? "NONE" : "UNRESOLVED",
                createdAt, atUserIds, contentTruncated, legacyImages);
    }

    private static List<ReplayMessage> loadReplyTargets(Connection connection, String groupId,
                                                        List<ReplayMessage> seed, String columns,
                                                        String common) throws SQLException {
        Set<String> providerIds = new java.util.LinkedHashSet<>();
        Set<Long> localIds = new java.util.LinkedHashSet<>();
        Set<String> knownProviderIds = seed.stream().map(ReplayMessage::providerMessageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (ReplayMessage message : seed) {
            String reference = message.replyReference();
            if (reference == null || knownProviderIds.contains(reference)) continue;
            providerIds.add(reference);
            if (message.replyProviderMessageId() == null) {
                try { localIds.add(Long.parseLong(reference)); }
                catch (NumberFormatException ignored) {}
            }
        }
        List<ReplayMessage> targets = queryMessages(connection, groupId, columns, common,
                "provider_message_id", new ArrayList<>(providerIds));
        Set<String> resolvedProviderIds = targets.stream().map(ReplayMessage::providerMessageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        localIds.removeIf(id -> resolvedProviderIds.contains(String.valueOf(id)));
        targets.addAll(queryMessages(connection, groupId, columns, common, "id", new ArrayList<>(localIds)));
        return targets.stream().collect(Collectors.toMap(ReplayMessage::localId, value -> value,
                (a, b) -> a, java.util.LinkedHashMap::new)).values().stream().toList();
    }

    private static List<ReplayMessage> queryMessages(Connection connection, String groupId, String columns,
                                                      String common, String keyColumn, List<?> values)
            throws SQLException {
        if (values.isEmpty()) return new ArrayList<>();
        String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
        List<ReplayMessage> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + columns + common
                + "AND " + keyColumn + " IN (" + placeholders + ")")) {
            statement.setString(1, groupId);
            int parameter = 2;
            for (Object value : values) statement.setObject(parameter++, value);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) messages.add(mapMessage(rs));
            }
        }
        return messages;
    }

    private static List<ReplayMessage> resolveReplyLinks(List<ReplayMessage> messages) {
        Map<String, List<ReplayMessage>> byProviderId = new java.util.HashMap<>();
        Map<Long, ReplayMessage> byLocalId = new java.util.HashMap<>();
        for (ReplayMessage message : messages) {
            byLocalId.put(message.localId(), message);
            if (message.providerMessageId() != null) {
                byProviderId.computeIfAbsent(message.providerMessageId(), ignored -> new ArrayList<>()).add(message);
            }
        }
        List<ReplayMessage> result = new ArrayList<>(messages.size());
        for (ReplayMessage message : messages) {
            String reference = message.replyReference();
            if (reference == null) {
                result.add(message.withReply(null, "NONE"));
                continue;
            }
            List<ReplayMessage> providerMatches = byProviderId.getOrDefault(reference, List.of());
            ReplayMessage providerTarget = providerMatches.stream()
                    .filter(target -> target.localId() < message.localId())
                    .max(java.util.Comparator.comparingLong(ReplayMessage::localId)).orElse(null);
            if (providerTarget != null) {
                result.add(message.withReply(providerTarget.localId(), "RESOLVED_PROVIDER_ID"));
            } else if (!providerMatches.isEmpty()) {
                result.add(message.withReply(null, "NON_CAUSAL_PROVIDER_TARGET"));
            } else if (message.replyProviderMessageId() == null) {
                Long localReference = parseLongOrNull(reference);
                ReplayMessage localTarget = localReference == null ? null : byLocalId.get(localReference);
                if (localTarget != null && localTarget.localId() < message.localId()) {
                    result.add(message.withReply(localTarget.localId(), "RESOLVED_LEGACY_LOCAL_ID"));
                } else {
                    result.add(message.withReply(null, "PROVIDER_TARGET_NOT_FOUND"));
                }
            } else {
                result.add(message.withReply(null, "PROVIDER_TARGET_NOT_FOUND"));
            }
        }
        return result;
    }

    private List<ReplayMessage> attachImageEvidence(Connection connection, List<ReplayMessage> messages)
            throws SQLException {
        Map<Long, ReplayMessage> byId = messages.stream().collect(Collectors.toMap(
                ReplayMessage::localId, value -> value, (a, b) -> a));
        Map<Long, List<ReplayImage>> imagesByMessage = new java.util.HashMap<>();
        List<Long> ids = new ArrayList<>(byId.keySet());
        if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            String sql = "SELECT p.id AS part_id,p.message_id,p.part_index,p.source_url,p.provider_file_id,"
                    + "p.storage_key,p.mime_type,p.sha256,"
                    + "p.availability_status AS part_status,p.error_code AS part_error_code,"
                    + "a.id AS artifact_id,a.status AS artifact_status,a.content_json,a.error_code AS artifact_error_code,"
                    + "a.input_sha256,a.parser_name,a.parser_version,a.completed_at "
                    + "FROM message_parts p LEFT JOIN media_artifacts a ON a.part_id=p.id "
                    + "AND a.artifact_type='IMAGE_DESCRIPTION' AND a.id=(SELECT MAX(a2.id) FROM media_artifacts a2 "
                    + "WHERE a2.part_id=p.id AND a2.artifact_type='IMAGE_DESCRIPTION') "
                    + "WHERE p.part_type='image' AND p.message_id IN (" + placeholders + ") "
                    + "ORDER BY p.message_id,p.part_index";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
                try (ResultSet rs = statement.executeQuery()) {
                    Map<Long, Integer> ordinals = new java.util.HashMap<>();
                    while (rs.next()) {
                        long messageId = rs.getLong("message_id");
                        ReplayMessage message = byId.get(messageId);
                        if (message == null) continue;
                        int ordinal = ordinals.merge(messageId, 1, Integer::sum) - 1;
                        ReplayImage legacy = legacyImageAt(message.images(), ordinal);
                        long artifactValue = rs.getLong("artifact_id");
                        Long artifactId = rs.wasNull() ? null : artifactValue;
                        String artifactStatus = trimToNull(rs.getString("artifact_status"));
                        String description = "SUCCEEDED".equalsIgnoreCase(artifactStatus)
                                ? artifactDescription(rs.getString("content_json")) : "";
                        if (description.isBlank() && legacy != null) description = legacy.description();
                        String status = artifactStatus != null ? artifactStatus
                                : description.isBlank() ? rs.getString("part_status") : "LEGACY_DESCRIPTION";
                        String sourceUrl = trimToNull(rs.getString("source_url"));
                        if (sourceUrl == null && legacy != null) sourceUrl = trimToNull(legacy.sourceUrl());
                        if (isInlineImageSource(sourceUrl)) sourceUrl = null;
                        String errorCode = trimToNull(rs.getString("artifact_error_code"));
                        if (errorCode == null) errorCode = trimToNull(rs.getString("part_error_code"));
                        String sha256 = trimToNull(rs.getString("input_sha256"));
                        if (sha256 == null) sha256 = trimToNull(rs.getString("sha256"));
                        Timestamp completed = rs.getTimestamp("completed_at");
                        long partValue = rs.getLong("part_id");
                        long partIndex = rs.getLong("part_index");
                        imagesByMessage.computeIfAbsent(messageId, ignored -> new ArrayList<>()).add(
                                new ReplayImage(messageId, partValue, (int) partIndex, artifactId,
                                        status == null ? "UNKNOWN" : status, safeImageDescription(description),
                                        errorCode, trimToNull(rs.getString("parser_name")),
                                        trimToNull(rs.getString("parser_version")),
                                        completed == null ? null : completed.toInstant(), sha256,
                                        sourceUrl == null ? "" : sourceUrl, rs.getString("mime_type"),
                                        trimToNull(rs.getString("provider_file_id")),
                                        trimToNull(rs.getString("storage_key")), "", "NONE", 0));
                    }
                }
            }
        }
        List<ReplayMessage> result = new ArrayList<>(messages.size());
        for (ReplayMessage message : messages) {
            List<ReplayImage> canonical = imagesByMessage.get(message.localId());
            if (canonical == null || canonical.isEmpty()) {
                result.add(message.withImages(message.images()));
                continue;
            }
            int imageCount = canonical.size();
            List<ReplayImage> merged = new ArrayList<>(canonical);
            for (ReplayImage legacy : message.images()) {
                if (legacy.partId() == null && legacy.partIndex() >= imageCount) merged.add(legacy);
            }
            result.add(message.withImages(List.copyOf(merged)));
        }
        return List.copyOf(result);
    }

    private List<ReplayMessage> attachInlineImagePayloads(Connection connection, String groupId,
                                                          List<ReplayMessage> messages, Set<Long> candidateIds)
            throws SQLException {
        if (candidateIds.isEmpty()) return messages;
        String placeholders = String.join(",", Collections.nCopies(candidateIds.size(), "?"));
        Map<Long, List<InlineImagePayload>> payloadsByMessage = new java.util.HashMap<>();
        int remainingBytes = MAX_REPLAY_INLINE_SNAPSHOT_BYTES;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,content,raw_segments_json,image_data FROM messages WHERE group_id=? "
                        + "AND is_private=FALSE AND retracted_at IS NULL AND redacted_at IS NULL "
                        + "AND id IN (" + placeholders + ")")) {
            statement.setString(1, groupId);
            int parameter = 2;
            for (Long id : candidateIds) statement.setLong(parameter++, id);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    InlineImageBatch batch = parseInlineImagePayloads(rs.getString("content"),
                            parseJson(rs.getString("raw_segments_json")), rs.getString("image_data"), remainingBytes);
                    remainingBytes -= batch.bytesUsed();
                    payloadsByMessage.put(rs.getLong("id"), batch.images());
                }
            }
        }

        List<ReplayMessage> result = new ArrayList<>(messages.size());
        for (ReplayMessage message : messages) {
            List<InlineImagePayload> payloads = payloadsByMessage.get(message.localId());
            if (payloads == null || payloads.isEmpty() || message.images().isEmpty()) {
                result.add(message);
                continue;
            }
            List<ReplayImage> images = new ArrayList<>(message.images().size());
            for (int i = 0; i < message.images().size(); i++) {
                ReplayImage image = message.images().get(i);
                InlineImagePayload payload = i < payloads.size() ? payloads.get(i) : InlineImagePayload.none();
                images.add("NONE".equals(payload.status()) ? image : image.withInlinePayload(payload));
            }
            result.add(message.withImages(List.copyOf(images)));
        }
        return List.copyOf(result);
    }

    private static InlineImageBatch parseInlineImagePayloads(String content, JsonNode segments, String imageData,
                                                              int remainingBytes) {
        List<String> segmentSources = imageSources(segments);
        List<LegacyImage> contentImages = legacyContentImages(content);
        List<LegacyImage> storedImages = parseLegacyImageData(imageData);
        int count = Math.max(segmentSources.size(), Math.max(contentImages.size(), storedImages.size()));
        List<InlineImagePayload> images = new ArrayList<>(count);
        int bytesUsed = 0;
        for (int i = 0; i < count; i++) {
            String inlineSource = firstInlineSource(
                    at(segmentSources, i),
                    i < contentImages.size() ? contentImages.get(i).source() : "",
                    i < storedImages.size() ? storedImages.get(i).source() : "");
            InlineImagePayload payload = inlineSource == null
                    ? InlineImagePayload.none()
                    : decodeInlineImage(inlineSource, Math.max(0, remainingBytes - bytesUsed));
            images.add(payload);
            if ("AVAILABLE".equals(payload.status())) bytesUsed += payload.byteSize();
        }
        return new InlineImageBatch(List.copyOf(images), bytesUsed);
    }

    private static List<String> imageSources(JsonNode segments) {
        if (segments == null || !segments.isArray()) return List.of();
        List<String> sources = new ArrayList<>();
        for (JsonNode segment : segments) {
            if (!"image".equals(segment.path("type").asText())) continue;
            JsonNode data = segment.path("data");
            String source = data.path("url").asText("").trim();
            if (source.isBlank()) source = data.path("file").asText("").trim();
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "";
    }

    private static String firstInlineSource(String... sources) {
        for (String source : sources) if (isInlineImageSource(source)) return source;
        return null;
    }

    private static boolean isInlineImageSource(String source) {
        return source != null && source.regionMatches(true, 0, "base64://", 0, 9);
    }

    private static InlineImagePayload decodeInlineImage(String source, int remainingBytes) {
        String encoded = source.substring(9).trim();
        if (encoded.isEmpty()) return InlineImagePayload.rejected("INVALID_BASE64", 0);
        long estimatedBytes = Math.max(0, (encoded.length() * 3L) / 4
                - (encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0));
        if (estimatedBytes > MAX_REPLAY_INLINE_IMAGE_BYTES
                || encoded.length() > ((MAX_REPLAY_INLINE_IMAGE_BYTES + 2L) / 3L) * 4L + 4L) {
            return InlineImagePayload.rejected("IMAGE_TOO_LARGE", (int) Math.min(Integer.MAX_VALUE, estimatedBytes));
        }
        if (estimatedBytes > remainingBytes) {
            return InlineImagePayload.rejected("SNAPSHOT_IMAGE_BUDGET_EXCEEDED",
                    (int) Math.min(Integer.MAX_VALUE, estimatedBytes));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException e) {
            return InlineImagePayload.rejected("INVALID_BASE64", 0);
        }
        if (bytes.length == 0) return InlineImagePayload.rejected("EMPTY_IMAGE", 0);
        if (bytes.length > MAX_REPLAY_INLINE_IMAGE_BYTES) {
            return InlineImagePayload.rejected("IMAGE_TOO_LARGE", bytes.length);
        }
        String mimeType = supportedImageMimeType(bytes);
        if (mimeType == null) return InlineImagePayload.rejected("UNSUPPORTED_IMAGE_FORMAT", bytes.length);
        String hash = java.util.HexFormat.of().formatHex(sha256(bytes));
        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return new InlineImagePayload(dataUri, hash, bytes.length, "AVAILABLE");
    }

    private static String supportedImageMimeType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private List<ReplayMessage> awaitPendingVision(List<ReplayMessage> messages) {
        List<ReplayMessage> current = List.copyOf(messages);
        if (!hasPendingVision(current)) return current;
        long deadline = System.currentTimeMillis() + VISION_WAIT_MS;
        while (System.currentTimeMillis() < deadline && hasPendingVision(current)) {
            try {
                Thread.sleep(Math.min(200L, Math.max(1L, deadline - System.currentTimeMillis())));
                try (Connection connection = dataSource.getConnection()) {
                    connection.setReadOnly(true);
                    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    connection.setAutoCommit(false);
                    try {
                        List<ReplayMessage> refreshed = attachImageEvidence(connection, current);
                        current = preserveInlinePayloads(current, refreshed);
                        connection.rollback();
                    } catch (Exception e) {
                        try { connection.rollback(); } catch (SQLException ignored) {}
                        throw e;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReplayException(503, "vision_wait_interrupted");
            } catch (Exception e) {
                throw new ReplayException(503, "vision_evidence_refresh_failed");
            }
        }
        return current;
    }

    private static List<ReplayMessage> preserveInlinePayloads(List<ReplayMessage> original,
                                                               List<ReplayMessage> refreshed) {
        Map<Long, ReplayMessage> originalById = original.stream().collect(Collectors.toMap(
                ReplayMessage::localId, message -> message));
        List<ReplayMessage> result = new ArrayList<>(refreshed.size());
        for (ReplayMessage message : refreshed) {
            ReplayMessage prior = originalById.get(message.localId());
            if (prior == null) {
                result.add(message);
                continue;
            }
            List<ReplayImage> images = new ArrayList<>(message.images().size());
            for (int i = 0; i < message.images().size(); i++) {
                ReplayImage image = message.images().get(i);
                if (i < prior.images().size() && !"NONE".equals(prior.images().get(i).inlinePayloadStatus())) {
                    ReplayImage old = prior.images().get(i);
                    image = image.withInlinePayload(new InlineImagePayload(old.inlineDataUri(), old.sha256(),
                            old.inlinePayloadBytes(), old.inlinePayloadStatus()));
                }
                images.add(image);
            }
            result.add(message.withImages(List.copyOf(images)));
        }
        return List.copyOf(result);
    }

    private static boolean hasPendingVision(List<ReplayMessage> messages) {
        return messages.stream().flatMap(message -> message.images().stream()).anyMatch(image ->
                "PENDING".equalsIgnoreCase(image.status()) || "PROCESSING".equalsIgnoreCase(image.status())
                        || "RETRY".equalsIgnoreCase(image.status()));
    }

    private static ReplayImage legacyImageAt(List<ReplayImage> images, int ordinal) {
        List<ReplayImage> legacy = images.stream().filter(image -> image.partId() == null).toList();
        return ordinal >= 0 && ordinal < legacy.size() ? legacy.get(ordinal) : null;
    }

    private static List<ReplayImage> legacyImageEvidence(long messageId, String rawContent,
                                                         JsonNode segments, String rawImageData) {
        List<String> segmentSources = imageSources(segments);
        List<LegacyImage> contentImages = legacyContentImages(rawContent);
        List<LegacyImage> legacy = parseLegacyImageData(rawImageData);
        int count = Math.max(segmentSources.size(), Math.max(contentImages.size(), legacy.size()));
        List<ReplayImage> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String segmentSource = at(segmentSources, i);
            LegacyImage content = i < contentImages.size() ? contentImages.get(i) : new LegacyImage("", "");
            LegacyImage saved = i < legacy.size() ? legacy.get(i) : new LegacyImage("", "");
            String source = !segmentSource.isBlank() ? segmentSource
                    : !content.source().isBlank() ? content.source() : saved.source();
            if (isInlineImageSource(source)) source = "";
            String description = saved.description();
            String status = !description.isBlank() ? "LEGACY_DESCRIPTION"
                    : !source.isBlank() ? "SOURCE_AVAILABLE" : "UNAVAILABLE_REFERENCE";
            images.add(new ReplayImage(messageId, null, i, null, status, safeImageDescription(description),
                    "", "", "", null, "", source, "", "", "", "", "NONE", 0));
        }
        return List.copyOf(images);
    }

    private static List<LegacyImage> legacyContentImages(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return List.of();
        List<LegacyImage> images = new ArrayList<>();
        Matcher matcher = CQ_IMAGE.matcher(rawContent);
        while (matcher.find()) {
            String attributes = matcher.group(1) == null ? "" : matcher.group(1);
            String source = cqAttribute(attributes, "url");
            if (source.isBlank()) source = cqAttribute(attributes, "file");
            images.add(new LegacyImage(source, ""));
        }
        return List.copyOf(images);
    }

    private static String cqAttribute(String attributes, String key) {
        for (String attribute : attributes.split(",")) {
            int separator = attribute.indexOf('=');
            if (separator > 0 && key.equalsIgnoreCase(attribute.substring(0, separator).trim())) {
                return attribute.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static List<LegacyImage> parseLegacyImageData(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode root = parseJson(raw);
        List<LegacyImage> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) result.add(legacyImage(node));
        } else if (root.isObject()) {
            result.add(legacyImage(root));
        } else if (raw.contains("|")) {
            String[] values = raw.split("\\|", 2);
            result.add(new LegacyImage(values[0], values[1]));
        }
        return List.copyOf(result);
    }

    private static LegacyImage legacyImage(JsonNode node) {
        String source = node.path("url").asText("").trim();
        if (source.isBlank()) source = node.path("file").asText("").trim();
        List<String> descriptions = new ArrayList<>();
        addDescription(descriptions, node);
        return new LegacyImage(source, String.join("；", descriptions));
    }

    private static String artifactDescription(String raw) {
        JsonNode node = parseJson(raw);
        List<String> descriptions = new ArrayList<>();
        addDescription(descriptions, node);
        return String.join("；", descriptions);
    }

    private static String safeImageDescription(String description) {
        if (description == null) return "";
        String value = description.trim();
        return value.length() <= 1_800 ? value : value.substring(0, 1_800) + "…[识图说明已截断]";
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static boolean mentions(List<Long> atUserIds, String userId) {
        if (atUserIds == null || userId == null || userId.isBlank()) return false;
        try { return atUserIds.contains(Long.parseLong(userId)); }
        catch (NumberFormatException ignored) { return false; }
    }

    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) return JSON.nullNode();
        try { return JSON.readTree(raw); }
        catch (Exception ignored) { return JSON.nullNode(); }
    }

    private static String senderNickname(String rawEvent, String userId) {
        JsonNode event = parseJson(rawEvent);
        String card = event.path("sender").path("card").asText("").trim();
        if (!card.isBlank()) return card;
        String nickname = event.path("sender").path("nickname").asText("").trim();
        return nickname.isBlank() ? "QQ" + (userId == null ? "未知" : userId) : nickname;
    }

    private static void addDescription(List<String> out, JsonNode node) {
        for (String key : List.of("desc", "description", "vision_description")) {
            String text = node.path(key).asText("").trim();
            if (!text.isBlank() && !out.contains(text)) out.add(text);
        }
    }

    private static String normalizeGroupId(String groupId) {
        if (groupId == null || !groupId.matches("[0-9]{1,20}")) {
            throw new ReplayException(400, "invalid_group_id");
        }
        return groupId;
    }

    private static String safeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return "redacted";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "redacted";
        }
    }

    public record ReplayProfile(String modelId, MainModelPool.Candidate candidate, RuleSet ruleSet,
                                String promptOverride, String promptPatch) {}

    public record Turn(String groupId, long localMessageId, String userId, String nickname, String prompt,
                       List<Long> atUserIds, boolean allowSilence, boolean isAtBot,
                       boolean addressedToBotByName, String transcript, String turnEvidence,
                       Instant timestamp, List<String> imageDataUris, List<String> contextMessageIds,
                       String replyProviderMessageId, String replyToRawId, String replyReference,
                       String replyReferenceSource, Long replyToLocalId, String replyResolutionStatus,
                       List<ImageEvidenceView> imageEvidence, List<String> omittedContextMessageIds,
                       List<com.start.runtime.conversation.ConversationSession.SearchImage> searchImages,
                       String shortTermGroupScene) {
        public Turn {
            atUserIds = atUserIds == null ? List.of() : List.copyOf(atUserIds);
            imageDataUris = imageDataUris == null ? List.of() : List.copyOf(imageDataUris);
            contextMessageIds = contextMessageIds == null ? List.of() : List.copyOf(contextMessageIds);
            imageEvidence = imageEvidence == null ? List.of() : List.copyOf(imageEvidence);
            omittedContextMessageIds = omittedContextMessageIds == null ? List.of() : List.copyOf(omittedContextMessageIds);
            searchImages = searchImages == null ? List.of() : List.copyOf(searchImages);
            shortTermGroupScene = shortTermGroupScene == null ? "" : shortTermGroupScene;
        }

        public String modelUserText() {
            return "【本轮消息 local_id=" + localMessageId + "，发送者=" + nickname
                    + "（QQ=" + userId + "）】\n" + prompt;
        }
    }

    @FunctionalInterface
    public interface ReplayGenerator {
        ReplayGeneration generate(Turn turn, ReplayProfile profile) throws Exception;
    }

    public record ReplayGeneration(String reply, String systemPrompt, String modelUserText,
                                   String protocol, int attachedImageCount, String requestBodySha256,
                                   GroupTurnAssessment groupAssessment, boolean replyToCurrentMessage) {
        public ReplayGeneration(String reply, String systemPrompt, String modelUserText,
                                String protocol, int attachedImageCount, String requestBodySha256) {
            this(reply, systemPrompt, modelUserText, protocol, attachedImageCount, requestBodySha256, null, false);
        }

        public ReplayGeneration(String reply, String systemPrompt, String modelUserText,
                                String protocol, int attachedImageCount, String requestBodySha256,
                                GroupTurnAssessment groupAssessment) {
            this(reply, systemPrompt, modelUserText, protocol, attachedImageCount, requestBodySha256,
                    groupAssessment, false);
        }
    }

    public record ImageEvidenceView(long messageLocalId, Long partId, int partIndex, Long artifactId,
                                    String status, String description, String errorCode, String parserName,
                                    String parserVersion, Instant completedAt, String sha256,
                                    boolean sourceReferencePresent, String sourceScheme, String sourceHost,
                                    int sourceLength, List<String> sourceQueryParameterNames,
                                    boolean providerFileIdPresent, int providerFileIdLength,
                                    boolean storageKeyPresent, int storageKeyLength,
                                    String sourceLoadStatus, String sourceLoadErrorCode,
                                    boolean descriptionInPrompt, boolean attachedAsImage,
                                    String inlinePayloadStatus, int inlinePayloadBytes) {}

    public record MessageView(long localMessageId, String userId, String nickname, String content,
                              boolean bot, Long replyToLocalMessageId, String replyProviderMessageId,
                              String replyToRawId, String replyReference, String replyReferenceSource,
                              String replyResolutionStatus, Instant createdAt, List<Long> atUserIds,
                              boolean hasImage, String imageDescription, List<ImageEvidenceView> images,
                              boolean contentTruncated) {}

    public enum ReplayMode {
        HISTORICAL,
        COUNTERFACTUAL;

        public static ReplayMode parse(String value) {
            if (value == null || value.isBlank()) return HISTORICAL;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ReplayException(400, "invalid_replay_mode");
            }
        }
    }

    public record StartResult(String replayId, String groupId, String mode, long fromLocalMessageId,
                              long toLocalMessageId, String modelId, String candidateId, String endpoint,
                              String protocol, String apiKeyEnv, List<MessageView> messages,
                              long nextLocalMessageId, Instant snapshotAt) {}

    public record AdvanceResult(String mode, long localMessageId, Long nextLocalMessageId, Instant snapshotAt) {}

    public record InjectionResult(String mode, long localMessageId, String decision, String reply, String modelId,
                                  String candidateId, String endpoint, String apiKeyEnv, long latencyMs,
                                  List<String> contextMessageIds, String transcript,
                                  String modelUserText, String turnEvidence,
                                  String replyProviderMessageId, String replyToRawId, String replyReference,
                                  String replyReferenceSource, Long replyToLocalMessageId,
                                  String replyResolutionStatus, List<ImageEvidenceView> imageEvidence,
                                  List<String> omittedContextMessageIds, String systemPrompt,
                                  String protocol, int attachedImageCount, String requestBodySha256,
                                  Instant snapshotAt, Long nextLocalMessageId, String triggerEvent,
                                  boolean proactiveCandidate, boolean modelCallMade,
                                  GroupTurnAssessment groupAssessment, boolean replyToCurrentMessage) {}

    public static final class ReplayException extends RuntimeException {
        private final int status;
        private final String code;
        private final Map<String, Object> details;

        ReplayException(int status, String code) {
            this(status, code, Map.of());
        }

        ReplayException(int status, String code, Map<String, Object> details) {
            super(code);
            this.status = status;
            this.code = code;
            this.details = details == null ? Map.of() : Map.copyOf(details);
        }

        public int status() { return status; }
        public String code() { return code; }
        public Map<String, Object> details() { return details; }
    }

    private record Snapshot(List<ReplayMessage> before, List<ReplayMessage> range,
                            List<ReplayMessage> replyTargets, Instant loadedAt) {}

    private record LegacyImage(String source, String description) {}

    private record InlineImageBatch(List<InlineImagePayload> images, int bytesUsed) {}

    private record InlineImagePayload(String dataUri, String sha256, int byteSize, String status) {
        static InlineImagePayload none() { return new InlineImagePayload("", "", 0, "NONE"); }
        static InlineImagePayload rejected(String status, int byteSize) {
            return new InlineImagePayload("", "", byteSize, status);
        }
    }

    private record ReplayImage(long messageLocalId, Long partId, int partIndex, Long artifactId,
                               String status, String description, String errorCode, String parserName,
                               String parserVersion, Instant completedAt, String sha256,
                               String sourceUrl, String mimeType, String providerFileId, String storageKey,
                               String inlineDataUri, String inlinePayloadStatus, int inlinePayloadBytes) {
        ImageEvidenceView view(boolean descriptionInPrompt, boolean attachedAsImage) {
            return view(descriptionInPrompt, attachedAsImage, "NOT_ATTEMPTED", "");
        }

        ImageEvidenceView view(boolean descriptionInPrompt, boolean attachedAsImage,
                               String sourceLoadStatus, String sourceLoadErrorCode) {
            boolean inlineSource = !"NONE".equals(inlinePayloadStatus);
            return new ImageEvidenceView(messageLocalId, partId, partIndex, artifactId, status,
                    description, errorCode, parserName, parserVersion, completedAt, sha256,
                    !sourceUrl.isBlank() || inlineSource,
                    inlineSource ? "cq-inline-base64" : safeImageSourceScheme(sourceUrl),
                    inlineSource ? "" : safeImageSourceHost(sourceUrl),
                    sourceUrl.length(), safeImageQueryParameterNames(sourceUrl),
                    providerFileId != null && !providerFileId.isBlank(),
                    providerFileId == null ? 0 : providerFileId.length(),
                    storageKey != null && !storageKey.isBlank(), storageKey == null ? 0 : storageKey.length(),
                    sourceLoadStatus, sourceLoadErrorCode,
                    descriptionInPrompt, attachedAsImage, inlinePayloadStatus, inlinePayloadBytes);
        }

        ReplayImage withInlinePayload(InlineImagePayload payload) {
            String inputHash = "AVAILABLE".equals(payload.status()) ? payload.sha256() : sha256;
            return new ReplayImage(messageLocalId, partId, partIndex, artifactId, status, description,
                    errorCode, parserName, parserVersion, completedAt, inputHash, sourceUrl, mimeType,
                    providerFileId, storageKey, payload.dataUri(), payload.status(), payload.byteSize());
        }
    }

    private record ReplayMessage(long localId, String userId, String nickname, String content,
                                 boolean bot, String providerMessageId, String replyProviderMessageId,
                                 String replyToRawId, String replyReference, String replyReferenceSource,
                                 Long replyToLocalId,
                                 String replyResolutionStatus, Instant createdAt, List<Long> atUserIds,
                                 boolean contentTruncated, List<ReplayImage> images) {
        ReplayMessage withReply(Long targetId, String status) {
            return new ReplayMessage(localId, userId, nickname, content, bot, providerMessageId,
                    replyProviderMessageId, replyToRawId, replyReference, replyReferenceSource, targetId, status,
                    createdAt, atUserIds, contentTruncated, images);
        }

        ReplayMessage withImages(List<ReplayImage> value) {
            return new ReplayMessage(localId, userId, nickname, content, bot, providerMessageId,
                    replyProviderMessageId, replyToRawId, replyReference, replyReferenceSource, replyToLocalId,
                    replyResolutionStatus, createdAt, atUserIds, contentTruncated, value);
        }

        MessageView view() {
            String descriptions = images.stream().map(ReplayImage::description).filter(value -> !value.isBlank())
                    .distinct().collect(Collectors.joining("；"));
            return new MessageView(localId, userId, nickname, content, bot, replyToLocalId,
                    replyProviderMessageId, replyToRawId, replyReference, replyReferenceSource,
                    replyResolutionStatus, createdAt, atUserIds, !images.isEmpty(),
                    descriptions, images.stream().map(image -> image.view(!image.description().isBlank(), false)).toList(),
                    contentTruncated);
        }
    }

    private record ContextLine(String id, long localId, String userId, String nickname, String content,
                               Instant createdAt, String providerMessageId, String replyProviderMessageId,
                               String replyToRawId, String replyReference, String replyReferenceSource,
                               Long replyToLocalId, String replyResolutionStatus, List<Long> atUserIds,
                               boolean bot, boolean contentTruncated, List<ReplayImage> images,
                               boolean synthetic, boolean pinned) {
        static ContextLine from(ReplayMessage message, boolean pinned) {
            return new ContextLine(String.valueOf(message.localId()), message.localId(), message.userId(),
                    message.nickname(), message.content(), message.createdAt(), message.providerMessageId(),
                    message.replyProviderMessageId(), message.replyToRawId(), message.replyReference(),
                    message.replyReferenceSource(), message.replyToLocalId(), message.replyResolutionStatus(),
                    message.atUserIds(), message.bot(), message.contentTruncated(), message.images(), false, pinned);
        }
    }

    private static final class ReplaySession {
        final String groupId;
        final List<ReplayMessage> before;
        final List<ReplayMessage> range;
        final Map<Long, ReplayMessage> messagesById;
        final ReplayProfile profile;
        final ReplayMode mode;
        final Instant snapshotAt;
        final Map<Long, String> generatedReplies = new ConcurrentHashMap<>();
        final GroupSceneMemory groupSceneMemory = new GroupSceneMemory();
        volatile long expiresAtMs;
        long lastProcessedLocalId;
        boolean failed;
        InjectionResult lastResult;
        AdvanceResult lastAdvanceResult;

        ReplaySession(String groupId, List<ReplayMessage> before, List<ReplayMessage> range,
                      List<ReplayMessage> replyTargets, ReplayProfile profile, ReplayMode mode, Instant snapshotAt,
                      long expiresAtMs) {
            this.groupId = groupId;
            this.before = before;
            this.range = range;
            Map<Long, ReplayMessage> all = new java.util.LinkedHashMap<>();
            before.forEach(message -> all.put(message.localId(), message));
            range.forEach(message -> all.put(message.localId(), message));
            replyTargets.forEach(message -> all.put(message.localId(), message));
            this.messagesById = Map.copyOf(all);
            this.profile = profile;
            this.mode = mode;
            this.snapshotAt = snapshotAt;
            this.expiresAtMs = expiresAtMs;
            this.lastProcessedLocalId = range.get(0).localId() - 1;
        }
    }
}
