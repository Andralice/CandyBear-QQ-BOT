package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 糖果熊情绪系统。
 * 情绪值 0-100，影响回复语气和主动性。
 *
 * 升情绪：被 @、被友好称呼、群聊活跃、好感度高
 * 降情绪：被冷落、被怼、长时间没人理
 *
 * 情绪区间：0-25 低落 | 26-50 平静 | 51-75 开心 | 76-100 兴奋
 */
public class BotMoodService {
    private static final Logger logger = LoggerFactory.getLogger(BotMoodService.class);

    private int mood = 50; // 默认平静
    private final Map<String, Long> groupLastMessageTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> groupMessageStreak = new ConcurrentHashMap<>();

    // 冷场阈值（秒）
    private static final long COLD_THRESHOLD_SECONDS = 300; // 5分钟
    // 冷却时间（秒）
    private long lastTopicThrowTime = 0;
    private static final long TOPIC_COOLDOWN_SECONDS = 900; // 15分钟

    /** 收到正面互动，升情绪 */
    public void onPositiveInteraction() { adjustMood(+3); }

    /** 被 @ 了，显著升情绪 */
    public void onMentioned() { adjustMood(+5); }

    /** 收到负面/冷淡消息，降情绪 */
    public void onNegativeInteraction() { adjustMood(-2); }

    /** 发了消息，微降（消耗能量） */
    public void onBotSpeak() { adjustMood(-1); }

    /** 长时间没互动，自然衰减 */
    public void tickIdle() { adjustMood(-1); }

    /** 记录群最后活跃时间 */
    public void recordGroupActivity(String groupId) {
        groupLastMessageTime.put(groupId, System.currentTimeMillis());
        groupMessageStreak.merge(groupId, 1, Integer::sum);
    }

    /** 检查群是否冷场 */
    public boolean isGroupCold(String groupId) {
        Long last = groupLastMessageTime.get(groupId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) / 1000 > COLD_THRESHOLD_SECONDS;
    }

    /** 是否应该主动抛话题 */
    public boolean shouldThrowTopic(String groupId) {
        if (!isGroupCold(groupId)) return false;
        long now = System.currentTimeMillis();
        if (now - lastTopicThrowTime < TOPIC_COOLDOWN_SECONDS * 1000) return false;
        // 情绪高涨才主动，低落时懒得说话
        if (mood < 40) return false;
        lastTopicThrowTime = now;
        return true;
    }

    /** 生成话题抛出提示词 */
    public String getTopicThrowPrompt(String groupId) {
        groupMessageStreak.put(groupId, 0); // 重置计数
        String[] topics = {
            "群好像安静下来了，抛一个轻松的话题活跃气氛。25字以内。",
            "大家都在潜水，用关心的语气问问大家今天过得怎么样。",
            "聊一聊最近的热门番剧或游戏，看看群友有没有同好。",
            "分享一个有趣的小知识或冷知识。",
        };
        return topics[(int) (Math.random() * topics.length)];
    }

    /** 获取当前情绪描述 */
    public String getMoodDescription() {
        if (mood >= 76) return "心情超好，充满活力";
        if (mood >= 51) return "心情不错，乐于聊天";
        if (mood >= 26) return "心情平静，正常聊天";
        return "心情不太好，话少冷漠";
    }

    /** 获取情绪值 */
    public int getMood() { return mood; }

    private void adjustMood(int delta) {
        mood = Math.max(0, Math.min(100, mood + delta));
        logger.debug("😊 糖果熊情绪: {} ({})", mood, getMoodDescription());
    }
}
