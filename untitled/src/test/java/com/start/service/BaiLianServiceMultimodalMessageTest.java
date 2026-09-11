package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaiLianServiceMultimodalMessageTest {

    @Test
    void keepsLegacyTextShapeWithoutImages() {
        Map<String, Object> message = BaiLianService.buildUserMessage("看一下", List.of());

        assertEquals("user", message.get("role"));
        assertEquals("看一下", message.get("content"));
    }

    @Test
    void doesNotSendRawImagesToMainChat() {
        Map<String, Object> message = BaiLianService.buildUserMessage(
                "看一下", List.of("data:image/png;base64,abc"));

        assertEquals("看一下", message.get("content"));
    }
}
