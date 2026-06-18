package com.start.agent.evo;

import com.start.agent.Tool;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 创建文件工具 — 在项目中新建 Java 源码文件。
 * 自动创建父目录，安全校验路径，仅允许 src/main/java/com/start/ 下。
 */
public class CreateFileTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(CreateFileTool.class);
    private final String realUserId;
    private final Path projectRoot;

    public CreateFileTool() { this("0"); }

    public CreateFileTool(String realUserId) {
        this.realUserId = realUserId;
        String cwd = System.getProperty("user.dir");
        Path cwdPath = Paths.get(cwd);
        if (Files.exists(cwdPath.resolve("pom.xml"))) {
            this.projectRoot = cwdPath.toAbsolutePath().normalize();
        } else {
            this.projectRoot = Paths.get("/opt/qq-bot");
        }
    }

    @Override public String getName() { return "create_file"; }

    @Override
    public String getDescription() {
        return "在项目中创建新的 Java 源码文件。仅允许在 src/main/java/com/start/ 下创建。\n" +
               "参数: file_path(相对于项目根目录的路径), content(文件完整内容), reason(创建原因)。\n" +
               "会自动创建父目录。仅管理员可用。创建后需用 self_evolve 编译。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "新文件路径，如 src/main/java/com/start/agent/NewTool.java"),
                        "content", Map.of("type", "string", "description", "文件的完整 Java 源码内容"),
                        "reason", Map.of("type", "string", "description", "为什么创建这个文件")
                ),
                "required", List.of("file_path", "content", "reason"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        long uid;
        try { uid = Long.parseLong(realUserId); }
        catch (NumberFormatException e) { return "无法确定用户身份"; }
        if (uid != BotConfig.getAdminQq()) return "create_file 仅对归儿开放。";

        String filePath = (String) args.get("file_path");
        String content = (String) args.get("content");
        String reason = (String) args.get("reason");
        if (filePath == null || filePath.isBlank()) return "请指定 file_path";
        if (content == null || content.isBlank()) return "请指定 content";
        if (reason == null || reason.isBlank()) return "请指定 reason";

        // 安全校验
        String normalized = filePath.replace('\\', '/');
        if (!normalized.startsWith("src/main/java/com/start/")) {
            return "仅允许在 src/main/java/com/start/ 下创建文件。给定路径: " + filePath;
        }
        if (!normalized.endsWith(".java")) {
            return "只能创建 .java 文件: " + filePath;
        }

        Path file = projectRoot.resolve(normalized).normalize();
        if (!file.startsWith(projectRoot)) return "路径不合法: " + filePath;
        if (Files.exists(file)) return "文件已存在: " + filePath + "。用 self_evolve 修改已有文件。";

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            logger.info("创建文件: {} — {}", filePath, reason);

            // git add
            try {
                new ProcessBuilder("git", "add", file.toString())
                        .directory(projectRoot.toFile())
                        .start()
                        .waitFor();
            } catch (Exception ignored) {}

            return "文件已创建: " + filePath + "\n原因: " + reason + "\n\n下一步: 用 self_evolve 编译验证，然后把新类注册到 BaiLianService 的 availableTools 列表和系统提示词。";

        } catch (IOException e) {
            logger.error("创建文件失败: {}", filePath, e);
            return "创建文件失败: " + e.getMessage();
        }
    }
}
