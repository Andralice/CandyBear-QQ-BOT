package com.start.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LuckUtil 单元测试 — 确定性随机，同一用户同一天结果固定。
 */
class LuckUtilTest {

    @Test
    void sameUserSameDayReturnsSameResult() {
        int luck1 = LuckUtil.getDailyLuck(12345L);
        int luck2 = LuckUtil.getDailyLuck(12345L);
        assertEquals(luck1, luck2);
    }

    @Test
    void differentUsersMayDiffer() {
        int luck1 = LuckUtil.getDailyLuck(10001L);
        int luck2 = LuckUtil.getDailyLuck(10002L);
        // 两个不同用户同一天极大概率不同（概率 > 99%）
        // 不做严格断言，仅验证返回值在有效范围
        assertTrue(luck1 >= 0 && luck1 <= 100);
        assertTrue(luck2 >= 0 && luck2 <= 100);
    }

    @Test
    void luckInRange() {
        // 多次验证确保幸运值始终在 0-100
        for (int i = 0; i < 100; i++) {
            int luck = LuckUtil.getDailyLuck(i * 100L);
            assertTrue(luck >= 0 && luck <= 100,
                    "Luck should be 0-100, got " + luck + " for user " + i);
        }
    }

    @Test
    void dailySpellNotNull() {
        LuckUtil.DailySpell spell = LuckUtil.getDailySpell(12345L);
        assertNotNull(spell);
        assertTrue(spell.luck() >= 0 && spell.luck() <= 100);
        assertNotNull(spell.doSpell());
        assertNotNull(spell.avoidSpell());
        assertNotNull(spell.mood());
    }

    @Test
    void dailySpellMoodMatchesLuckRange() {
        // 测试同一个用户100次（但同一天结果固定，所以只测一次）
        LuckUtil.DailySpell spell = LuckUtil.getDailySpell(55555L);
        if (spell.luck() >= 80) {
            assertTrue(spell.mood().contains("爆棚"));
        } else if (spell.luck() >= 60) {
            assertTrue(spell.mood().contains("不错"));
        } else if (spell.luck() >= 40) {
            assertTrue(spell.mood().contains("平平"));
        } else if (spell.luck() >= 20) {
            assertTrue(spell.mood().contains("低迷"));
        } else {
            assertTrue(spell.mood().contains("不宜"));
        }
    }

    @Test
    void spellHasCorrectPrefixes() {
        LuckUtil.DailySpell spell = LuckUtil.getDailySpell(77777L);
        assertTrue(spell.doSpell().startsWith("宜"));
        assertTrue(spell.avoidSpell().startsWith("忌"));
    }
}
