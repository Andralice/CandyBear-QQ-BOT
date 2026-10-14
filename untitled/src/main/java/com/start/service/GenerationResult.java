package com.start.service;

import java.util.List;

/** 统一的 AI 生成结果信封，替代裸 String 返回值 */
public class GenerationResult {
    public enum ReplyAction {
        REPLY, SILENT, ERROR
    }

    private final String reply;
    private final ReplyAction action;
    private final int toolCalls;
    private final int tokensUsed;
    private final GenerationMetadata metadata;
    private final List<String> evidenceMessageIds;
    private final GroupTurnAssessment groupAssessment;
    private final boolean replyToCurrentMessage;

    private GenerationResult(String reply, ReplyAction action, GenerationMetadata metadata,
                             List<String> evidenceMessageIds, GroupTurnAssessment groupAssessment,
                             boolean replyToCurrentMessage) {
        this.reply = reply;
        this.action = action;
        this.metadata = metadata;
        this.toolCalls = metadata != null ? metadata.toolCalls() : 0;
        this.tokensUsed = metadata != null ? metadata.tokensUsed() : 0;
        this.evidenceMessageIds = evidenceMessageIds == null ? List.of() : List.copyOf(evidenceMessageIds);
        this.groupAssessment = groupAssessment;
        this.replyToCurrentMessage = replyToCurrentMessage;
    }

    public static GenerationResult reply(String text, int toolCalls, int tokensUsed, long generation, long revision) {
        return new GenerationResult(text, ReplyAction.REPLY,
                GenerationMetadata.of(generation, revision, toolCalls, tokensUsed, 0), List.of(), null, false);
    }

    public static GenerationResult reply(String text, int toolCalls, int tokensUsed,
                                         GroupTurnAssessment assessment) {
        return reply(text, toolCalls, tokensUsed, assessment, false);
    }

    public static GenerationResult reply(String text, int toolCalls, int tokensUsed,
                                         GroupTurnAssessment assessment, boolean replyToCurrentMessage) {
        return new GenerationResult(text, ReplyAction.REPLY,
                GenerationMetadata.of(0, 0, toolCalls, tokensUsed, 0), List.of(), assessment,
                replyToCurrentMessage);
    }

    /** 兼容旧签名（无 generation/revision 时用 0） */
    public static GenerationResult reply(String text, int toolCalls, int tokensUsed) {
        return reply(text, toolCalls, tokensUsed, 0, 0);
    }

    public static GenerationResult silent(int toolCalls, int tokensUsed) {
        return new GenerationResult("", ReplyAction.SILENT,
                GenerationMetadata.of(0, 0, toolCalls, tokensUsed, 0), List.of(), null, false);
    }

    public static GenerationResult silent(int toolCalls, int tokensUsed, GroupTurnAssessment assessment) {
        return new GenerationResult("", ReplyAction.SILENT,
                GenerationMetadata.of(0, 0, toolCalls, tokensUsed, 0), List.of(), assessment, false);
    }

    public static GenerationResult error(String fallbackText) {
        return new GenerationResult(fallbackText, ReplyAction.ERROR, null, List.of(), null, false);
    }

    public GenerationResult withEvidenceMessageIds(List<String> ids) {
        return new GenerationResult(reply, action, metadata, ids, groupAssessment, replyToCurrentMessage);
    }

    public String reply() { return reply; }
    public ReplyAction action() { return action; }
    public int toolCalls() { return toolCalls; }
    public int tokensUsed() { return tokensUsed; }
    public GenerationMetadata metadata() { return metadata; }
    public List<String> evidenceMessageIds() { return evidenceMessageIds; }
    public GroupTurnAssessment groupAssessment() { return groupAssessment; }
    public boolean replyToCurrentMessage() { return replyToCurrentMessage; }

    public boolean isSilent() { return action == ReplyAction.SILENT; }
    public boolean isError() { return action == ReplyAction.ERROR; }
    public boolean shouldSend() { return action == ReplyAction.REPLY && reply != null && !reply.isBlank(); }
}
