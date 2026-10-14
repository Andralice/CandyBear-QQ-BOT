package com.start.service;

import com.start.config.BotConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiLianServiceMultimodalMessageTest {

    @Test
    void keepsLegacyTextShapeWithoutImages() {
        Map<String, Object> message = BaiLianService.buildUserMessage("看一下", List.of());

        assertEquals("user", message.get("role"));
        assertEquals("看一下", message.get("content"));
    }

    @Test
    void doesNotSendRawImagesWhenMultimodalIsDisabled() throws Exception {
        Field flag = BotConfig.class.getDeclaredField("baiLianMultimodalEnabled");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        try {
            flag.setBoolean(null, false);
            Map<String, Object> message = BaiLianService.buildUserMessage(
                    "看一下", List.of("data:image/png;base64,abc"));

            assertEquals("看一下", message.get("content"));
        } finally {
            flag.setBoolean(null, previous);
        }
    }

    @Test
    void convertsGifBeforeBuildingEnabledMultimodalMessage() throws Exception {
        Field flag = BotConfig.class.getDeclaredField("baiLianMultimodalEnabled");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        try {
            flag.setBoolean(null, true);
            String gifUri = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

            Map<String, Object> message = BaiLianService.buildUserMessage(
                    "看一下", List.of(gifUri), LlmProtocol.Type.OPENAI);

            assertEquals("user", message.get("role"));
            assertTrue(message.get("content") instanceof List<?>);
            List<?> content = (List<?>) message.get("content");
            assertEquals("text", ((Map<?, ?>) content.get(0)).get("type"));
            assertEquals("image_url", ((Map<?, ?>) content.get(1)).get("type"));
            Map<?, ?> imageUrl = (Map<?, ?>) ((Map<?, ?>) content.get(1)).get("image_url");
            assertTrue(((String) imageUrl.get("url")).startsWith("data:image/jpeg;base64,"));
        } finally {
            flag.setBoolean(null, previous);
        }
    }

    @Test
    void sendsCurrentPngAsAnOpenAiImageContentBlockWhenEnabled() throws Exception {
        Field flag = BotConfig.class.getDeclaredField("baiLianMultimodalEnabled");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        try {
            flag.setBoolean(null, true);
            Map<String, Object> message = BaiLianService.buildUserMessage(
                    "这张图里是谁", List.of("data:image/png;base64,AQID"), LlmProtocol.Type.OPENAI);

            assertEquals("user", message.get("role"));
            assertTrue(message.get("content") instanceof List<?>);
            List<?> content = (List<?>) message.get("content");
            assertEquals("text", ((Map<?, ?>) content.get(0)).get("type"));
            assertEquals("image_url", ((Map<?, ?>) content.get(1)).get("type"));
            assertEquals("data:image/png;base64,AQID",
                    ((Map<?, ?>) ((Map<?, ?>) content.get(1)).get("image_url")).get("url"));
        } finally {
            flag.setBoolean(null, previous);
        }
    }

}
