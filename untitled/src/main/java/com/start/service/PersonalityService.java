package com.start.service;

import java.util.*;

/**
 * 糖果熊性格系统
 * 安静文艺少女设定（非温柔）
 */
public class PersonalityService {

    // 性格参数
    private static class PersonalityTraits {
        double quietness = 0.8;        // 安静程度 0-1
        double elegance = 0.7;         // 文艺程度
        double thoughtfulness = 0.6;   // 思考深度
        double curiosity = 0.5;        // 好奇心
        double humor = 0.3;            // 幽默感（较低）
    }

    private final PersonalityTraits traits = new PersonalityTraits();
    private final Set<String> interests = new HashSet<>(Arrays.asList(
            "文学", "诗歌", "音乐", "艺术", "哲学", "自然", "读书", "写作"
    ));

    /**
     * 判断糖果熊是否对话题感兴趣
     */
    public boolean isInterestedInTopic(String message) {
        String lower = message.toLowerCase();
        return interests.stream().anyMatch(lower::contains);
    }

    /**
     * 应用性格过滤到AI回复
     */
    public String applyPersonality(String aiReply, String context) {
        if (aiReply == null || aiReply.trim().isEmpty()) {
            return aiReply;
        }

        String processed = aiReply;

        // 1. 安静性格：缩短过长的回复
        if (processed.length() > 30) {
            processed = shortenReply(processed);
        }

        // 2. 文艺气质：添加适当的修饰
        if (Math.random() < traits.elegance) {
            processed = addLiteraryTouch(processed);
        }

        // 3. 思考型：添加思考迹象
        if (Math.random() < traits.thoughtfulness) {
            processed = addThoughtfulness(processed);
        }

        // 4. 非温柔：移除过度友好的语气词
        processed = removeExcessiveSoftness(processed);

        return processed;
    }

    /**
     * 决定是否主动参与对话
     */
    public boolean decideToJoinConversation(String groupId, String message,
                                            double activityLevel) {
        // 安静性格：降低主动参与概率
        double baseProbability = 0.1 * (1 - traits.quietness);

        // 对感兴趣的话题提高概率
        if (isInterestedInTopic(message)) {
            baseProbability *= 2.0;
        }

        // 群聊活跃度高时，保持安静
        if (activityLevel > 10) { // 最近消息很多
            baseProbability *= 0.5;
        }

        return Math.random() < baseProbability;
    }

    /**
     * 生成糖果熊风格的主动回复
     */
    public String generatePersonalityReply(String trigger) {
        if (trigger.contains("诗") || trigger.contains("文学")) {
            return "说到文学...我最近在读一些诗。";
        }
        if (trigger.contains("音乐")) {
            return "音乐让人平静...";
        }
        if (trigger.contains("艺术")) {
            return "艺术是另一种语言...";
        }

        // 默认的安静回复
        String[] quietReplies = {
                "嗯...",
                "这样啊...",
                "原来如此...",
                "有意思...",
                "我在听..."
        };

        return quietReplies[(int)(Math.random() * quietReplies.length)];
    }

    // 私有辅助方法
    private String shortenReply(String reply) {
        // 找到合适的断句点
        int cutIndex = Math.min(25, reply.length());
        for (int i = cutIndex; i >= 15; i--) {
            char c = reply.charAt(i);
            if (c == '。' || c == '，' || c == '！' || c == '？' || c == '、') {
                return reply.substring(0, i + 1);
            }
        }
        return reply.substring(0, Math.min(20, reply.length())) + "...";
    }

    private String addLiteraryTouch(String reply) {
        String[] literarySuffixes = {"", "。", "...", "～", "——"};
        String suffix = literarySuffixes[(int)(Math.random() * literarySuffixes.length)];

        // 偶尔添加文学引用
        if (Math.random() < 0.1) {
            String[] quotes = {
                    "「言有尽而意无穷」",
                    "「诗言志，歌永言」",
                    "「艺术是情感的传递」"
            };
            String quote = quotes[(int)(Math.random() * quotes.length)];
            return reply + "\n" + quote;
        }

        return reply + suffix;
    }

    private String addThoughtfulness(String reply) {
        String[] thoughtPrefixes = {"想想...", "感觉...", "或许...", "有时..."};
        if (Math.random() < 0.3) {
            String prefix = thoughtPrefixes[(int)(Math.random() * thoughtPrefixes.length)];
            return prefix + reply;
        }
        return reply;
    }

    private String removeExcessiveSoftness(String reply) {
        // 移除过度温柔的语气词
        String[] softWords = {"呢", "呀", "喔", "啦", "嘛", "哒", "捏"};
        String result = reply;
        for (String word : softWords) {
            if (result.endsWith(word)) {
                result = result.substring(0, result.length() - word.length());
            }
        }
        return result;
    }
}