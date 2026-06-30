package com.start.service;

/** 生成元数据：generation/revision/toolCalls/latency。供 Trace 和 Replay 使用。 */
public record GenerationMetadata(
        long generation,
        long revision,
        int toolCalls,
        int tokensUsed,
        long latencyMs) {

    public static GenerationMetadata of(long gen, long rev, int tools, int tokens, long ms) {
        return new GenerationMetadata(gen, rev, tools, tokens, ms);
    }
}
