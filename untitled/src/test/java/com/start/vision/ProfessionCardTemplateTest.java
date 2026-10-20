package com.start.vision;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProfessionCardTemplateTest {

    @Test
    void rendersLandscapeCardWithBundledRankSeals() {
        String[] seals = {
                "assets/rank-seals/rank-1-common-silver.png",
                "assets/rank-seals/rank-2-common-jade.png",
                "assets/rank-seals/rank-3-rare-sapphire.png",
                "assets/rank-seals/rank-4-epic-amethyst.png",
                "assets/rank-seals/rank-5-legendary-gold.png"
        };
        for (String seal : seals) {
            assertNotNull(getClass().getClassLoader().getResource(seal), seal);
        }

        ProfessionData data = new ProfessionData(
                "123456789", "青霄剑使", 4, "四阶·炉火纯青",
                "剑气凌霄，青云照影。", "史诗", 4680);
        data.professionPath = "剑";
        data.groupRank = 2;
        data.bestTier = 5;
        data.bestTierName = "登峰造极";
        data.changeDesc = "修为精进";

        BufferedImage image = new ProfessionCardTemplate().render(data);
        assertEquals(1920, image.getWidth());
        assertEquals(1080, image.getHeight());
        assertFalse(image.getColorModel().hasAlpha());
        assertEquals(new Color(0x171A18).getRGB(), image.getRGB(10, 100));
    }
}
