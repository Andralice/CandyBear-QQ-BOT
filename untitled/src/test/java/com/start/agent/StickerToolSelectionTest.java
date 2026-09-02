package com.start.agent;

import com.start.service.StickerIngestService.StickerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StickerToolSelectionTest {

    @Test
    void equalScoresDoNotBreakSortingContract() {
        List<StickerRecord> stickers = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            StickerRecord sticker = new StickerRecord();
            sticker.id = "sticker-" + i;
            sticker.file = "sticker-" + i + ".jpg";
            sticker.keywords = new ArrayList<>(List.of("安慰"));
            stickers.add(sticker);
        }

        for (int i = 0; i < 100; i++) {
            assertNotNull(StickerTool.selectScoredSticker(stickers, "安慰"));
        }
    }

    @Test
    void noKeywordMatchDoesNotSelectUnrelatedSticker() {
        StickerRecord sticker = new StickerRecord();
        sticker.id = "comfort";
        sticker.file = "comfort.jpg";
        sticker.keywords = new ArrayList<>(List.of("安慰"));

        assertNull(StickerTool.selectScoredSticker(List.of(sticker), "不存在的关键词"));
    }
}
