package com.start;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainOutgoingMessageSanitizationTest {
    @Test
    void filtersEmojiAndFaceButPreservesAllowedCqSegments() {
        assertEquals("好呀",
                Main.sanitizeOutgoingMessage("好😂[CQ:face,id=14]呀 QAQ"));
        assertEquals("好呀",
                Main.sanitizeOutgoingMessage("好[CQ:mface,summary=笑]呀"));
        assertEquals("[CQ:image,file=熊😂.png]",
                Main.sanitizeOutgoingMessage("[CQ:image,file=熊😂.png]"));
        assertEquals("[CQ:at,qq=123]收到",
                Main.sanitizeOutgoingMessage("[CQ:at,qq=123]收到"));
        assertEquals("", Main.sanitizeOutgoingMessage("🥺"));
    }

    @Test
    void normalizesBareAtCqBeforeSending() {
        assertEquals("[CQ:at,qq=3360575865] 三千",
                Main.sanitizeOutgoingMessage("\"CQ:at,qq=3360575865 三千\""));
    }

    @Test
    void removesBlankLinesButKeepsSingleLineBreaks() {
        assertEquals("第一段\n第二段\n第三段",
                Main.sanitizeOutgoingMessage("第一段\r\n \t\r\n\r\n第二段\n第三段\n\n"));
    }

    @Test
    void videoReceiptGetsLongerUploadTimeout() {
        assertEquals(120, Main.receiptTimeoutSeconds("[CQ:video,file=/tmp/video.mp4]"));
        assertEquals(30, Main.receiptTimeoutSeconds("[CQ:image,file=/tmp/image.png]"));
        assertEquals(5, Main.receiptTimeoutSeconds("普通文本"));
    }

    @Test
    void encodesReplyAsOneBotMessageSegment() {
        assertEquals("[CQ:reply,id=1627481010]收到",
                Main.withReplyReference("收到", 1627481010L));
        assertEquals("收到", Main.withReplyReference("收到", null));
        assertEquals("收到", Main.withReplyReference("收到", 0L));
    }
}
