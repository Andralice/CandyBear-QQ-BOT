package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates a reviewable summary after a session closes.
 * The result remains a session summary and is never promoted to person memory here.
 */
public final class GroupConversationSessionSummaryService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger("com.start.session.summary");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ITEMS = 12;
    private static final int MAX_ITEM_CHARS = 280;
    private static final int MAX_EVIDENCE_CHARS = 8_000;

    private final BaiLianService aiService;
    private final GroupConversationSummaryRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CandyBear-Session-Summary");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    public GroupConversationSessionSummaryService(BaiLianService aiService,
                                                  GroupConversationSummaryRepository repository) {
        this.aiService = aiService;
        this.repository = repository;
    }

    public void schedule(GroupConversationSession session,
                         List<BaiLianService.PublicMessage> history) {
        if (session == null || repository == null) return;
        if (!scheduled.add(session.sessionId())) return;
        try {
            if ("COMPLETED".equals(repository.findStatus(session.sessionId()))) {
                scheduled.remove(session.sessionId());
                return;
            }
        } catch (RuntimeException e) {
            logger.debug("session summary status lookup skipped session_id={}: {}",
                    session.sessionId(), e.getMessage());
        }
        String evidence = renderEvidence(session, history);
        try {
            repository.savePending(session, evidence);
        } catch (RuntimeException e) {
            logger.warn("session summary pending write failed session_id={}: {}",
                    session.sessionId(), e.getMessage());
        }
        executor.execute(() -> generate(session, evidence));
    }

    private void generate(GroupConversationSession session, String evidence) {
        try {
            if (aiService == null) throw new IllegalStateException("AI service unavailable");
            String raw = aiService.generateRaw(buildPrompt(session, evidence));
            SummaryDraft draft = parseDraft(raw, session);
            repository.saveCompleted(session, draft.summary(), draft.confirmed(),
                    draft.uncertain(), draft.unresolved(), evidence);
            logger.info("session_summary session_id={} status=COMPLETED evidence_count={}",
                    session.sessionId(), session.evidenceMessageIds().size());
        } catch (Exception e) {
            String fallback = fallbackSummary(session);
            try {
                repository.saveFailed(session, fallback, evidence, errorSummary(e));
            } catch (RuntimeException persistError) {
                logger.warn("session summary failure write failed session_id={}: {}",
                        session.sessionId(), persistError.getMessage());
            }
            logger.warn("session_summary session_id={} status=FAILED error={}",
                    session.sessionId(), errorSummary(e));
        } finally {
            scheduled.remove(session.sessionId());
        }
    }

    private String buildPrompt(GroupConversationSession session, String evidence) {
        return """
                你是群聊会话记录整理器。只能根据给出的消息证据总结，不得补写人物经历、情绪、动机或现实事实。
                这是一个已经关闭的群聊会话。请把“消息中明确说出的内容”和“仍然不确定的内容”分开。
                不要把群友说“糖果熊怎样”当成糖果熊自己的经历；不要把网络图片、转述或玩笑当成事实。
                evidence 的 status=UNVERIFIED 或 FAILED 只能写入 uncertain，不能写入 confirmed；图片和网页只能说明其内容。
                输出严格 JSON，不要 Markdown，不要额外解释：
                {"summary":"简短总结","confirmed":["已确认内容"],"uncertain":["不能确认的内容"],"unresolved_questions":["未解决问题"]}
                会话信息：
                session_id=%s
                topic=%s
                close_reason=%s
                消息证据：
                %s
                """.formatted(session.sessionId(), safe(session.topic()), safe(session.closeReason()), evidence);
    }

    static SummaryDraft parseDraft(String raw, GroupConversationSession session) {
        String json = extractJson(raw);
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("summary is not an object");
            String summary = limited(root.path("summary").asText(""), MAX_ITEM_CHARS);
            if (summary.isBlank()) throw new IllegalArgumentException("summary is empty");
            return new SummaryDraft(summary,
                    joinItems(root.path("confirmed")),
                    joinItems(root.path("uncertain")),
                    joinItems(root.path("unresolved_questions")));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid summary JSON", e);
        }
    }

    private static String renderEvidence(GroupConversationSession session,
                                         List<BaiLianService.PublicMessage> history) {
        Set<String> ids = session.evidenceMessageIds();
        StringBuilder output = new StringBuilder();
        if (history != null) {
            for (BaiLianService.PublicMessage message : history) {
                if (!ids.isEmpty() && !ids.contains(message.messageId)) continue;
                if (output.length() >= MAX_EVIDENCE_CHARS) break;
                String content = message.content == null || message.content.isBlank()
                        ? "[无文字内容]" : message.content;
                String image = message.imageDescription == null || message.imageDescription.isBlank()
                        ? "" : "；图片描述=" + message.imageDescription;
                String line = "[message_id=" + safe(message.messageId) + "][sender_id="
                        + safe(message.userId) + "][sender=" + safe(message.nickname) + "] "
                        + safe(content) + safe(image);
                output.append(limited(line, 700)).append('\n');
            }
        }
        if (output.length() == 0) {
            output.append("[没有找到可回放的完整消息，仅保留证据 ID] ")
                    .append(String.join(",", ids));
        }
        for (GroupConversationEvidence item : session.evidence()) {
            if (!item.allowSummary() || output.length() >= MAX_EVIDENCE_CHARS) continue;
            output.append("[evidence_id=").append(safe(item.evidenceId()))
                    .append("][type=").append(item.type())
                    .append("][status=").append(item.status())
                    .append("][source_message_id=").append(safe(item.sourceMessageId())).append("] ")
                    .append(limited(item.content(), 700));
            if (!item.sourceUrl().isBlank()) output.append(" <").append(safe(item.sourceUrl())).append(">");
            output.append('\n');
        }
        return limited(output.toString(), MAX_EVIDENCE_CHARS);
    }

    private static String fallbackSummary(GroupConversationSession session) {
        String topic = safe(session.topic());
        if (topic.isBlank()) topic = "未提取到明确主题";
        return "会话已关闭，主题为“" + limited(topic, 80) + "”。AI 总结失败，未将未确认内容写入人物事实。";
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            int newline = text.indexOf('\n');
            text = newline >= 0 ? text.substring(newline + 1, text.length() - 3).trim() : text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("summary JSON missing");
        return text.substring(start, end + 1);
    }

    private static String joinItems(JsonNode node) {
        if (node == null || !node.isArray()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (values.size() >= MAX_ITEMS) break;
            String value = limited(item.asText(""), MAX_ITEM_CHARS);
            if (!value.isBlank()) values.add(value);
        }
        return String.join("\n", values);
    }

    private static String limited(String value, int limit) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String errorSummary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ":" + limited(current.getMessage(), 240);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record SummaryDraft(String summary, String confirmed, String uncertain, String unresolved) {}
}
