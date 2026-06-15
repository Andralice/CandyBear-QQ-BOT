package com.start.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 消息处理工具类
 * <p>
 * 提供针对 QQ 机器人消息的解析与提取功能，支持 JSON 格式（OneBot v11 标准）
 * 和 CQ 码字符串格式的消息处理。主要功能包括：
 * <ul>
 *     <li>从结构化 JSON 消息或 CQ 码字符串中提取纯文本内容</li>
 *     <li>从 JSON 消息段中提取被 @ 的用户 QQ 号列表</li>
 *     <li>判断消息是否 @ 了指定用户</li>
 * </ul>
 * </p>
 *
 * @author Lingma
 */
public class MessageUtil {
    private static final Logger logger = LoggerFactory.getLogger(MessageUtil.class);

    /**
     * 提取纯文本内容（忽略 at/image 等）
     */
    public static String extractPlainText(JsonNode messageNode) {
        if (messageNode == null || messageNode.isNull()) return "";
        if (messageNode.isTextual()) return messageNode.asText();

        StringBuilder sb = new StringBuilder();
        if (messageNode.isArray()) {
            for (JsonNode seg : messageNode) {
                if ("text".equals(seg.path("type").asText())) {
                    sb.append(seg.path("data").path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    public static String extractPlainText(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        boolean insideCq = false;

        for (int i = 0; i < rawMessage.length(); i++) {
            char c = rawMessage.charAt(i);

            if (!insideCq) {
                // 检查是否遇到 "[CQ:"
                if (c == '[' && i + 4 <= rawMessage.length()
                        && rawMessage.startsWith("CQ:", i + 1)) {
                    insideCq = true; // 进入 CQ 标签，跳过
                } else {
                    result.append(c); // 普通字符，保留
                }
            } else {
                // 已经在 CQ 标签内部，寻找 ']'
                if (c == ']') {
                    insideCq = false; // 结束标签，继续正常处理
                }
                // 否则继续跳过（不 append）
            }
        }

        return result.toString();
    }

    /**
     * 提取消息中所有被 @ 的 QQ 号
     * 支持 JSON 数组格式的消息段，安全解析
     */
    public static List<Long> extractAts(JsonNode messageNode) {
        List<Long> ats = new ArrayList<>();
        if (messageNode == null || messageNode.isNull() || !messageNode.isArray()) {
            return ats;
        }

        for (JsonNode seg : messageNode) {
            // 确保 seg 是对象
            if (!seg.isObject()) continue;

            JsonNode typeNode = seg.path("type");
            if (!"at".equals(typeNode.asText())) continue;

            JsonNode dataNode = seg.path("data");
            if (dataNode == null || !dataNode.isObject()) continue;

            JsonNode qqNode = dataNode.path("qq");
            if (qqNode == null || !qqNode.isNumber() && !qqNode.isTextual()) continue;

            try {
                long qq = qqNode.asLong();
                // QQ 号必须大于 0，且不超过 2^63-1（实际最大约 20 位）
                if (qq > 0 && qq < 1000000000000000000L) {
                    ats.add(qq);
                }
            } catch (Exception e) {
                logger.warn("解析 @ 段失败: {}", seg, e);
            }
        }

        return ats;
    }

    /**
     * 提取消息中的回复 ID（引用消息），无回复返回 null
     */
    public static Long extractReplyId(JsonNode messageNode) {
        if (messageNode == null || !messageNode.isArray()) return null;
        for (JsonNode seg : messageNode) {
            if ("reply".equals(seg.path("type").asText())) {
                String id = seg.path("data").path("id").asText();
                if (!id.isEmpty()) {
                    try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
                }
            }
        }
        return null;
    }

    /**
     * 判断消息是否 @ 了指定的 QQ
     */
    public static boolean isAt(JsonNode messageNode, long targetQq) {
        List<Long> ats = extractAts(messageNode);
        return ats.contains(targetQq);
    }

    // ---- 图片 / 链接 / 分享提取 ----

    /**
     * 提取消息中的图片信息（url, file, file_size）
     */
    public static List<Map<String, String>> extractImages(JsonNode messageNode) {
        List<Map<String, String>> images = new ArrayList<>();
        if (messageNode == null || !messageNode.isArray()) return images;
        for (JsonNode seg : messageNode) {
            if (!"image".equals(seg.path("type").asText())) continue;
            JsonNode data = seg.path("data");
            if (data == null || !data.isObject()) continue;
            String url = data.path("url").asText("");
            if (url.isEmpty()) continue;
            Map<String, String> info = new HashMap<>();
            info.put("url", url);
            info.put("file", data.path("file").asText(""));
            info.put("file_size", data.path("file_size").asText(""));
            images.add(info);
        }
        return images;
    }

    /**
     * 从纯文本中提取所有 http/https URL
     */
    public static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null || text.isEmpty()) return urls;
        Pattern p = Pattern.compile("https?://[\\w\\-./?=&%+#;~@,:!$'()*]+[\\w/]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    /**
     * 提取消息中的分享卡片（type=="share"），含 url/title/content
     */
    public static List<Map<String, String>> extractShares(JsonNode messageNode) {
        List<Map<String, String>> shares = new ArrayList<>();
        if (messageNode == null || !messageNode.isArray()) return shares;
        for (JsonNode seg : messageNode) {
            if (!"share".equals(seg.path("type").asText())) continue;
            JsonNode data = seg.path("data");
            if (data == null || !data.isObject()) continue;
            String url = data.path("url").asText("");
            if (url.isEmpty()) continue;
            Map<String, String> info = new HashMap<>();
            info.put("url", url);
            info.put("title", data.path("title").asText(""));
            info.put("content", data.path("content").asText(""));
            shares.add(info);
        }
        return shares;
    }
}