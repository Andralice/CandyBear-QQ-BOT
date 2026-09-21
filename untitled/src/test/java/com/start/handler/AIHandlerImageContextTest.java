package com.start.handler;

import com.start.service.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIHandlerImageContextTest {

    @Test
    void textImmediatelyAfterTwoImagesUsesOnlyTheLatestImageAsMainInput() {
        ConversationState state = new ConversationState("group", "user");
        state.addMessage("", "64660");
        state.addImageInfo("cat-url", "cat.jpg", "64660", 1, "user");
        state.addMessage("", "64661");
        state.addImageInfo("girl-url", "girl.jpg", "64661", 1, "user");
        state.addMessage("ai画的", "64662");

        Map<String, String> cat = image("64660", "黑色卡通猫，头顶问号");
        Map<String, String> girl = image("64661", "粉发少女手托腮");
        List<Map<String, String>> selected = AIHandler.selectPromptImages(state, List.of(cat, girl));

        assertEquals(List.of(girl), selected);
        assertTrue(AIHandler.buildImagePromptContext(state, List.of(cat, girl), "有描述")
                .contains("当前主图（消息ID=64661）"));
    }

    @Test
    void unrelatedLaterTextDoesNotGuessAnOlderImage() {
        ConversationState state = new ConversationState("group", "user");
        state.addMessage("", "64661");
        state.addImageInfo("girl-url", "girl.jpg", "64661", 1, "user");
        state.addMessage("换个话题", "64662");
        state.addMessage("今天吃什么", "64663");

        Map<String, String> girl = image("64661", "粉发少女手托腮");
        assertTrue(AIHandler.selectPromptImages(state, List.of(girl)).isEmpty());
        assertTrue(AIHandler.buildImagePromptContext(state, List.of(girl), "有描述")
                .contains("没有可确认的紧邻图片"));
    }

    @Test
    void selectedDataUrisStayAlignedWithSelectedImages() {
        Map<String, String> cat = image("64660", "猫");
        Map<String, String> girl = image("64661", "少女");
        assertEquals(List.of("girl-data"), AIHandler.selectPromptImageDataUris(
                List.of(cat, girl), List.of("cat-data", "girl-data"), List.of(girl)));
    }

    @Test
    void multipleImagesInOneMessageStayTogether() {
        ConversationState state = new ConversationState("group", "user");
        state.addMessage("看看这两张", "700");
        state.addImageInfo("one-url", "one.jpg", "700", 1, "user");
        state.addImageInfo("two-url", "two.jpg", "700", 2, "user");

        Map<String, String> one = image("700", "第一张");
        Map<String, String> two = image("700", "第二张");
        assertEquals(List.of(one, two), AIHandler.selectPromptImages(state, List.of(one, two)));
    }

    @Test
    void imageEvidenceKeepsIdentityButDoesNotInjectVisionDescription() {
        Map<String, String> image = image("800", "一只不应进入提示词的猫");
        image.put("image_index", "1");

        String evidence = AIHandler.buildImageEvidence(List.of(image));

        assertTrue(evidence.contains("消息ID=800"));
        assertTrue(evidence.contains("发送者=user"));
        assertTrue(evidence.contains("未自动读取画面内容"));
        assertTrue(!evidence.contains("猫"));
    }

    @Test
    void missingMessageIdsKeepLegacySameTurnImages() {
        ConversationState state = new ConversationState("group", "user");
        state.addMessage("看看", null);
        Map<String, String> one = image("", "第一张");
        Map<String, String> two = image("", "第二张");

        assertEquals(List.of(one, two), AIHandler.selectPromptImages(state, List.of(one, two)));
    }

    private static Map<String, String> image(String messageId, String description) {
        Map<String, String> image = new HashMap<>();
        image.put("message_id", messageId);
        image.put("sender_id", "user");
        image.put("description", description);
        return image;
    }
}
