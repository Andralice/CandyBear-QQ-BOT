package com.start.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaiLianServiceReplySplittingTest {

    @Test
    void ordinaryParagraphsStayInOneMessage() {
        String reply = "我是 Claude。\n\n你继续问具体区别，我再慢慢说。";

        assertEquals(List.of(reply), BaiLianService.splitIntoShortMessages(reply));
    }

    @Test
    void explicitMessageTagsStillCreateSeparateMessages() {
        assertEquals(List.of("第一条", "第二条"),
                BaiLianService.splitIntoShortMessages("<msg>第一条</msg><msg>第二条</msg>"));
    }

    @Test
    void mediumReplyWithNaturalSentenceBreaksStaysTogether() {
        String reply = "我先把这件事记下来了，刚才那段确实没接好。"
                + "后面你再补一句具体内容，我就能接着往下处理，不用重新说一遍，也别把整件事重新展开。"
                + "如果还有新的事实或执行结果，我会按顺序说清楚，已经说过的部分就不再重复了。";

        assertEquals(List.of(reply),
                BaiLianService.splitIntoShortMessages(reply));
    }

    @Test
    void shortContrastiveReplyStaysTogetherWithoutExplicitProtocol() {
        String reply = "被你说中了，我确实是。不过刚才那段是真话，没编。";

        assertEquals(List.of(reply),
                BaiLianService.splitIntoShortMessages(reply));
    }

    @Test
    void noReplyMarkerNeverBecomesPublicMessage() {
        assertEquals(List.of(), BaiLianService.splitIntoShortMessages("<NO_REPLY>"));
        assertEquals(List.of(), BaiLianService.splitIntoShortMessages("<NO_REPLY> 还有一句"));
    }

    @Test
    void explicitModelBeatsAreCappedAtTwoWithoutDroppingTail() {
        assertEquals(List.of("第一条", "第二条\n第三条"),
                BaiLianService.splitIntoShortMessages(
                        "<msg>第一条</msg><msg>第二条</msg><msg>第三条</msg>"));
    }

    @Test
    void longAutomaticSplitIsAlsoCappedAtTwo() {
        String reply = "第一句说明当前结果。第二句补充必要事实。第三句又补了一点。"
                + "第四句继续展开。第五句继续展开。第六句继续展开。第七句继续展开。"
                + "第八句继续展开。第九句继续展开。第十句继续展开。第十一句继续展开。"
                + "第十二句继续展开。第十三句继续展开。第十四句继续展开。第十五句继续展开。"
                + "第十六句继续展开。第十七句继续展开。第十八句继续展开。";
        assertEquals(2, BaiLianService.splitIntoShortMessages(reply).size());
    }
}
