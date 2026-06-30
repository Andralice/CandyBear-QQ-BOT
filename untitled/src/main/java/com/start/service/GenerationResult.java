package com.start.service;

/** 统一的 AI 生成结果信封，替代裸 String 返回值 */
public class GenerationResult {
    public enum ReplyAction {
        /** 正常回复 */
        REPLY,
        /** 模型主动选择沉默（NO_REPLY） */
        SILENT,
        /** 生成出错 */
        ERROR
    }

    private final String reply;
    private final ReplyAction action;
    private final int toolCalls;
    private final int tokensUsed;

    private GenerationResult(String reply, ReplyAction action, int toolCalls, int tokensUsed) {
        this.reply = reply;
        this.action = action;
        this.toolCalls = toolCalls;
        this.tokensUsed = tokensUsed;
    }

    public static GenerationResult reply(String text, int toolCalls, int tokensUsed) {
        return new GenerationResult(text, ReplyAction.REPLY, toolCalls, tokensUsed);
    }

    public static GenerationResult silent(int toolCalls, int tokensUsed) {
        return new GenerationResult("", ReplyAction.SILENT, toolCalls, tokensUsed);
    }

    public static GenerationResult error(String fallbackText) {
        return new GenerationResult(fallbackText, ReplyAction.ERROR, 0, 0);
    }

    public String reply() { return reply; }
    public ReplyAction action() { return action; }
    public int toolCalls() { return toolCalls; }
    public int tokensUsed() { return tokensUsed; }

    public boolean isSilent() { return action == ReplyAction.SILENT; }
    public boolean isError() { return action == ReplyAction.ERROR; }
    public boolean shouldSend() { return action == ReplyAction.REPLY && reply != null && !reply.isBlank(); }
}
