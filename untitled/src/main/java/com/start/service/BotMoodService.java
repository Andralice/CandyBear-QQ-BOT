package com.start.service;

import com.start.model.GroupMood;
import com.start.repository.GroupMoodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 糖果熊分群情绪系统。
 * 每个群独立维护心情值 0-100，持久化到 group_mood 表。
 *
 * 升情绪：被 @、被友好称呼、群聊活跃、好感度高
 * 降情绪：被冷落、被怼、长时间没人理
 *
 * 情绪区间：0-25 低落 | 26-50 平静 | 51-75 开心 | 76-100 兴奋
 */
public class BotMoodService {
    private static final Logger logger = LoggerFactory.getLogger(BotMoodService.class);

    /** 默认心情值 */
    private static final int DEFAULT_MOOD = 50;

    /** 冷场阈值（秒） */
    private static final long COLD_THRESHOLD_SECONDS = 300;

    /** 抛话题冷却（秒） */
    private static final long TOPIC_COOLDOWN_SECONDS = 900;

    private final GroupMoodRepository repo;

    /** 内存缓存：groupId → 心情值 */
    private final Map<String, Integer> moodCache = new ConcurrentHashMap<>();

    /** 各群最后活跃时间 */
    private final Map<String, Long> groupLastMessageTime = new ConcurrentHashMap<>();

    /** 各群消息连续计数 */
    private final Map<String, Integer> groupMessageStreak = new ConcurrentHashMap<>();

    /** 各群抛话题冷却时间 */
    private final Map<String, Long> groupLastTopicThrowTime = new ConcurrentHashMap<>();

    public BotMoodService(GroupMoodRepository repo) {
        this.repo = repo;
    }

    // ===== 情绪调整 =====

    /** 正面互动 */
    public void onPositiveInteraction(String groupId) { adjustMood(groupId, +3); }

    /** 被 @ */
    public void onMentioned(String groupId) { adjustMood(groupId, +5); }

    /** 负面/冷淡消息 */
    public void onNegativeInteraction(String groupId) { adjustMood(groupId, -2); }

    /** 发了消息，微降 */
    public void onBotSpeak(String groupId) { adjustMood(groupId, -1); }

    /** 长时间没互动 */
    public void tickIdle(String groupId) { adjustMood(groupId, -1); }

    // ===== 群活跃追踪 =====

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
        Long lastThrow = groupLastTopicThrowTime.getOrDefault(groupId, 0L);
        if (now - lastThrow < TOPIC_COOLDOWN_SECONDS * 1000) return false;
        if (getMood(groupId) < 40) return false;
        groupLastTopicThrowTime.put(groupId, now);
        // 同步写入 DB
        try {
            GroupMood gm = loadFromDb(groupId);
            if (gm != null) {
                gm.setLastTopicThrowTime(now);
                repo.save(gm);
            }
        } catch (Exception e) {
            logger.warn("写入抛话题时间失败: {}", e.getMessage());
        }
        return true;
    }

    /** 生成话题抛出提示词 */
    public String getTopicThrowPrompt(String groupId) {
        groupMessageStreak.put(groupId, 0);
        String[] topics = {
            "群好像安静下来了，抛一个轻松的话题活跃气氛。25字以内。",
            "大家都在潜水，用关心的语气问问大家今天过得怎么样。",
            "聊一聊最近的热门番剧或游戏，看看群友有没有同好。",
            "分享一个有趣的小知识或冷知识。",
        };
        return topics[(int) (Math.random() * topics.length)];
    }

    // ===== 查询 =====

    /** 获取当前心情值 */
    public int getMood(String groupId) {
        Integer cached = moodCache.get(groupId);
        if (cached != null) return cached;
        // 从 DB 加载
        GroupMood gm = loadFromDb(groupId);
        int mood = (gm != null) ? gm.getMood() : DEFAULT_MOOD;
        moodCache.put(groupId, mood);
        return mood;
    }

    /** 获取当前情绪描述 */
    public String getMoodDescription(String groupId) {
        int mood = getMood(groupId);
        if (mood >= 76) return "心情超好，充满活力";
        if (mood >= 51) return "心情不错，乐于聊天";
        if (mood >= 26) return "心情平静，正常聊天";
        return "心情不太好，话少冷漠";
    }

    // ===== 内部 =====

    private void adjustMood(String groupId, int delta) {
        int current = getMood(groupId);
        int next = Math.max(0, Math.min(100, current + delta));
        moodCache.put(groupId, next);
        logger.debug("😊 群{} 糖果熊情绪: {} → {} ({})", groupId, current, next, describe(next));
        // 持久化
        persistMood(groupId, next);
    }

    private String describe(int mood) {
        if (mood >= 76) return "兴奋";
        if (mood >= 51) return "开心";
        if (mood >= 26) return "平静";
        return "低落";
    }

    private GroupMood loadFromDb(String groupId) {
        try {
            return repo.findByGroupId(groupId).orElse(null);
        } catch (Exception e) {
            logger.warn("加载群{}心情失败: {}", groupId, e.getMessage());
            return null;
        }
    }

    private void persistMood(String groupId, int mood) {
        try {
            GroupMood gm = loadFromDb(groupId);
            if (gm == null) {
                gm = new GroupMood();
                gm.setGroupId(groupId);
            }
            gm.setMood(mood);
            // 保留已有的 lastTopicThrowTime
            Long cachedThrowTime = groupLastTopicThrowTime.get(groupId);
            if (cachedThrowTime != null) {
                gm.setLastTopicThrowTime(cachedThrowTime);
            }
            repo.save(gm);
        } catch (Exception e) {
            logger.warn("持久化群{}心情失败: {}", groupId, e.getMessage());
        }
    }
}
