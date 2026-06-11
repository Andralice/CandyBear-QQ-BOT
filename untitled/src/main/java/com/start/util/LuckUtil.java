package com.start.util;

import java.time.LocalDate;
import java.util.*;

/**
 * 幸运值 + 今日魔咒。同一天同一用户结果固定。
 */
public class LuckUtil {

    public static int getDailyLuck(long userId) {
        long seed = SeedUtil.seed(String.valueOf(userId), "luck", LocalDate.now().toString());
        return new Random(seed).nextInt(101);
    }

    /** 获取今日魔咒：宜做 + 忌做的事 */
    public static DailySpell getDailySpell(long userId) {
        int luck = getDailyLuck(userId);
        long seed = SeedUtil.seed(String.valueOf(userId), "spell", LocalDate.now().toString());
        Random rng = new Random(seed);

        String[] doList = {"摸鱼", "表白", "氪金", "吃火锅", "睡懒觉", "打游戏",
                "出门散步", "看书", "听歌", "喝奶茶", "追番", "写代码",
                "和朋友聊天", "收拾房间", "买彩票", "锻炼身体", "拍照", "做饭"};
        String[] avoidList = {"吵架", "熬夜", "冲动消费", "吃辣", "立flag",
                "打排位", "相亲", "借钱", "减肥", "刷剧到天亮", "怼老板"};

        String doIt = doList[rng.nextInt(doList.length)];
        String avoid = avoidList[rng.nextInt(avoidList.length)];

        String mood;
        if (luck >= 80) mood = "🌟 运势爆棚，做什么都顺";
        else if (luck >= 60) mood = "😊 运气不错，积极乐观";
        else if (luck >= 40) mood = "🙂 平平淡淡，稳定发挥";
        else if (luck >= 20) mood = "😐 运势低迷，小心为上";
        else mood = "💀 诸事不宜，躺平保平安";

        return new DailySpell(luck, "宜" + doIt, "忌" + avoid, mood);
    }

    public record DailySpell(int luck, String doSpell, String avoidSpell, String mood) {}
}
