package com.start.service;

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

    private GenerationResult(String reply, ReplyAction action, GenerationMetadata metadata) {
        this.reply = reply;
        this.action = action;
        this.metadata = metadata;
        this.toolCalls = metadata != null ? metadata.toolCalls() : 0;
        this.tokensUsed = metadata != null ? metadata.tokensUsed() : 0;
    }

    public static GenerationResult reply(String text, int toolCalls, int tokensUsed, long generation, long revision) {
        return new GenerationResult(text, ReplyAction.REPLY,
                GenerationMetadata.of(generation, revision, toolCalls, tokensUsed, 0));
    }

    /** 兼容旧签名（无 generation/revision 时用 0） */
    public static GenerationResult reply(String text, int toolCalls, int tokensUsed) {
        return reply(text, toolCalls, tokensUsed, 0, 0);
    }

    public static GenerationResult silent(int toolCalls, int tokensUsed) {
        return new GenerationResult("", ReplyAction.SILENT,
                GenerationMetadata.of(0, 0, toolCalls, tokensUsed, 0));
    }

    public static GenerationResult error(String fallbackText) {
        return new GenerationResult(fallbackText, ReplyAction.ERROR, null);
    }

    public String reply() { return reply; }
    public ReplyAction action() { return action; }
    public int toolCalls() { return toolCalls; }
    public int tokensUsed() { return tokensUsed; }
    public GenerationMetadata metadata() { return metadata; }

    public boolean isSilent() { return action == ReplyAction.SILENT; }
    public boolean isError() { return action == ReplyAction.ERROR; }
    public boolean shouldSend() { return action == ReplyAction.REPLY && reply != null && !reply.isBlank(); }
}
