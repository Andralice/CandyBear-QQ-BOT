package com.start.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 表情包工具 —— AI 在聊天中发送表情包/梗图/QQ 表情
 */
public class StickerTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(StickerTool.class);

    private final Main bot;
    private final List<StickerEntry> stickers;
    private final Map<String, Long> lastSendTime = new HashMap<>();
    private static final long COOLDOWN_MS = 30_000;

    // QQ 内置表情 face id 映射（文件名空时的回退）
    private static final Map<String, Integer> FACE_FALLBACK = Map.ofEntries(
            Map.entry("开心", 14), Map.entry("笑", 14), Map.entry("哈哈", 14),
            Map.entry("无语", 11), Map.entry("尴尬", 11),
            Map.entry("哭", 6), Map.entry("难过", 6),
            Map.entry("生气", 12), Map.entry("怒", 12),
            Map.entry("惊讶", 1), Map.entry("震惊", 1),
            Map.entry("害羞", 7),
            Map.entry("赞", 76), Map.entry("厉害", 76), Map.entry("牛", 76),
            Map.entry("爱", 66), Map.entry("喜欢", 66),
            Map.entry("白眼", 22), Map.entry("嫌弃", 22)
    );

    public StickerTool(Main bot) {
        this.bot = bot;
        this.stickers = loadMetadata();
        logger.info("加载了 {} 条表情包元数据", stickers.size());
    }

    @Override
    public String getName() { return "send_sticker"; }

    @Override
    public String getDescription() {
        return "在群里发一张表情包/QQ表情/梗图。" +
                "当聊天氛围适合发图、用户说『发个表情包』『来张图』、或者你想用图表达情绪时调用。" +
                "keywords 参数描述你想要的情绪类型（如 开心/无语/安慰/加油 等），不传则随机发。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "目标群号"),
                        "keywords", Map.of("type", "string", "description", "表情包类型，如：开心、无语、哭、安慰、加油、赞等。不传则随机")
                ),
                "required", List.of("group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        if (groupId == null || groupId.isBlank()) return "缺少 group_id";

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = lastSendTime.get(groupId);
        if (last != null && now - last < COOLDOWN_MS) {
            return "表情包冷却中，稍后再发";
        }
        lastSendTime.put(groupId, now);

        String keywords = (String) args.get("keywords");

        try {
            StickerEntry selected = selectSticker(keywords);
            if (selected == null) return "没找到合适的表情包";

            String cqCode;
            if (selected.file != null && !selected.file.isBlank()) {
                // 从 classpath 加载图片
                InputStream is = getClass().getClassLoader()
                        .getResourceAsStream("stickers/" + selected.file);
                if (is != null) {
                    byte[] bytes = is.readAllBytes();
                    is.close();
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    cqCode = "[CQ:image,file=base64://" + b64 + "]";
                } else {
                    // 文件不存在，回退到 QQ face
                    cqCode = faceFallback(selected);
                }
            } else {
                cqCode = faceFallback(selected);
            }

            if (cqCode == null || cqCode.isBlank()) {
                return "表情包发送失败";
            }

            bot.sendGroupReply(Long.parseLong(groupId), cqCode);
            return "已发送表情包: " + String.join(",", selected.keywords);
        } catch (Exception e) {
            logger.warn("发送表情包失败", e);
            return "表情包发送失败: " + e.getMessage();
        }
    }

    private StickerEntry selectSticker(String keywords) {
        if (stickers.isEmpty()) return null;
        if (keywords == null || keywords.isBlank()) {
            return stickers.get(ThreadLocalRandom.current().nextInt(stickers.size()));
        }
        // 关键词匹配打分
        List<StickerEntry> scored = new ArrayList<>(stickers);
        scored.sort((a, b) -> {
            int sa = matchScore(b, keywords) - matchScore(a, keywords);
            if (sa != 0) return sa;
            return ThreadLocalRandom.current().nextInt(3) - 1;
        });
        // 取 top 3 里随机一个
        int limit = Math.min(3, scored.size());
        return scored.get(ThreadLocalRandom.current().nextInt(limit));
    }

    private int matchScore(StickerEntry entry, String input) {
        int score = 0;
        String lower = input.toLowerCase();
        for (String kw : entry.keywords) {
            if (lower.contains(kw)) score += 10;
            if (kw.contains(lower)) score += 5;
        }
        return score;
    }

    private String faceFallback(StickerEntry entry) {
        for (String kw : entry.keywords) {
            for (Map.Entry<String, Integer> fb : FACE_FALLBACK.entrySet()) {
                if (kw.contains(fb.getKey()) || fb.getKey().contains(kw)) {
                    return "[CQ:face,id=" + fb.getValue() + "]";
                }
            }
        }
        // 无匹配回退 → 随机发个默认表情
        return "[CQ:face,id=" + (14 + ThreadLocalRandom.current().nextInt(5)) + "]";
    }

    // ---- 元数据加载 ----

    private List<StickerEntry> loadMetadata() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("stickers/stickers.json")) {
            if (is == null) {
                logger.warn("stickers.json 未找到，表情包功能将只使用 QQ 内置表情");
                return Collections.emptyList();
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(is, new TypeReference<List<StickerEntry>>() {});
        } catch (Exception e) {
            logger.warn("加载 stickers.json 失败", e);
            return Collections.emptyList();
        }
    }

    // ---- 内部类 ----

    public static class StickerEntry {
        public String file;
        public List<String> keywords;
    }
}
