package com.start.runtime.conversation;

import java.time.Duration;

/** ConversationRuntime 配置，集中管理所有可调参数。 */
public record ConversationRuntimeConfig(
        boolean allowSilence,
        boolean enableTrace,
        Duration followUpWindow,
        Duration groupReplyCooldown,
        Duration userReplyCooldown,
        int maxRegenerate) {

    public static ConversationRuntimeConfig defaults() {
        return new ConversationRuntimeConfig(
                true,
                true,
                Duration.ofSeconds(120),
                Duration.ofSeconds(12),
                Duration.ofSeconds(2),
                2);
    }
}
