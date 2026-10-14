package com.start.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.agent.GroupTurnResultTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroupTurnDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesSeparateMessagesAndSceneFromOneStructuredResult() throws Exception {
        GroupTurnAssessment assessment = GroupTurnResultTool.parse("""
                {"decision":"REPLY","messages":["我也想试试这个。","你玩到哪一关了？"],"topic":"新出的游戏\\n讨论",
                 "interest":"玩法设计","open_need":"","keep_scene":true,"reason":"有具体兴趣点"}
                """, mapper);

        assertEquals(GroupTurnAssessment.Decision.REPLY, assessment.decision());
        assertEquals(java.util.List.of("我也想试试这个。", "你玩到哪一关了？"), assessment.messages());
        assertEquals("新出的游戏 讨论", assessment.topic());
        assertTrue(assessment.keepScene());
    }

    @Test
    void rejectsInvalidOrIncompleteResults() {
        assertThrows(Exception.class, () -> GroupTurnResultTool.parse("{}", mapper));
        assertThrows(IllegalArgumentException.class, () -> GroupTurnResultTool.parse("""
                {"decision":"REPLY","messages":[],"topic":"","interest":"","open_need":"",
                 "keep_scene":false,"reason":""}
                """, mapper));
        assertThrows(IllegalArgumentException.class, () -> GroupTurnResultTool.parse("""
                {"decision":"REPLY","messages":["第一条","第二条","第三条"],"topic":"","interest":"","open_need":"",
                 "keep_scene":false,"reason":""}
                """, mapper));
        assertThrows(IllegalArgumentException.class, () -> GroupTurnResultTool.parse("""
                {"decision":"SILENT","messages":["多余内容"],"topic":"","interest":"","open_need":"",
                 "keep_scene":false,"reason":""}
                """, mapper));
        assertThrows(IllegalArgumentException.class, () -> GroupTurnResultTool.parse("""
                {"decision":"REPLY","messages":["第一条", "  "],"topic":"","interest":"","open_need":"",
                 "keep_scene":false,"reason":""}
                """, mapper));
    }

    @Test
    void sceneMemoryIsScopedExpiresAndDoesNotAcceptLateResults() {
        GroupSceneMemory memory = new GroupSceneMemory();
        memory.update("g1", assessment("新话题", true), 20_000);
        memory.update("g2", assessment("另一个群", true), 21_000);
        memory.update("g1", assessment("迟到的旧结果", true), 19_000);

        assertEquals("新话题", memory.get("g1", 20_001).topic());
        assertEquals("另一个群", memory.get("g2", 20_001).topic());
        assertNull(memory.get("g1", 20_000 + GroupSceneMemory.TTL_MS));
    }

    @Test
    void onlyGroupPromptsRequireTheCombinedDecisionAndRecheckPriorScene() {
        PromptBuilder builder = new PromptBuilder();
        String groupPrompt = builder.buildRuleBook(RuleSetDefaults.defaults(), new PromptContext()
                .userId("10001").groupId("20002").allowSilence(true)
                .shortTermGroupScene(assessment("正在聊的主题", true).topic()));
        String privatePrompt = builder.buildRuleBook(RuleSetDefaults.defaults(), new PromptContext()
                .userId("10001"));

        assertTrue(groupPrompt.contains("group_turn_result"));
        assertTrue(groupPrompt.contains("reply_to_current_message"));
        assertTrue(groupPrompt.contains("默认作为普通群消息发送"));
        assertTrue(groupPrompt.contains("不引用就会让群友看不懂"));
        assertTrue(groupPrompt.contains("今晚吃火锅吗"));
        assertTrue(groupPrompt.contains("不能把兴趣说成承诺"));
        assertTrue(groupPrompt.contains("不得声称、承诺或安排自己与群友线下见面、吃饭、出行"));
        assertTrue(groupPrompt.contains("和群友联机打游戏、语音陪玩、同步观影"));
        assertTrue(groupPrompt.contains("那就七点"));
        assertTrue(groupPrompt.contains("不把自己算作赴约的人"));
        assertTrue(groupPrompt.contains("待复核的摘要线索"));
        assertTrue(groupPrompt.contains("数组每一项都会单独发送成一个 QQ 气泡"));
        assertTrue(groupPrompt.contains("同一主题里的即时反应、说明和紧接着的相关追问合在一项"));
        assertTrue(groupPrompt.contains("不同话题本身不构成拆分理由"));
        assertTrue(groupPrompt.contains("两个彼此独立的接话动作"));
        assertTrue(groupPrompt.contains("分别回应两人"));
        assertTrue(groupPrompt.contains("群消息条数最终检查"));
        assertFalse(groupPrompt.contains("<msg>"));
        assertFalse(groupPrompt.contains("态度或玩笑反应，以及独立的事实或技术结论，这两种表达目的必须分别发送为两条"));
        assertFalse(privatePrompt.contains("group_turn_result"));
        assertFalse(privatePrompt.contains("reply_to_current_message"));
        assertFalse(privatePrompt.contains("群消息条数最终检查"));
        assertTrue(privatePrompt.contains("态度或玩笑反应，以及独立的事实或技术结论，这两种表达目的必须分别发送为两条"));
    }

    private static GroupTurnAssessment assessment(String topic, boolean keep) {
        return new GroupTurnAssessment(GroupTurnAssessment.Decision.SILENT, java.util.List.of(), topic,
                "", "", keep, "test");
    }
}
