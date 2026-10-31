package com.start.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import com.start.model.ConversationThread;
import com.start.service.AuditModelUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息 → Thread 归类。规则优先，LLM 兜底。
 *
 * 机械规则（按优先级）：
 *   1. 用户已在活跃 Thread 的参与者列表中 → 归入该 Thread
 *   2. 消息与活跃 Thread topic 的 Jaccard 相似度 > 0.3 → 归入最相似的
 *   3. 相似度在 0.1-0.3 的灰色区间 → audit LLM 判断
 *   4. 以上都不匹配 → 新 Thread
 */
public class ThreadDetector {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDetector.class);
    private static final double CLEAR_MATCH_THRESHOLD = 0.3;
    private static final double AMBIGUOUS_MIN = 0.1;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    /**
     * 为消息找到所属 Thread。返回 null 表示需要创建新 Thread。
     */
    public ConversationThread detect(String groupId, String userId, String message,
                                      List<ConversationThread> activeThreads) {
        return detect(groupId, userId, message, "", activeThreads);
    }

    public ConversationThread detect(String groupId, String userId, String message, String messageId,
                                     List<ConversationThread> activeThreads) {
        if (activeThreads == null || activeThreads.isEmpty()) return null;
        if (message == null || message.isBlank()) return null;

        // 规则 1：用户已在某 Thread 的参与者列表中
        for (ConversationThread t : activeThreads) {
            if (t.getParticipants().contains(userId)) {
                logger.debug("Thread detected by participant: thread={} user={}", t.getId(), userId);
                return t;
            }
        }

        // 规则 2：Jaccard 相似度匹配
        double bestScore = 0;
        ConversationThread bestMatch = null;
        double secondBestScore = 0;
        for (ConversationThread t : activeThreads) {
            if (t.getTopic() == null || t.getTopic().isBlank()) continue;
            double score = jaccardSimilarity(message, t.getTopic());
            if (score > bestScore) {
                secondBestScore = bestScore;
                bestScore = score;
                bestMatch = t;
            } else if (score > secondBestScore) {
                secondBestScore = score;
            }
        }

        // 明确匹配：分数 >= 0.3 且与第二名的差距 > 0.1
        boolean clearMatch = bestScore >= CLEAR_MATCH_THRESHOLD && (bestScore - secondBestScore) > 0.1;
        if (clearMatch) {
            logger.debug("Thread detected by similarity: thread={} score={}", bestMatch.getId(), bestScore);
            return bestMatch;
        }

        // 规则 3：模糊区间 → audit LLM 判断
        // 条件：分数在 0.1-0.3 的灰色区，或分数 >= 0.3 但与第二名差距太小
        boolean ambiguous = (bestScore >= AMBIGUOUS_MIN && bestScore < CLEAR_MATCH_THRESHOLD)
                || (bestScore >= CLEAR_MATCH_THRESHOLD && (bestScore - secondBestScore) <= 0.1);
        if (ambiguous && bestMatch != null) {
            logger.debug("Ambiguous match score={} gap={}, trying LLM fallback", bestScore, bestScore - secondBestScore);
            Long llmThreadId = classifyByLLM(message, groupId, userId, messageId, activeThreads);
            if (llmThreadId != null) return activeThreads.stream()
                    .filter(t -> t.getId().equals(llmThreadId)).findFirst().orElse(null);
        }

        // 规则 4：新 Thread
        return null;
    }

    /**
     * 调用 audit LLM 判断消息属于哪个 Thread。
     * 返回 thread ID，或 null 表示新话题。
     */
    private Long classifyByLLM(String message, String groupId, String userId, String messageId,
                               List<ConversationThread> threads) {
        if (threads.isEmpty()) return null;
        try {
            StringBuilder topics = new StringBuilder();
            for (int i = 0; i < threads.size(); i++) {
                ConversationThread t = threads.get(i);
                if (t.getTopic() == null || t.getTopic().isBlank()) continue;
                topics.append(i + 1).append(". ").append(t.getTopic()).append("\n");
            }
            if (topics.isEmpty()) return null;

            String prompt = "群聊消息：\"" + message + "\"\n\n活跃话题：\n" + topics
                    + "\n判断这条消息属于哪个话题。只回复数字（如 1），如果属于新话题回复 0。只回复一个数字：";

            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("model", BotConfig.getAuditModel());
            ArrayNode msgs = body.putArray("messages");
            ObjectNode msg = msgs.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            body.put("max_tokens", 5);
            body.put("temperature", 0.0);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BotConfig.getAuditBaseUrl()))
                    .header("Authorization", "Bearer " + BotConfig.getAuditApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(BotConfig.getAuditTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = AuditModelUsageService.send(
                    httpClient, req, "thread_classification", BotConfig.getAuditModel(),
                    new AuditModelUsageService.CallContext(messageId, groupId, userId, "", "thread_classification"), 1);
            if (resp.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(resp.body());
                String content = json.path("choices").get(0).path("message").path("content").asText("0").trim();
                int idx = Integer.parseInt(content.replaceAll("[^0-9]", ""));
                if (idx > 0 && idx <= threads.size()) {
                    return threads.get(idx - 1).getId();
                }
            }
        } catch (Exception e) {
            logger.debug("Thread LLM classification failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 计算两段中文文本的字符 bigram Jaccard 相似度。
     */
    static double jaccardSimilarity(String a, String b) {
        Set<String> bigramsA = toBigrams(a);
        Set<String> bigramsB = toBigrams(b);
        if (bigramsA.isEmpty() && bigramsB.isEmpty()) return 1.0;
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);

        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> toBigrams(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.length() < 2) return set;
        String cleaned = text.replaceAll("\\s+", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            set.add(cleaned.substring(i, i + 2));
        }
        return set;
    }
}
