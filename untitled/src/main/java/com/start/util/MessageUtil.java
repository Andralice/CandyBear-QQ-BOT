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
     * 将 OneBot 消息段压缩成给群聊关系判断使用的可读摘要。
     * 不把 URL、文件内容或原始 CQ 码塞进公共上下文，只保留消息类型和必要的短标题。
     */
    public static String summarizeSegments(JsonNode messageNode) {
        if (messageNode == null || messageNode.isNull()) return "";
        if (messageNode.isTextual()) return messageNode.asText().isBlank() ? "" : "文字";
        if (!messageNode.isArray()) return "其他消息";

        List<String> parts = new ArrayList<>();
        for (JsonNode seg : messageNode) {
            if (seg == null || !seg.isObject()) continue;
            String type = seg.path("type").asText("").trim().toLowerCase();
            JsonNode data = seg.path("data");
            switch (type) {
                case "text" -> {
                    if (!data.path("text").asText("").isBlank()) parts.add("文字");
                }
                case "at" -> {
                    String qq = data.path("qq").asText("").trim();
                    parts.add(qq.isEmpty() ? "@某人" : "@用户" + qq);
                }
                case "reply" -> {
                    String id = data.path("id").asText("").trim();
                    parts.add(id.isEmpty() ? "回复消息" : "回复消息" + id);
                }
                case "image" -> parts.add("图片");
                case "face", "mface" -> parts.add("表情");
                case "file" -> {
                    String name = compactSegmentValue(data.path("file").asText(""), 40);
                    parts.add(name.isEmpty() ? "文件" : "文件「" + name + "」");
                }
                case "record", "音频" -> parts.add("语音");
                case "video" -> parts.add("视频");
                case "share" -> {
                    String title = compactSegmentValue(data.path("title").asText(""), 40);
                    parts.add(title.isEmpty() ? "分享卡片" : "分享卡片「" + title + "」");
                }
                case "music" -> {
                    String title = compactSegmentValue(data.path("title").asText(""), 40);
                    parts.add(title.isEmpty() ? "音乐" : "音乐「" + title + "」");
                }
                case "forward" -> parts.add("转发消息");
                case "json" -> parts.add("JSON 卡片");
                case "xml" -> parts.add("XML 卡片");
                case "contact" -> parts.add("联系人");
                case "location" -> parts.add("位置");
                case "node" -> parts.add("转发节点");
                case "" -> parts.add("未知消息段");
                default -> parts.add("其他消息段(" + compactSegmentValue(type, 24) + ")");
            }
        }
        return String.join("、", parts.stream().distinct().toList());
    }

    private static String compactSegmentValue(String value, int maxChars) {
        if (value == null) return "";
        String compact = value.replaceAll("[\\r\\n]+", " ").trim();
        if (compact.length() <= maxChars) return compact;
        return compact.substring(0, Math.max(0, maxChars - 3)) + "...";
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
            String file = data.path("file").asText("");
            if (url.isEmpty() && file.isEmpty()) continue;
            Map<String, String> info = new HashMap<>();
            info.put("url", url);
            info.put("file", file);
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
     * 提取消息中的文件信息（file_id, file_name, file_size, busid）
     */
    public static List<Map<String, String>> extractFiles(JsonNode messageNode) {
        List<Map<String, String>> files = new ArrayList<>();
        if (messageNode == null || !messageNode.isArray()) return files;
        for (JsonNode seg : messageNode) {
            if (!"file".equals(seg.path("type").asText())) continue;
            JsonNode data = seg.path("data");
            if (data == null || !data.isObject()) continue;
            String fileId = data.path("file_id").asText("");
            if (fileId.isEmpty()) continue;
            Map<String, String> info = new HashMap<>();
            info.put("file_id", fileId);
            info.put("file_name", data.path("file").asText(""));
            info.put("file_size", data.path("file_size").asText(""));
            info.put("busid", data.path("busid").asText(""));
            files.add(info);
        }
        return files;
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
