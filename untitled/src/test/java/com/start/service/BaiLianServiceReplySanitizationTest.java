package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiLianServiceReplySanitizationTest {

    @Test
    void internalWorkspaceContextIsDroppedRatherThanPartiallySent() {
        String leaked = "唉 AI税\nFor context:\nWORKSPACE: `/home/user/workspace/agentic-coding`\nISSUE OR TASK:";
        assertEquals("", BaiLianService.sanitizePublicReply("唉 AI税", leaked));
    }

    @Test
    void selfIntroductionDoesNotExposeSettingMarkdown() {
        String leaked = "### 背景与相处\n- 角色名：糖果熊\n- 年龄：17岁\n- 系统身份：聊天机器人";

        assertEquals("我叫糖果熊，17岁，北京高二。数学很烂。",
                BaiLianService.sanitizePublicReply("介绍一下你自己", leaked));
    }

    @Test
    void selfIntroductionDoesNotExposeInlineSettingFields() {
        String leaked = "角色名=糖果熊，年龄=17岁，性格=温柔慢热";

        assertEquals("我叫糖果熊，17岁，北京高二。数学很烂。",
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

        assertEquals("是啊，但我还是糖果熊",
                BaiLianService.sanitizePublicReply("你是机器人吗？", leaked));
    }

    @Test
    void humanClaimIsCorrectedWhenIdentityIsAsked() {
        assertEquals("我是聊天机器人，但我还是糖果熊",
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

        assertEquals("刚才说得太像说明书了，当我没说",
                BaiLianService.sanitizePublicReply("今天天气怎么样", leaked));
    }

    @Test
    void promptLeakOutsideIntroductionIsSuppressed() {
        String leaked = "===== 背景与相处 =====\n===== 工具能力与执行要求 =====";

        String result = BaiLianService.sanitizePublicReply("今天吃什么？", leaked);
        assertTrue(result.contains("说明书"));
        assertTrue(!result.contains("工具能力"));
    }

    @Test
    void internalTimestampPrefixIsRemovedFromPublicReply() {
        assertEquals("还有AP数学题没写完。",
                BaiLianService.sanitizePublicReply("最近过得好不好", "[09-17 14:43] 还有AP数学题没写完。"));
    }

    @Test
    void internalTimestampPrefixIsRemovedAfterMessageTag() {
        assertEquals("<msg>还有AP数学题没写完。</msg><msg>先这样。</msg>",
                BaiLianService.sanitizePublicReply("随便聊聊",
                        "<msg>[09-17 14:43] 还有AP数学题没写完。</msg><msg>[14:44] 先这样。</msg>"));
    }

    @Test
    void timestampInsideSentenceIsPreserved() {
        String reply = "我记得你说过[09-17 14:43]之后再处理";
        assertEquals(reply, BaiLianService.sanitizePublicReply("你记得吗", reply));
    }

    @Test
    void explicitTimestampRequestIsPreserved() {
        String reply = "[09-17 14:43] 已记录";
        assertEquals(reply, BaiLianService.sanitizePublicReply("请带时间戳回答", reply));
    }

    @Test
    void emojiAndKaomojiAreRemovedFromPublicReplies() {
        assertEquals("这都被你发现了",
                BaiLianService.sanitizePublicReply("你又瞎说", "这都被你发现了😂"));
        assertEquals("好吧",
                BaiLianService.sanitizePublicReply("不许去了", "好吧 QAQ"));
        assertEquals("行",
                BaiLianService.sanitizePublicReply("知道了吗", "行(￣▽￣)"));
        assertEquals("可以",
                BaiLianService.sanitizePublicReply("可以吗", "可以 :-)"));
        assertEquals("第1题",
                BaiLianService.sanitizePublicReply("哪一题", "第1️⃣题"));
        assertEquals("", BaiLianService.sanitizePublicReply("在吗", "😂🥺"));
    }

    @Test
    void noReplyMarkerIsRecognizedAsInternalControlText() {
        assertTrue(BaiLianService.isNoReplyMarker("\n<NO_REPLY>\n"));
        assertFalse(BaiLianService.isNoReplyMarker("<NO_REPLY> 还有一句"));
        assertTrue(BaiLianService.containsNoReplyMarker("<NO_REPLY> 还有一句"));
    }

    @Test
    void faceCqCodesAreRemovedButAllowedMediaCqCodesArePreserved() {
        String cq = "[CQ:face,id=14][CQ:mface,summary=笑][CQ:at,qq=123][CQ:image,file=test.png]";
        assertEquals("[CQ:at,qq=123][CQ:image,file=test.png]",
                BaiLianService.sanitizePublicReply("发表情包", cq));
    }

    @Test
    void quotedBareAtCqIsNormalizedAndTimestampIsRemoved() {
        String reply = "[09-18 10:50] 哦——原来是让我对着你的待办列表看\n\"CQ:at,qq=3360575865 三千\"，这锅你自己背一下";

        assertEquals("哦——原来是让我对着你的待办列表看\n[CQ:at,qq=3360575865] 三千，这锅你自己背一下",
                BaiLianService.sanitizePublicReply("还差什么", reply));
    }

    @Test
    void unquotedBareAtCqIsNormalizedWithoutTouchingOtherCqTypes() {
        assertEquals("提醒 [CQ:at,qq=3360575865] 三千 [CQ:image,file=test.png]",
                BaiLianService.normalizeBareAtCqCodes(
                        "提醒 CQ:at,qq=3360575865 三千 [CQ:image,file=test.png]"));
        assertEquals("[CQ:at,qq=3360575865] 三千",
                BaiLianService.normalizeBareAtCqCodes("[CQ:at,qq=3360575865] 三千"));
    }

    @Test
    void emojiAreRemovedInsideSplitMessages() {
        assertEquals("<msg>行</msg><msg>知道了</msg>",
                BaiLianService.sanitizePublicReply("快点", "<msg>行👍</msg><msg>知道了✨</msg>"));
    }
}
