package com.start.agent;

import com.start.Main;
import com.start.service.StickerIngestService;
import com.start.service.StickerIngestService.StickerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 表情包发送工具 —— AI 在聊天中发送真实表情包/梗图。
 *
 * <p>数据源统一由 {@link StickerIngestService} 管理：自动入库的表情包 + 管理员私聊导入的 legacy
 * 表情包关键词映射；没有真实图片时不发送 QQ 内置表情。
 */
public class StickerTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(StickerTool.class);

    private final Main bot;
    /** 发送表情包不应占住群聊生成 worker；队列有界，满时如实返回失败。 */
    private static final ThreadPoolExecutor SEND_EXECUTOR = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "CandyBear-Sticker-Sender");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String, Long> lastSendTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 30_000;

    public StickerTool(Main bot) {
        this.bot = bot;
        try {
            int count = StickerIngestService.getInstance().getAllStickers().size();
            logger.info("StickerTool 初始化：当前 sticker 库 {} 条", count);
        } catch (Exception e) {
            logger.warn("StickerIngestService 未初始化，sticker 功能暂不可用: {}", e.getMessage());
        }
    }

    @Override
    public String getName() { return "send_sticker"; }

    @Override
    public String getDescription() {
        return "用真实表情包/梗图回应群友/私聊好友，是你日常聊天的一部分。\n" +
                "触发场景：\n" +
                "- 对方表达明显情绪（开心/难过/无语/愤怒/惊讶等）→ 发匹配情绪的图\n" +
                "- 对方发了图/梗 → 跟图\n" +
                "- 群内冷场 30s+ → 主动发图活跃气氛\n" +
                "- 私聊时对方情绪到位 → 也可以发图\n" +
                "- 用户直接要图（『发个表情包』『来张图』）→ 立刻发\n" +
                "- 别人感谢/夸你/示好 → 可以发比心/害羞\n" +
                "keywords 选情绪标签（开心/无语/哭/加油/得意/嘲讽/震惊/比心 等），不传则随机。\n" +
                "参数：group_id（群时填，私聊不填） OR user_id（私聊时填，群不填）—— 二选一必填。\n" +
                "冷却 30 秒（私聊 30 秒/用户，群 30 秒/群）防刷屏。\n" +
                "别等用户要才发 —— 看到情绪就发，是日常社交的一部分。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "目标群号（群聊必填，私聊不填）"),
                        "user_id", Map.of("type", "string", "description", "目标用户 QQ（私聊必填，群聊不填）"),
                        "keywords", Map.of("type", "string", "description", "表情包类型，如：开心、无语、哭、安慰、加油、赞等。不传则随机")
                ),
                "required", List.of());  // 二选一必填在 execute() 里校验
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String userId = (String) args.get("user_id");
        boolean isGroup = groupId != null && !groupId.isBlank();
        boolean isPrivate = userId != null && !userId.isBlank();
        if (!isGroup && !isPrivate) return "缺少 group_id 或 user_id（必填其一）";

        String cooldownKey = isGroup ? "g:" + groupId : "p:" + userId;

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = lastSendTime.get(cooldownKey);
        if (last != null && now - last < COOLDOWN_MS) {
            return "表情包冷却中，稍后再发";
        }
        String keywords = (String) args.get("keywords");

        try {
            StickerRecord selected = selectSticker(keywords);
            if (selected == null) return "没找到合适的表情包";

            String cqCode;
            if (selected.file != null && !selected.file.isBlank()) {
                // 从 StickerIngestService 读文件
                byte[] bytes = StickerIngestService.getInstance().readStickerBytes(selected);
                if (bytes != null && bytes.length > 0) {
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    cqCode = "[CQ:image,file=base64://" + b64 + "]";
                } else {
                    return "表情包读取失败，暂时没有可发送的图片";
                }
            } else {
                return "没找到可发送的真实表情包";
            }

            if (cqCode == null || cqCode.isBlank()) {
                return "表情包发送失败";
            }

            long targetId = Long.parseLong(isGroup ? groupId : userId);
            long scheduledAt = System.currentTimeMillis();
            final boolean[] coolingDown = {false};
            lastSendTime.compute(cooldownKey, (key, previous) -> {
                if (previous != null && scheduledAt - previous < COOLDOWN_MS) {
                    coolingDown[0] = true;
                    return previous;
                }
                return scheduledAt;
            });
            if (coolingDown[0]) return "表情包冷却中，稍后再发";

            try {
                SEND_EXECUTOR.execute(() -> {
                    try {
                        boolean sent = isGroup
                                ? bot.sendGroupReply(targetId, cqCode)
                                : bot.sendPrivateReply(targetId, cqCode);
                        if (!sent) logger.warn("表情包发送未确认送达 target={} scope={}", targetId, cooldownKey);
                    } catch (Exception sendError) {
                        logger.warn("表情包后台发送失败 target={} scope={}", targetId, cooldownKey, sendError);
                    }
                });
            } catch (RejectedExecutionException e) {
                // 没有进入发送队列就不应消耗冷却时间。
                lastSendTime.remove(cooldownKey, scheduledAt);
                logger.warn("表情包发送队列已满 scope={}", cooldownKey);
                return "暂时不可用：表情包发送队列已满，请稍后再发";
            }
            return "已安排表情包，正在发送: " + String.join(",", selected.keywords);
        } catch (Exception e) {
            logger.warn("发送表情包失败", e);
            return "表情包发送失败: " + e.getMessage();
        }
    }

    private StickerRecord selectSticker(String keywords) {
        List<StickerRecord> all;
        try {
            all = StickerIngestService.getInstance().getAllStickers();
        } catch (Exception e) {
            return null;
        }
        // 只选有真实图片的记录；旧的无图记录不参与发送。
        List<StickerRecord> realStickers = new ArrayList<>();
        for (StickerRecord r : all) {
            if (r.file != null && !r.file.isBlank()) {
                realStickers.add(r);
            }
        }
        if (realStickers.isEmpty()) {
            return null;
        }
        if (keywords == null || keywords.isBlank()) {
            return realStickers.get(ThreadLocalRandom.current().nextInt(realStickers.size()));
        }
        // 关键词匹配打分
        return selectScoredSticker(realStickers, keywords);
    }

    /** 使用稳定顺序完成评分/排序，再从最高分前三名中随机选择。 */
    static StickerRecord selectScoredSticker(List<StickerRecord> stickers, String keywords) {
        List<ScoredSticker> scored = new ArrayList<>(stickers.size());
        for (StickerRecord sticker : stickers) {
            scored.add(new ScoredSticker(sticker, matchScore(sticker, keywords)));
        }
        scored.sort(Comparator.comparingInt(ScoredSticker::score)
                .reversed()
                .thenComparing(s -> stableStickerKey(s.sticker())));

        // 关键词完全没有命中时，不要从前三张真图中随机发一张无关图片。
        if (scored.isEmpty() || scored.get(0).score() <= 0) return null;

        int limit = Math.min(3, scored.size());
        return scored.get(ThreadLocalRandom.current().nextInt(limit)).sticker();
    }

    private static int matchScore(StickerRecord entry, String input) {
        int score = 0;
        String lower = input.toLowerCase(Locale.ROOT);
        // 优先匹配管理员修正后的 keywords，回退到 LLM 出的 auto_keywords
        for (String kw : entry.keywords) {
            if (lower.contains(kw)) score += 10;
            if (kw.toLowerCase(Locale.ROOT).contains(lower)) score += 5;
        }
        for (String kw : entry.autoKeywords) {
            if (lower.contains(kw)) score += 4;
            if (kw.toLowerCase(Locale.ROOT).contains(lower)) score += 2;
        }
        return score;
    }

    private static String stableStickerKey(StickerRecord sticker) {
        if (sticker == null) return "";
        if (sticker.id != null && !sticker.id.isBlank()) return sticker.id;
        return sticker.file != null ? sticker.file : "";
    }

    private record ScoredSticker(StickerRecord sticker, int score) {}

}
