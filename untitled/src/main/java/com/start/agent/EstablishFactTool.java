package com.start.agent;

import com.start.config.BotConfig;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.time.LocalDateTime;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 在知识库无匹配时，根据人设自动形成关于糖果熊自己的新设定。
 * 与 RememberFactTool 的区别：source=AUTO_FORMED、置信度更低、支持 TTL。
 */
public class EstablishFactTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(EstablishFactTool.class);
    private final LongTermMemoryRepository repo;

    public EstablishFactTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "establish_fact"; }

    @Override public String getDescription() {
        return "形成关于你（糖果熊）自己的新设定。当 knowledge_base 没有相关信息，且问题属于生活细节"
               + "（喜好、小故事、日常、最近经历），而非固定设定（生日、学校、家庭、疾病、世界观）时，"
               + "根据人设自然形成一个合理的新设定，写入数据库。要求：不与已有知识冲突、符合17岁女生的人设、"
               + "尽量普通真实不追求戏剧性。调用前先用 recall_memory(user_id=你的QQ号) 查是否已有未过期的事实——"
               + "已有则保持一致不要改。稳定性：不要今天说喜欢A、明天说喜欢B。"
               + "参数：content(事实内容，一句话), memory_type(preference/event/fact，默认preference), "
               + "keywords(逗号分隔关键词), ttl_hours(有效期小时数，0=永久。偏好720、事件72、日常24-48)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "content", Map.of("type", "string", "description", "事实内容，简洁的一句话，如'喜欢薄荷绿'"),
                        "memory_type", Map.of("type", "string", "description", "类型：preference(偏好)/event(事件)/fact(事实)，默认preference"),
                        "keywords", Map.of("type", "string", "description", "关键词，逗号分隔，方便以后检索"),
                        "ttl_hours", Map.of("type", "string", "description", "有效期小时数，0=永久。偏好默认720(30天)，事件默认72，日常默认24")
                ),
                "required", Arrays.asList("content"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) return "缺少 content 参数";

        String userId = String.valueOf(BotConfig.getBotQq());
        String trimmedContent = content.trim();
        String keywords = (String) args.get("keywords");
        String memoryType = (String) args.getOrDefault("memory_type", "preference");
        int ttlHours = parseIntSafe((String) args.get("ttl_hours"), defaultTtlHours(memoryType));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = ttlHours > 0 ? now.plusHours(ttlHours) : null;

        try {
            // 1. 检查是否有同名未过期的事实 → 强化确认
            List<LongTermMemory> similar = repo.findSimilar(userId, null, trimmedContent, keywords);
            var exact = similar.stream().filter(m -> "AUTO_FORMED".equals(m.getSource())
                    && trimmedContent.equals(m.getContent())).findFirst();
            if (exact.isPresent()) {
                LongTermMemory existing = exact.get();
                repo.upsertConfirm(existing.getId());
                return "已强化已有设定: " + trimmedContent + "（之前已存在，已更新确认时间）";
            }

            // 2. 检查是否有同名已过期的事实 → 刷新复用
            List<LongTermMemory> expired = repo.findExpiredByUser(userId, trimmedContent);
            var expiredExact = expired.stream().filter(m -> "AUTO_FORMED".equals(m.getSource())
                    && trimmedContent.equals(m.getContent())).findFirst();
            if (expiredExact.isPresent()) {
                LongTermMemory old = expiredExact.get();
                repo.refreshExpired(old.getId(), trimmedContent, keywords, expiresAt);
                return "已刷新过期设定: " + trimmedContent + "（之前已过期，已更新内容和有效期"
                        + (ttlHours > 0 ? "，" + ttlHours + "h后过期" : "，永久有效") + "）";
            }

            // 3. 新建
            LongTermMemory m = new LongTermMemory();
            m.setUserId(userId);
            m.setContent(trimmedContent);
            m.setMemoryType(memoryType);
            m.setKeywords(keywords);
            m.setImportance(2);
            m.setSource("AUTO_FORMED");
            m.setStatus("ACTIVE");
            m.setConfidence(0.4);
            m.setExpiresAt(expiresAt);
            m.setLastSeenAt(now);
            m.setLastConfirmedAt(now);

            repo.insert(m);
            return "已形成新设定: " + trimmedContent
                    + (ttlHours > 0 ? "（" + ttlHours + "h后过期）" : "（永久有效）");
        } catch (Exception e) {
            logger.error("establish_fact 失败", e);
            return "形成设定失败: " + e.getMessage();
        }
    }

    /** 根据 memory_type 返回默认 TTL 小时数 */
    private int defaultTtlHours(String memoryType) {
        return switch (memoryType) {
            case "event" -> 72;
            case "fact" -> 24;
            default -> 720; // preference: 30 天
        };
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
