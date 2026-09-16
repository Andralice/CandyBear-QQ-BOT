package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiLianServiceReplySanitizationTest {

    @Test
    void selfIntroductionDoesNotExposeSettingMarkdown() {
        String leaked = "### 背景与相处\n- 角色名：糖果熊\n- 年龄：17岁\n- 系统身份：聊天机器人";

        assertEquals("我是糖果熊，平时就在群里和大家聊天。",
                BaiLianService.sanitizePublicReply("介绍一下你自己", leaked));
    }

    @Test
    void selfIntroductionDoesNotExposeInlineSettingFields() {
        String leaked = "角色名=糖果熊，年龄=17岁，性格=温柔慢热";

        assertEquals("我是糖果熊，平时就在群里和大家聊天。",
                BaiLianService.sanitizePublicReply("介绍一下你自己", leaked));
    }

    @Test
    void internalHeaderIsSuppressedOutsideIdentityQuestion() {
        String leaked = "### 工具能力与执行要求\n- send_sticker\n- query_past_images";

        assertTrue(BaiLianService.sanitizePublicReply("你现在能做什么？", leaked).contains("说明书"));
    }

    @Test
    void directRobotQuestionKeepsHonestIdentity() {
        String leaked = "角色名=糖果熊\n系统身份=聊天机器人\n年龄=17岁";

        assertEquals("我是聊天机器人，糖果熊是我在群里的名字。",
                BaiLianService.sanitizePublicReply("你是机器人吗？", leaked));
    }

    @Test
    void humanClaimIsCorrectedWhenIdentityIsAsked() {
        assertEquals("我是聊天机器人，糖果熊是我在群里的名字。",
                BaiLianService.sanitizePublicReply("你是人类女孩吗？", "我是人类女孩呀"));
    }

    @Test
    void ordinaryMarkdownIsNotChanged() {
        String reply = "**结论**：可以。\n\n- 先备份\n- 再验证";

        assertEquals(reply, BaiLianService.sanitizePublicReply("怎么做？", reply));
    }

    @Test
    void harmlessSelfIntroductionMarkdownIsFlattened() {
        String result = BaiLianService.sanitizePublicReply("介绍一下你自己",
                "### 我是谁\n- 我是糖果熊\n- 平时喜欢和大家聊天");

        assertEquals("我是谁\n我是糖果熊\n平时喜欢和大家聊天", result);
    }

    @Test
    void settingListIsSuppressedEvenWhenPromptIsNotIdentityQuestion() {
        String leaked = "角色名：糖果熊\n年龄：17岁\n性格：温柔慢热";

        assertEquals("我刚才说得太像说明书了，忽略那段，咱们接着聊。",
                BaiLianService.sanitizePublicReply("今天天气怎么样", leaked));
    }

    @Test
    void promptLeakOutsideIntroductionIsSuppressed() {
        String leaked = "===== 背景与相处 =====\n===== 工具能力与执行要求 =====";

        String result = BaiLianService.sanitizePublicReply("今天吃什么？", leaked);
        assertTrue(result.contains("说明书"));
        assertTrue(!result.contains("工具能力"));
    }
}
