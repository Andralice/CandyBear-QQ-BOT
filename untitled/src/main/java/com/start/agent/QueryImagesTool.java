package com.start.agent;

import com.start.model.ChatMessage;
import com.start.repository.MessageRepository;

import java.util.*;

/**
 * 搜索历史图片描述的工具。
 * 糖果熊可以用它在数据库中查找过去发送过的图片和其内容描述。
 */
public class QueryImagesTool implements Tool {

    private final MessageRepository messageRepo;
    private final String currentGroupId;

    public QueryImagesTool(MessageRepository messageRepo, String currentGroupId) {
        this.messageRepo = messageRepo;
        this.currentGroupId = currentGroupId;
    }

    @Override
    public String getName() { return "query_past_images"; }

    @Override
    public String getDescription() {
        return "搜索群里过去发过的图片和其内容描述。当用户问「之前发过什么图」「上次那张图」「还记得那张照片吗」时调用。" +
               "参数：keyword(搜索关键词，如「猫」「风景」), limit(返回条数，默认5)。" +
               "返回最近匹配的图片描述列表（含发送时间和描述内容）。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string", "description", "搜索关键词，如「猫」「白猫」「风景」"),
                        "limit", Map.of("type", "string", "description", "返回条数，默认5")
                ),
                "required", Arrays.asList("keyword"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return "请提供搜索关键词";
        }
        if (currentGroupId == null || currentGroupId.isBlank()) {
            return "当前不是群聊会话，不能查询群图片";
        }

        int limit = Math.max(1, Math.min(parseIntSafe((String) args.get("limit"), 5), 10));
        var result = messageRepo.searchImageDescriptions(currentGroupId, keyword.trim(), limit);

        if (!result.isSuccess() || result.getData() == null || result.getData().isEmpty()) {
            return "没有找到相关图片记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(result.getData().size()).append(" 条相关图片记录：\n");
        for (ChatMessage msg : result.getData()) {
            sb.append("- [").append(msg.getCreatedAt() != null ? msg.getCreatedAt().toLocalDate() : "未知时间")
              .append("] ");
            // 从 content 中提取图片描述部分
            String content = msg.getContent();
            if (content != null) {
                int imgIdx = content.indexOf("图片");
                if (imgIdx >= 0) {
                    sb.append(content.substring(imgIdx).replace("\n", " "));
                } else {
                    sb.append(content.length() > 60 ? content.substring(0, 60) + "..." : content);
                }
            }
            if (msg.getImageData() != null) {
                sb.append(" [有图片数据]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
