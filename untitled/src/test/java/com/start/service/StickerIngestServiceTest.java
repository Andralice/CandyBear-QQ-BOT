package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StickerIngestServiceTest {

    @Test
    void keepsOnlyEmotionKeywordsForFutureStickerSearch() {
        assertEquals(List.of("害羞", "尴尬"), StickerIngestService.normalizeStickerKeywords(
                List.of("百合", "动漫", "R-18", "害羞", "尴尬")));
    }

    @Test
    void rejectsContentOnlyKeywords() {
        assertEquals(List.of(), StickerIngestService.normalizeStickerKeywords(
                List.of("猫", "白发", "小说详情页", "R-18")));
    }
}
