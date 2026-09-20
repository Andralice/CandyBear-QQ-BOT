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
    void longReplyWithNaturalSentenceBreaksUsesTwoBeats() {
        String reply = "我先把这件事记下来了，刚才那段确实没接好。"
                + "后面你再补一句具体内容，我就能接着往下处理，不用重新说一遍，也别把整件事重新展开。";

        assertEquals(List.of(
                        "我先把这件事记下来了，刚才那段确实没接好。",
                        "后面你再补一句具体内容，我就能接着往下处理，不用重新说一遍，也别把整件事重新展开。"),
                BaiLianService.splitIntoShortMessages(reply));
    }

    @Test
    void shortContrastiveReplyUsesTwoBeats() {
        String reply = "被你说中了，我确实是。不过刚才那段是真话，没编。";

        assertEquals(List.of("被你说中了，我确实是。", "不过刚才那段是真话，没编。"),
                BaiLianService.splitIntoShortMessages(reply));
    }

    @Test
    void noReplyMarkerNeverBecomesPublicMessage() {
        assertEquals(List.of(), BaiLianService.splitIntoShortMessages("<NO_REPLY>"));
        assertEquals(List.of(), BaiLianService.splitIntoShortMessages("<NO_REPLY> 还有一句"));
    }
}
