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
}
