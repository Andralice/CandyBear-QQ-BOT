package com.start.runtime;

import com.start.service.ConversationEvent;
import com.start.service.ConversationInterpreter;
import com.start.service.GenerationResult;

/** Runtime 生命周期事件。事件驱动分发，新增事件类型不改 Listener 接口。 */
public sealed interface RuntimeEvent
        permits RuntimeEvent.MessageReceived, RuntimeEvent.Interpreted,
                RuntimeEvent.GenerationStarted, RuntimeEvent.GenerationFinished,
                RuntimeEvent.CommitFinished {

    record MessageReceived(String groupId, String userId, String text) implements RuntimeEvent {}

    record Interpreted(String groupId, String userId,
                       ConversationEvent event,
                       ConversationInterpreter.InterpretResult result) implements RuntimeEvent {}

    record GenerationStarted(String groupId, String userId, long generation) implements RuntimeEvent {}

    record GenerationFinished(String groupId, String userId,
                              GenerationResult result) implements RuntimeEvent {}

    record CommitFinished(String groupId, String userId,
                          GenerationResult result, long latencyMs) implements RuntimeEvent {}
}
