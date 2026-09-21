package com.start.service;

/**
 * 对话事件类型。Java 只负责识别"发生了什么"，LLM 负责决定"怎么做"。
 * 明确 @、待回复和被动协议事件保持必须回应；模糊的群聊候选交给同一次主模型调用决定是否沉默。
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
    /** 概率性主动插话 */
    PROBABILISTIC,
    /** 并行认知确认糖果熊拥有下一轮话权 */
    COGNITIVE_CONTINUE,
    /** 并行认知确认可作为第三人提供高价值帮助 */
    COGNITIVE_INTERJECTION,
    /** 本地证据表明可能是在接糖果熊，但仍由本轮主模型决定是否回复 */
    IMPLICIT_CANDIDATE,
    /** 无需处理 */
    NOTHING;

    /** 该事件是否允许模型在同一次主模型调用中输出 NO_REPLY（沉默） */
    public boolean allowsSilence() {
        return switch (this) {
            case FOLLOW_UP, AI_COMMENTED, PROBABILISTIC,
                    COGNITIVE_CONTINUE, COGNITIVE_INTERJECTION,
                    IMPLICIT_CANDIDATE -> true;
            case MENTION, PASSIVE_TRIGGER, AWAIT_REPLY, NOTHING -> false;
        };
    }
}
