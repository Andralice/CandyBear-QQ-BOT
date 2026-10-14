package com.start.handler;

import com.start.service.ConversationState;
import com.start.service.BaiLianService;
import com.start.runtime.conversation.ConversationSession;
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
    void independentSearchResultsRemainBoundToTheirOwnImageMessage() {
        Map<String, String> first = image("901", "");
        first.put("image_index", "1");
        Map<String, String> second = image("902", "");
        second.put("image_index", "2");

        String evidence = AIHandler.buildImageSearchEvidence(List.of(first, second), List.of(
                new BaiLianService.ImageSearchEvidence(2, "第二张识别为星露谷", List.of("星露谷截图"), List.of()),
                new BaiLianService.ImageSearchEvidence(1, "第一张识别为游戏地图", List.of("游戏地图"), List.of())));

        assertTrue(evidence.contains("图片1（消息ID=901，消息内第1张）：识别结论：第一张识别为游戏地图"));
        assertTrue(evidence.contains("图片2（消息ID=902，消息内第2张）：识别结论：第二张识别为星露谷"));
        assertTrue(first.get("web_search_evidence").contains("第一张识别为游戏地图"));
        assertTrue(second.get("web_search_evidence").contains("第二张识别为星露谷"));
        assertTrue(!first.get("web_search_evidence").contains("第二张"));
        assertTrue(!second.get("web_search_evidence").contains("第一张"));
    }

    @Test
    void visionFallbackSelectsOnlyImagesWithoutSearchEvidence() {
        assertEquals(List.of(2), AIHandler.findUnverifiedImageIndexes(3, List.of(
                new BaiLianService.ImageSearchEvidence(1, "第一张已核验", List.of(), List.of()),
                new BaiLianService.ImageSearchEvidence(3, "第三张已核验", List.of(), List.of()))));
        assertEquals(List.of(1, 2), AIHandler.findUnverifiedImageIndexes(2, List.of()));
    }

    @Test
    void cachedSearchEvidenceIsReusedPerImageWithoutCrossingImagesInOneMessage() {
        Map<String, String> first = image("901", "");
        first.put("image_index", "1");
        Map<String, String> second = image("901", "");
        second.put("image_index", "2");
        Map<String, String> third = image("901", "");
        third.put("image_index", "3");
        BaiLianService.PublicMessage cached = new BaiLianService.PublicMessage(
                "901", "user", "群友", "");
        cached.imageDescription = BaiLianService.CACHED_IMAGE_WEB_EVIDENCE_PREFIX
                + "{\"1\":\"第一张搜索证据\",\"2\":\"第二张搜索证据\"}";

        AIHandler.hydrateCachedImageSearchEvidence(List.of(first, second, third), List.of(cached));

        assertEquals("第一张搜索证据", first.get("web_search_evidence"));
        assertEquals("第二张搜索证据", second.get("web_search_evidence"));
        assertTrue(third.getOrDefault("web_search_evidence", "").isBlank());
        List<ConversationSession.SearchImage> searchable = AIHandler.buildSearchImageInputs(
                List.of(first, second, third), List.of("first-image", "second-image", "third-image"));
        assertEquals(1, searchable.size());
        assertEquals("third-image", searchable.get(0).dataUri());
        assertEquals(3, searchable.get(0).selectionIndex());
        assertEquals(3, searchable.get(0).messageImageIndex());
        assertTrue(AIHandler.buildSearchImageInputs(List.of(third), List.of(" ")).isEmpty());
        String evidence = AIHandler.buildImageSearchEvidence(List.of(first, second, third), List.of());
        assertTrue(evidence.contains("图片1（消息ID=901，消息内第1张）：第一张搜索证据"));
        assertTrue(evidence.contains("图片2（消息ID=901，消息内第2张）：第二张搜索证据"));
        assertTrue(!evidence.contains("图片3（"));
    }

    @Test
    void ordinaryVisionDescriptionIsNotTreatedAsCachedWebVerification() {
        Map<String, String> image = image("902", "");
        image.put("image_index", "1");
        BaiLianService.PublicMessage visionOnly = new BaiLianService.PublicMessage(
                "902", "user", "群友", "");
        visionOnly.imageDescription = "图片里有一只猫";

        AIHandler.hydrateCachedImageSearchEvidence(List.of(image), List.of(visionOnly));

        assertEquals(List.of(new ConversationSession.SearchImage("image-data", "902", 1, 1)),
                AIHandler.buildSearchImageInputs(List.of(image), List.of("image-data")));
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
