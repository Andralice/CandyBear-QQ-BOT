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
    void videoReceiptGetsLongerUploadTimeout() {
        assertEquals(120, Main.receiptTimeoutSeconds("[CQ:video,file=/tmp/video.mp4]"));
        assertEquals(30, Main.receiptTimeoutSeconds("[CQ:image,file=/tmp/image.png]"));
        assertEquals(5, Main.receiptTimeoutSeconds("普通文本"));
    }
}
