package com.start.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 发送文件工具 — 上传本地文件到群聊或私聊。
 */
public class SendFileTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(SendFileTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Main bot;

    public SendFileTool(Main bot) {
        this.bot = bot;
    }

    @Override public String getName() { return "send_file"; }

    @Override
    public String getDescription() {
        return "上传并发送本地文件到群聊或私聊。\n" +
               "参数: target_type(group或private), target_id(群号或QQ号), file_path(服务器上的文件路径), file_name(可选，展示文件名)。\n" +
               "只发服务器上已有的文件，比如截图、日志等。发之前先用 shell_exec ls 确认文件存在。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("target_type", Map.of("type", "string", "description", "发送目标类型: group 或 private"));
        properties.put("target_id", Map.of("type", "string", "description", "目标群号(group)或用户QQ号(private)"));
        properties.put("file_path", Map.of("type", "string", "description", "服务器上的本地文件路径"));
        properties.put("file_name", Map.of("type", "string", "description", "展示文件名，不填就用原文件名"));
        return Map.of("type", "object",
                "properties", properties,
                "required", List.of("target_type", "target_id", "file_path"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String targetType = (String) args.get("target_type");
        String targetId = (String) args.get("target_id");
        String filePath = (String) args.get("file_path");
        String fileName = (String) args.get("file_name");

        if (targetType == null || targetId == null || filePath == null) {
            return "缺少参数: target_type, target_id, file_path 都是必填的";
        }

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return "文件不存在: " + filePath;
        }
        if (!Files.isRegularFile(path)) {
            return "路径不是文件: " + filePath;
        }

        // 路径穿越防护
        try {
            path.toRealPath();
        } catch (Exception e) {
            return "文件路径无效: " + filePath;
        }

        String displayName = (fileName != null && !fileName.isBlank())
                ? fileName : path.getFileName().toString();

        String action;
        ObjectNode params = MAPPER.createObjectNode();
        if ("group".equals(targetType)) {
            action = "upload_group_file";
            params.put("group_id", Long.parseLong(targetId));
        } else if ("private".equals(targetType)) {
            action = "upload_private_file";
            params.put("user_id", Long.parseLong(targetId));
        } else {
            return "target_type 只能是 group 或 private";
        }

        params.put("file", filePath);
        params.put("name", displayName);

        try {
            logger.info("发送文件: {} -> {} {} ({}), 展示名: {}", filePath, targetType, targetId, path.toFile().length(), displayName);
            var future = bot.callOneBotApi(action, params);
            var resp = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && "ok".equals(resp.path("status").asText())) {
                return "文件已发送: " + displayName;
            }
            String errMsg = resp != null ? resp.path("wording").asText("") : "无响应";
            return "文件发送失败: " + errMsg;
        } catch (Exception e) {
            logger.error("发送文件失败: {}", filePath, e);
            return "发送文件失败: " + e.getMessage();
        }
    }
}
