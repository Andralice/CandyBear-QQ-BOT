package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏状态管理。代码层跟踪，注入提示词，AI 不用靠记忆。
 */
public class GameStateService {
    private static final Logger logger = LoggerFactory.getLogger(GameStateService.class);

    public enum GameType { GUESS_NUMBER, IDIOM_CHAIN, SPY }
    public enum SpyPhase { REGISTERING, STARTED, VOTING }

    private final Map<String, SpyGame> spyGames = new ConcurrentHashMap<>();
    private final Map<String, NumberGame> numberGames = new ConcurrentHashMap<>();

    // ========== 谁是卧底 ==========

    public SpyGame getOrCreateSpy(String groupId) {
        return spyGames.computeIfAbsent(groupId, k -> new SpyGame());
    }

    public void endSpy(String groupId) {
        spyGames.remove(groupId);
        logger.info("🎮 谁是卧底结束: group={}", groupId);
    }

    public static class SpyGame {
        public SpyPhase phase = SpyPhase.REGISTERING;
        public final Set<String> players = new LinkedHashSet<>();  // 报名玩家 QQ
        public final Map<String, String> words = new HashMap<>();   // QQ → 词
        public final Set<String> sentWords = new HashSet<>();       // 已发词的 QQ
        public String spyUserId;                                    // 卧底 QQ
        public String civilianWord;
        public String spyWord;
        public final Set<String> alive = new HashSet<>();           // 存活玩家
        public int round = 0;
        public long lastActivity = System.currentTimeMillis();

        public String getDescription() {
            if (players.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n【🎮 谁是卧底进行中】");
            sb.append("\n阶段：").append(phase == SpyPhase.REGISTERING ? "报名中（凑够3人自动开始）"
                    : phase == SpyPhase.STARTED ? "描述中（每人一句话）" : "投票中");
            sb.append("\n已报名(").append(players.size()).append("人)：");
            for (String p : players) {
                sb.append(p);
                if (sentWords.contains(p)) sb.append("(词已发)");
                sb.append(" ");
            }
            if (spyUserId != null) {
                sb.append("\n卧底：").append(spyUserId);
                sb.append(" | 平民词：").append(civilianWord).append(" | 卧底词：").append(spyWord);
            }
            if (!alive.isEmpty()) {
                sb.append("\n存活：").append(String.join(" ", alive));
            }
            sb.append("\n你的任务：严格按照游戏流程操作。当前阶段是【").append(phase).append("】。");
            return sb.toString();
        }
    }

    // ========== 猜数字 ==========

    public NumberGame getOrCreateNumber(String groupId) {
        return numberGames.computeIfAbsent(groupId, k -> new NumberGame());
    }

    public void endNumber(String groupId) {
        numberGames.remove(groupId);
    }

    public static class NumberGame {
        public int target = -1;
        public int min = 1, max = 100;
        public long lastGuess = System.currentTimeMillis();

        public String getDescription() {
            if (target < 0) return "";
            return "\n【🔢 猜数字进行中】范围：" + min + "-" + max + "，答案：" + target;
        }
    }
}
