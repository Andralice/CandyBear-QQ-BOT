package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupConversationSessionSummaryServiceTest {

    @Test
    void parsesStructuredSummaryAndKeepsUncertaintySeparate() {
        GroupConversationSession session = new GroupConversationSession("g\u001fthread", "g", "thread");
        GroupConversationSessionSummaryService.SummaryDraft draft =
                GroupConversationSessionSummaryService.parseDraft("""
                        {"summary":"讨论了游戏更新","confirmed":["群友说客户端已更新"],"uncertain":["糖果熊是否亲自玩过"],"unresolved_questions":["是否需要重新登录"]}
                        """, session);

        assertEquals("讨论了游戏更新", draft.summary());
        assertEquals("群友说客户端已更新", draft.confirmed());
        assertEquals("糖果熊是否亲自玩过", draft.uncertain());
        assertEquals("是否需要重新登录", draft.unresolved());
    }

    @Test
    void acceptsJsonWrappedInMarkdownFence() {
        GroupConversationSession session = new GroupConversationSession("s", "g", "t");
        GroupConversationSessionSummaryService.SummaryDraft draft =
                GroupConversationSessionSummaryService.parseDraft("```json\n{\"summary\":\"结束\",\"confirmed\":[],\"uncertain\":[],\"unresolved_questions\":[]}\n```", session);

        assertEquals("结束", draft.summary());
    }

    @Test
    void rejectsMissingSummaryInsteadOfInventingOne() {
        GroupConversationSession session = new GroupConversationSession("s", "g", "t");
        assertThrows(IllegalArgumentException.class, () ->
                GroupConversationSessionSummaryService.parseDraft(
                        "{\"confirmed\":[\"x\"]}", session));
    }
}
