package com.start.service;

/**
 * 对话事件类型。Java 只负责识别"发生了什么"，LLM 负责决定"怎么做"。
 * 只有 PROBABILISTIC 事件允许模型输出 NO_REPLY。
 */
public enum ConversationEvent {
    /** 追问 — 120s 窗口内继续对话 */
    FOLLOW_UP,
    /** 被 @ */
    MENTION,
    /** 红包/音乐/冷场等被动触发 */
    PASSIVE_TRIGGER,
    /** AI 发言被其他人评论 */
    AI_COMMENTED,
    /** 异步等待回复 */
    AWAIT_REPLY,
    /** 概率性主动插话（唯一允许 NO_REPLY 的事件） */
    PROBABILISTIC,
    /** 无需处理 */
    NOTHING;

    /** 该事件是否允许模型输出 NO_REPLY（沉默） */
    public boolean allowsSilence() {
        return this == PROBABILISTIC;
    }
}
