package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 管理群内长期会话状态。它只做确定性的证据归类，不生成回复。
 */
public final class GroupConversationSessionManager {
    private static final Logger logger = LoggerFactory.getLogger("com.start.session");
    private static final long CANDIDATE_TTL_MS = 90_000L;
    private static final long WAITING_TTL_MS = 10 * 60_000L;
    private static final long CLOSED_TTL_MS = 24 * 60 * 60_000L;
    private static final String UNRESOLVED_THREAD = "unresolved";

    private final Map<String, GroupConversationSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingBotReplies = new ConcurrentHashMap<>();
    private final Map<String, List<GroupConversationEvidence>> pendingEvidence = new ConcurrentHashMap<>();
    private final GroupConversationSessionRepository repository;
    private final GroupConversationEvidenceRepository evidenceRepository;

    public GroupConversationSessionManager() {
        this(null, null);
    }

    public GroupConversationSessionManager(GroupConversationSessionRepository repository) {
        this(repository, null);
    }

    public GroupConversationSessionManager(GroupConversationSessionRepository repository,
                                           GroupConversationEvidenceRepository evidenceRepository) {
        this.repository = repository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 记录一条群消息并产生影子判断。这个判断不改变现有回复链路。
     */
    public GroupConversationSession.Decision observe(GroupConversationSession.Observation observation) {
        expire(observation.observedAt());
        String groupId = observation.groupId();
        String threadId = normalizeThreadId(observation.threadId());
        String sessionId = key(groupId, threadId);
        GroupConversationSession session = sessions.computeIfAbsent(sessionId, ignored -> loadOrCreate(sessionId, groupId, threadId));
        attachPendingEvidence(session, observation.messageId());
        Long pendingReplyAt = pendingBotReplies.remove(key(groupId, observation.messageId()));
        if (pendingReplyAt != null) session.markBotReply("", pendingReplyAt);

        GroupConversationSession.BotPosition position = classifyPosition(observation, session);
        GroupConversationSession.Action action;
        GroupConversationSession.LifecycleState state;
        String reason;
        boolean explicitClose = isClosure(observation.text())
                && session.lifecycleState() != GroupConversationSession.LifecycleState.OBSERVING
                && session.lifecycleState() != GroupConversationSession.LifecycleState.CLOSED;
        if (explicitClose) {
            action = GroupConversationSession.Action.CLOSE;
            state = GroupConversationSession.LifecycleState.CLOSED;
            reason = "explicit_closure_phrase";
        } else if (observation.botMentioned() || observation.replyToBot()) {
            action = session.lifecycleState() == GroupConversationSession.LifecycleState.ACTIVE
                    ? GroupConversationSession.Action.CONTINUE : GroupConversationSession.Action.OPEN;
            state = GroupConversationSession.LifecycleState.ACTIVE;
            reason = observation.botMentioned() ? "explicit_bot_mention" : "reply_to_bot";
        } else if (position == GroupConversationSession.BotPosition.SECOND_PERSON_CONTINUATION) {
            action = GroupConversationSession.Action.CONTINUE;
            state = GroupConversationSession.LifecycleState.ACTIVE;
            reason = "continuation_of_bot_turn";
        } else if (position == GroupConversationSession.BotPosition.GROUP_WIDE_PARTICIPANT) {
            action = GroupConversationSession.Action.CANDIDATE;
            state = session.lifecycleState() == GroupConversationSession.LifecycleState.ACTIVE
                    ? GroupConversationSession.LifecycleState.ACTIVE
                    : GroupConversationSession.LifecycleState.CANDIDATE;
            reason = "group_wide_greeting_or_open_topic";
        } else if (position == GroupConversationSession.BotPosition.OTHER_MEMBER_CONVERSATION) {
            action = GroupConversationSession.Action.OBSERVE;
            state = session.lifecycleState() == GroupConversationSession.LifecycleState.ACTIVE
                    ? GroupConversationSession.LifecycleState.WAITING_FOR_REPLY
                    : GroupConversationSession.LifecycleState.OBSERVING;
            reason = "addressed_to_other_member";
        } else {
            action = GroupConversationSession.Action.OBSERVE;
            state = session.lifecycleState() == GroupConversationSession.LifecycleState.ACTIVE
                    ? GroupConversationSession.LifecycleState.WAITING_FOR_REPLY
                    : GroupConversationSession.LifecycleState.OBSERVING;
            reason = observation.imagePresent() ? "unresolved_image_context" : "no_bot_turn_evidence";
        }

        session.observe(observation, state, position, reason, topicFrom(observation.text()));
        if (!observation.messageId().isBlank() && !observation.text().isBlank()) {
            addEvidence(session, new GroupConversationEvidence(
                    evidenceId(session.sessionId(), GroupConversationEvidence.Type.GROUP_MESSAGE,
                            observation.messageId(), "", 0, observation.text()),
                    session.sessionId(), GroupConversationEvidence.Type.GROUP_MESSAGE,
                    observation.messageId(), "", 0, observation.text(), "", 1D,
                    GroupConversationEvidence.Status.CONFIRMED, observation.observedAt(), true));
        }
        if (action == GroupConversationSession.Action.CLOSE) session.close(reason, observation.observedAt());
        session.consumePendingBotTurn();
        persist(session);
        GroupConversationSession.Decision decision = new GroupConversationSession.Decision(
                session.sessionId(), groupId, threadId, state, position, action, reason,
                observation.messageId(), observation.observedAt());
        logger.info("session_shadow session_id={} group_id={} thread_id={} action={} state={} position={} reason={} message_id={}",
                decision.sessionId(), decision.groupId(), decision.threadId(), decision.action(),
                decision.state(), decision.botPosition(), decision.reason(), decision.latestMessageId());
        return decision;
    }

    public void markBotReply(String groupId, String threadId, String messageId, long at) {
        String normalizedThread = normalizeThreadId(threadId);
        String sessionId = key(groupId, normalizedThread);
        GroupConversationSession session = sessions.computeIfAbsent(sessionId,
                ignored -> loadOrCreate(sessionId, groupId, normalizedThread));
        session.markBotReply(messageId == null ? "" : messageId, at <= 0 ? System.currentTimeMillis() : at);
        persist(session);
    }

    /** 将回复绑定到刚刚触发本轮生成的入站消息，即使线程 ID 在异步处理后才解析出来。 */
    public void markBotReplyForMessage(String groupId, String inboundMessageId, String botMessageId, long at) {
        if (inboundMessageId == null || inboundMessageId.isBlank()) return;
        long timestamp = at <= 0 ? System.currentTimeMillis() : at;
        boolean matched = false;
        for (GroupConversationSession session : sessions.values()) {
            if (!session.groupId().equals(groupId) || !inboundMessageId.equals(session.lastMessageId())) continue;
            matched = true;
            session.markBotReply(botMessageId == null ? "" : botMessageId, timestamp);
            persist(session);
        }
        if (!matched) pendingBotReplies.put(key(groupId, inboundMessageId), timestamp);
    }

    /** Attach a completed Vision/search/web result to the session owning the source message. */
    public void recordEvidenceForMessage(String groupId, String sourceMessageId,
                                          GroupConversationEvidence.Type type,
                                          String imageMessageId, int imageIndex,
                                          String content, String sourceUrl, double confidence,
                                          GroupConversationEvidence.Status status,
                                          boolean allowSummary) {
        if (groupId == null || groupId.isBlank() || sourceMessageId == null || sourceMessageId.isBlank()
                || content == null || content.isBlank()) return;
        List<GroupConversationSession> matches = sessions.values().stream()
                .filter(session -> groupId.trim().equals(session.groupId()))
                .filter(session -> sourceMessageId.equals(session.lastMessageId())
                        || session.evidenceMessageIds().contains(sourceMessageId))
                .toList();
        if (!matches.isEmpty()) {
            for (GroupConversationSession session : matches) {
                GroupConversationEvidence item = evidenceFor(session.sessionId(), sourceMessageId, type, imageMessageId,
                        imageIndex, content, sourceUrl, confidence, status, allowSummary);
                addEvidence(session, item);
                persist(session);
            }
            return;
        }
        pendingEvidence.compute(key(groupId, sourceMessageId), (ignored, existing) -> {
            List<GroupConversationEvidence> values = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            String pendingSessionId = "pending:" + groupId.trim() + "\u001f" + sourceMessageId.trim();
            values.add(evidenceFor(pendingSessionId, sourceMessageId, type, imageMessageId, imageIndex,
                    content, sourceUrl, confidence, status, allowSummary));
            return values.size() > 16 ? values.subList(values.size() - 16, values.size()) : values;
        });
    }

    public List<GroupConversationEvidence> evidenceFor(String groupId, String threadId) {
        return get(groupId, threadId).map(GroupConversationSession::evidence).orElse(List.of());
    }

    public Optional<GroupConversationSession> get(String groupId, String threadId) {
        String normalizedThread = normalizeThreadId(threadId);
        GroupConversationSession session = sessions.get(key(groupId, normalizedThread));
        if (session == null && repository != null) {
            session = repository.find(key(groupId, normalizedThread)).orElse(null);
            if (session != null) {
                loadEvidence(session);
                sessions.put(session.sessionId(), session);
            }
        }
        return Optional.ofNullable(session).map(GroupConversationSession::copy);
    }

    public List<GroupConversationSession> activeForGroup(String groupId) {
        List<GroupConversationSession> result = new ArrayList<>();
        sessions.values().stream()
                .filter(s -> s.groupId().equals(groupId))
                .filter(s -> s.lifecycleState() != GroupConversationSession.LifecycleState.CLOSED)
                .sorted(Comparator.comparingLong(GroupConversationSession::lastActivityAt).reversed())
                .forEach(s -> result.add(s.copy()));
        return List.copyOf(result);
    }

    /** 根据确定性超时推进 WAITING/CANDIDATE/关闭状态。 */
    public int expire(long now) {
        return expireAndCollect(now).size();
    }

    /** 推进超时并返回本次刚关闭的会话，供总结任务消费。 */
    public List<GroupConversationSession> expireAndCollect(long now) {
        long current = now <= 0 ? System.currentTimeMillis() : now;
        List<GroupConversationSession> closed = new ArrayList<>();
        for (GroupConversationSession session : sessions.values()) {
            long idle = current - session.lastActivityAt();
            GroupConversationSession.LifecycleState state = session.lifecycleState();
            if (state == GroupConversationSession.LifecycleState.CANDIDATE && idle >= CANDIDATE_TTL_MS) {
                session.close("candidate_expired", current);
                persist(session);
                closed.add(session.copy());
            } else if ((state == GroupConversationSession.LifecycleState.ACTIVE
                    || state == GroupConversationSession.LifecycleState.WAITING_FOR_REPLY
                    || state == GroupConversationSession.LifecycleState.PAUSED)
                    && idle >= WAITING_TTL_MS) {
                session.close("inactive_timeout", current);
                persist(session);
                closed.add(session.copy());
            } else if (state == GroupConversationSession.LifecycleState.CLOSED && idle >= CLOSED_TTL_MS) {
                sessions.remove(session.sessionId(), session);
            }
        }
        pendingBotReplies.entrySet().removeIf(entry -> current - entry.getValue() >= WAITING_TTL_MS);
        return List.copyOf(closed);
    }

    static String normalizeThreadId(String threadId) {
        return threadId == null || threadId.isBlank() ? UNRESOLVED_THREAD : threadId.trim();
    }

    static String key(String groupId, String threadId) {
        return (groupId == null ? "" : groupId.trim()) + "\u001f" + normalizeThreadId(threadId);
    }

    private GroupConversationSession loadOrCreate(String sessionId, String groupId, String threadId) {
        if (repository != null) {
            Optional<GroupConversationSession> saved = repository.find(sessionId);
            if (saved.isPresent()) {
                GroupConversationSession session = saved.get();
                loadEvidence(session);
                return session;
            }
        }
        return new GroupConversationSession(sessionId, groupId, threadId);
    }

    private void persist(GroupConversationSession session) {
        if (repository == null) return;
        try {
            repository.upsert(session);
        } catch (RuntimeException e) {
            logger.warn("session snapshot persistence failed, session_id={}: {}", session.sessionId(), e.getMessage());
        }
    }

    private void loadEvidence(GroupConversationSession session) {
        if (evidenceRepository == null) return;
        try {
            for (GroupConversationEvidence item : evidenceRepository.findBySessionId(session.sessionId())) {
                session.addEvidence(item);
            }
        } catch (RuntimeException e) {
            logger.warn("session evidence load failed, session_id={}: {}", session.sessionId(), e.getMessage());
        }
    }

    private void addEvidence(GroupConversationSession session, GroupConversationEvidence item) {
        if (!session.addEvidence(item)) return;
        if (evidenceRepository != null) {
            try {
                evidenceRepository.upsert(item);
            } catch (RuntimeException e) {
                logger.warn("session evidence persistence failed, session_id={}: {}",
                        session.sessionId(), e.getMessage());
            }
        }
    }

    private void attachPendingEvidence(GroupConversationSession session, String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        List<GroupConversationEvidence> pending = pendingEvidence.remove(key(session.groupId(), messageId));
        if (pending == null) return;
        for (GroupConversationEvidence item : pending) {
            GroupConversationEvidence bound = new GroupConversationEvidence(
                    evidenceId(session.sessionId(), item.type(), item.sourceMessageId(), item.imageMessageId(),
                            item.imageIndex(), item.content()),
                    session.sessionId(), item.type(), item.sourceMessageId(), item.imageMessageId(),
                    item.imageIndex(), item.content(), item.sourceUrl(), item.confidence(), item.status(),
                    item.createdAt(), item.allowSummary());
            addEvidence(session, bound);
        }
    }

    private static GroupConversationEvidence evidenceFor(String sessionId, String sourceMessageId,
                                                         GroupConversationEvidence.Type type,
                                                         String imageMessageId, int imageIndex,
                                                         String content, String sourceUrl, double confidence,
                                                         GroupConversationEvidence.Status status,
                                                         boolean allowSummary) {
        return new GroupConversationEvidence(
                evidenceId(sessionId, type, sourceMessageId, imageMessageId, imageIndex, content),
                sessionId, type, sourceMessageId,
                imageMessageId, imageIndex, content, sourceUrl, confidence, status,
                System.currentTimeMillis(), allowSummary);
    }

    private static String evidenceId(String sessionId, GroupConversationEvidence.Type type,
                                     String sourceMessageId, String imageMessageId, int imageIndex,
                                     String content) {
        String raw = String.join("|", sessionId == null ? "" : sessionId,
                type == null ? "" : type.name(), sourceMessageId == null ? "" : sourceMessageId,
                imageMessageId == null ? "" : imageMessageId, String.valueOf(imageIndex),
                content == null ? "" : content.trim());
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static GroupConversationSession.BotPosition classifyPosition(
            GroupConversationSession.Observation observation, GroupConversationSession session) {
        if (observation.botMentioned() || observation.replyToBot()) {
            return GroupConversationSession.BotPosition.DIRECT_TARGET;
        }
        if (observation.addressedOther()) {
            return GroupConversationSession.BotPosition.OTHER_MEMBER_CONVERSATION;
        }
        if (session.hasPendingBotTurn()) {
            return GroupConversationSession.BotPosition.SECOND_PERSON_CONTINUATION;
        }
        if (isGroupWideMessage(observation.text())) {
            return GroupConversationSession.BotPosition.GROUP_WIDE_PARTICIPANT;
        }
        return observation.text().isBlank() && observation.imagePresent()
                ? GroupConversationSession.BotPosition.UNKNOWN
                : GroupConversationSession.BotPosition.TOPIC_OBSERVER;
    }

    private static boolean isGroupWideMessage(String text) {
        if (text == null || text.isBlank()) return false;
        String value = text.trim();
        return value.matches("(?s).*(大家|各位|群里|有人吗|早上好|晚上好|晚安|早安|大家好|有人在吗).*")
                || value.matches("(?s)^(早|晚安|早安|哈喽|嗨|大家好)[!！~～。,.， ]*$");
    }

    private static boolean isClosure(String text) {
        if (text == null) return false;
        return text.trim().matches("^(谢谢|谢了|解决了|好了|没事了|不用了|收到|明白了|问题解决了).{0,12}$");
    }

    private static String topicFrom(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }
}
