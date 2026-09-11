package com.start.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Opt-in contract test for the configured OpenAI-compatible cognition endpoint. */
class GroupDialogueAnalyzerLiveTest {

    @Test
    void hardGroupScenariosProduceUsablePlans() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("GROUP_COGNITION_LIVE_TEST")));
        String key = System.getenv("GROUP_COGNITION_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank());

        GroupDialogueAnalyzer analyzer = new GroupDialogueAnalyzer(
                GroupCognitionMode.ENFORCE, Set.of(1L), key,
                "https://www.wyy22.com/v1/chat/completions", "gpt-5.6-luna", 30_000);

        GroupDialoguePlan continuation = analyzer.analyze(List.of(
                msg("m1", "999", "糖果熊", "小陈，你说的是生产库还是测试库？"),
                msg("m2", "u2", "老王", "小陈那边是测试库"),
                msg("m3", "u1", "小陈", "对，测试库，昨天还能连")));
        assertNotNull(continuation);
        assertEquals(GroupDialoguePlan.EntryRole.SECOND_PERSON, continuation.entryRole());
        assertEquals("u1", continuation.targetUserId());

        GroupDialoguePlan interjection = analyzer.analyze(List.of(
                msg("m1", "u1", "阿明", "这个正则怎么匹配金额？"),
                msg("m2", "u2", "老周", "\\d+ 不行吗"),
                msg("m3", "u1", "阿明", "不行，要保留两位小数"),
                msg("m4", "u2", "老周", "那我不会了")));
        assertNotNull(interjection);
        assertEquals(GroupDialoguePlan.EntryRole.THIRD_PERSON_INTERJECTION, interjection.entryRole());
        assertEquals("u1", interjection.targetUserId());

        GroupDialoguePlan observer = analyzer.analyze(List.of(
                msg("m1", "u1", "阿明", "服务器一直502"),
                new BaiLianService.PublicMessage("m2", "u2", "老周",
                        "Nginx日志里有 upstream timed out 吗？", List.of(), null),
                new BaiLianService.PublicMessage("m3", "u1", "阿明",
                        "有，超时是60秒", List.of(), 2L)));
        assertNotNull(observer);
        assertEquals(GroupDialoguePlan.EntryRole.OBSERVER, observer.entryRole());
    }

    private static BaiLianService.PublicMessage msg(String id, String userId,
                                                     String nickname, String content) {
        return new BaiLianService.PublicMessage(id, userId, nickname, content);
    }
}
