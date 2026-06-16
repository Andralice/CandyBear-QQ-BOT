package com.start.agent;

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
 * 读代码工具 — 读取项目源码，带行号，不截断。
 * 支持读全文件 / 指定行范围 / 关键词搜索。
 */
public class ReadCodeTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ReadCodeTool.class);
    private static final int MAX_LINES = 500;
    private static final int MAX_OUTPUT_CHARS = 2000;

    private final Path projectRoot;

    public ReadCodeTool() {
        String cwd = System.getProperty("user.dir");
        Path cwdPath = Paths.get(cwd);
        if (Files.exists(cwdPath.resolve("pom.xml"))) {
            this.projectRoot = cwdPath.toAbsolutePath().normalize();
        } else {
            this.projectRoot = Paths.get("/opt/qq-bot");
        }
    }

    @Override public String getName() { return "read_code"; }

    @Override
    public String getDescription() {
        return "读取项目中的 Java 源码文件，返回带行号的内容。\n" +
               "参数: file_path(必填, 相对项目根目录路径), start_line(可选, 起始行), end_line(可选, 结束行), " +
               "keyword(可选, 搜索关键词，返回匹配行及上下文)。\n" +
               "示例: read_code file_path=src/main/java/com/start/service/BaiLianService.java start_line=400 end_line=450\n" +
               "示例: read_code file_path=src/main/java/com/start/service/BaiLianService.java keyword=baseSystemPrompt\n" +
               "改了代码后先用这个确认文件内容，再用 self_evolve。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "文件路径，相对于项目根目录。如 src/main/java/com/start/service/BaiLianService.java"),
                        "start_line", Map.of("type", "integer", "description", "起始行号（可选，从1开始）"),
                        "end_line", Map.of("type", "integer", "description", "结束行号（可选）"),
                        "keyword", Map.of("type", "string", "description", "搜索关键词（可选），返回包含该词的行及前后2行上下文")
                ),
                "required", List.of("file_path"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = (String) args.get("file_path");
        if (filePath == null || filePath.isBlank()) return "请指定 file_path";

        Path file = projectRoot.resolve(filePath).normalize();
        if (!file.startsWith(projectRoot)) return "文件路径不合法: " + filePath;
        if (!Files.exists(file)) return "文件不存在: " + filePath;
        if (!Files.isRegularFile(file)) return "不是文件: " + filePath;

        try {
            List<String> lines = Files.readAllLines(file);

            // 关键词搜索模式
            String keyword = (String) args.get("keyword");
            if (keyword != null && !keyword.isBlank()) {
                return searchByKeyword(lines, keyword, filePath);
            }

            // 行范围模式
            Object startObj = args.get("start_line");
            Object endObj = args.get("end_line");
            int startLine = 1;
            int endLine = lines.size();

            if (startObj instanceof Number n) startLine = Math.max(1, n.intValue());
            if (endObj instanceof Number n) endLine = Math.min(lines.size(), n.intValue());

            if (endLine - startLine > MAX_LINES) {
                endLine = startLine + MAX_LINES - 1;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 ").append(filePath).append(" (行 ").append(startLine).append("-").append(endLine)
                    .append(" / 共 ").append(lines.size()).append(" 行)\n\n");

            for (int i = startLine - 1; i < endLine; i++) {
                sb.append(String.format("%4d │ ", i + 1)).append(lines.get(i)).append("\n");
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    sb.append("\n... [截断，使用 start_line/end_line 读更多内容]");
                    break;
                }
            }
            return sb.toString();

        } catch (IOException e) {
            logger.error("读取文件失败: {}", filePath, e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String searchByKeyword(List<String> lines, String keyword, String filePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 ").append(filePath).append(" 搜索 \"").append(keyword).append("\" (共 ").append(lines.size()).append(" 行)\n\n");

        int matchCount = 0;
        for (int i = 0; i < lines.size() && matchCount < 30; i++) {
            if (lines.get(i).contains(keyword)) {
                matchCount++;
                // 显示前后2行上下文
                int ctxStart = Math.max(0, i - 2);
                int ctxEnd = Math.min(lines.size() - 1, i + 2);

                for (int j = ctxStart; j <= ctxEnd; j++) {
                    String marker = (j == i) ? "▶" : " ";
                    sb.append(String.format("%4d %s %s\n", j + 1, marker, lines.get(j)));
                }
                sb.append("───\n");
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    sb.append("\n... [搜索匹配太多，已截断]");
                    break;
                }
            }
        }
        if (matchCount == 0) {
            sb.append("未找到 \"").append(keyword).append("\"");
        } else {
            sb.append("\n共 ").append(matchCount).append(" 处匹配");
        }
        return sb.toString();
    }
}
