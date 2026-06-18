package com.start.agent.evo;

import com.start.agent.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自审工具 — 读取日志、分析错误、定位 bug。
 * 配合 read_code + self_evolve 形成闭环：看日志 → 找 bug → 读代码 → 改代码 → 编译测试。
 */
public class AuditTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(AuditTool.class);
    private static final int MAX_LINES = 200;

    @Override public String getName() { return "audit_logs"; }

    @Override
    public String getDescription() {
        return "分析最近的运行日志，查找错误和异常。\n" +
               "参数: action(errors/warnings/tail/search), lines(行数, 默认100), keyword(搜索关键词)。\n" +
               "- errors: 只看 ERROR 级别日志\n" +
               "- warnings: 只看 WARN 级别日志\n" +
               "- tail: 最后 N 行日志\n" +
               "- search: 搜索包含关键词的日志行及上下文\n" +
               "找到错误后，用 read_code 读相关源码，用 self_evolve 修复，形成排查闭环。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "errors / warnings / tail / search"),
                        "lines", Map.of("type", "integer", "description", "读取行数，默认100"),
                        "keyword", Map.of("type", "string", "description", "search 模式下的搜索关键词")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.getOrDefault("action", "errors");
        int lines = args.get("lines") instanceof Number n ? n.intValue() : 100;
        if (lines > MAX_LINES) lines = MAX_LINES;
        String keyword = (String) args.get("keyword");

        Path logFile = findLogFile();
        if (logFile == null) {
            // 回退：从 classpath 找日志
            return "未找到日志文件。请检查日志路径。" +
                   "\n常见路径: /opt/qq-bot/qq-bot.log 或 /opt/qq-bot/logs/app.log";
        }

        try {
            List<String> allLines = Files.readAllLines(logFile);
            int totalLines = allLines.size();

            return switch (action) {
                case "errors" -> filterLog(allLines, lines, "ERROR", null);
                case "warnings" -> filterLog(allLines, lines, "WARN", null);
                case "tail" -> tailLog(allLines, lines);
                case "search" -> searchLog(allLines, keyword, lines);
                default -> "不支持的操作: " + action + "。支持: errors / warnings / tail / search";
            };

        } catch (IOException e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    private String filterLog(List<String> allLines, int lines, String level, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 最近 ").append(lines).append(" 条 ").append(level).append(" 日志:\n\n");

        int count = 0;
        for (int i = allLines.size() - 1; i >= 0 && count < lines; i--) {
            String line = allLines.get(i);
            if (line.contains(level)) {
                if (keyword == null || line.contains(keyword)) {
                    sb.append(line).append("\n");
                    count++;
                }
            }
        }
        if (count == 0) sb.append("（无 ").append(level).append(" 日志）");
        else sb.append("\n共 ").append(count).append(" 条");

        // 提取堆栈信息
        sb.append("\n\n📋 最近的异常堆栈:\n");
        appendStackTraces(sb, allLines);

        return sb.toString();
    }

    private String tailLog(List<String> allLines, int lines) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, allLines.size() - lines);
        sb.append("📋 最后 ").append(Math.min(lines, allLines.size())).append(" 行日志 (").append(allLines.size()).append(" 行总共):\n\n");
        for (int i = start; i < allLines.size(); i++) {
            sb.append(allLines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String searchLog(List<String> allLines, String keyword, int lines) {
        if (keyword == null || keyword.isBlank()) return "请指定 keyword";

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索 \"").append(keyword).append("\" 在日志中:\n\n");

        int count = 0;
        for (int i = 0; i < allLines.size() && count < lines; i++) {
            if (allLines.get(i).contains(keyword)) {
                // 上下文前后2行
                int ctxStart = Math.max(0, i - 2);
                int ctxEnd = Math.min(allLines.size() - 1, i + 2);
                for (int j = ctxStart; j <= ctxEnd; j++) {
                    sb.append(j == i ? "▶ " : "  ").append(allLines.get(j)).append("\n");
                }
                sb.append("───\n");
                count++;
            }
        }
        sb.append("\n共 ").append(count).append(" 处匹配");
        return sb.toString();
    }

    private void appendStackTraces(StringBuilder sb, List<String> allLines) {
        // 找最近3个异常堆栈
        List<String> traces = new ArrayList<>();
        boolean inTrace = false;
        StringBuilder currentTrace = new StringBuilder();

        for (int i = allLines.size() - 1; i >= 0 && traces.size() < 3; i--) {
            String line = allLines.get(i);
            // 检测异常开始
            if (line.contains("Exception") || line.contains("Error:")) {
                if (currentTrace.length() > 0) {
                    traces.add(currentTrace.toString());
                    currentTrace = new StringBuilder();
                }
                currentTrace.append(line).append("\n");
                inTrace = true;
            } else if (inTrace && (line.startsWith("\tat ") || line.startsWith("Caused by:") || line.startsWith("... "))) {
                currentTrace.insert(0, line + "\n");
            } else if (inTrace && !line.trim().isEmpty()) {
                inTrace = false;
            }
        }
        if (currentTrace.length() > 0) traces.add(currentTrace.toString());

        if (traces.isEmpty()) {
            sb.append("（无异常堆栈）");
        } else {
            for (int i = 0; i < traces.size(); i++) {
                String t = traces.get(i);
                sb.append("── 异常 ").append(i + 1).append(" ──\n");
                sb.append(t.length() > 1500 ? t.substring(0, 1500) + "\n... [截断]" : t);
                sb.append("\n");
            }

            // 智能分析
            sb.append("\n💡 排查建议: ");
            List<String> suggestions = analyzeTraces(traces);
            sb.append(String.join("\n   ", suggestions));
        }
    }

    private List<String> analyzeTraces(List<String> traces) {
        List<String> suggestions = new ArrayList<>();
        for (String trace : traces) {
            if (trace.contains("NullPointerException")) {
                suggestions.add("NPE → 用 read_code 读相关文件，检查变量初始化。");
                if (trace.contains("com.start")) {
                    suggestions.add("在 " + extractClassName(trace) + " 中检查空值处理。");
                }
            } else if (trace.contains("SQLException") || trace.contains("MySQL")) {
                suggestions.add("DB 异常 → 检查 SQL 语法，用 audit_logs action=search keyword=SQL 查具体错误。");
            } else if (trace.contains("TimeoutException") || trace.contains("timed out")) {
                suggestions.add("超时 → 检查网络连接和 API 响应时间。");
            } else if (trace.contains("ClassCastException")) {
                suggestions.add("类型转换错误 → 用 read_code 检查类型转换代码。");
            } else if (trace.contains("IOException")) {
                suggestions.add("IO 异常 → 检查文件路径和权限。");
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("用 read_code 读相关源码文件，重点关注异常堆栈中提到的类。");
        }
        return suggestions;
    }

    private String extractClassName(String trace) {
        Pattern p = Pattern.compile("com\\.start\\.(\\w+)\\.(\\w+)");
        Matcher m = p.matcher(trace);
        if (m.find()) {
            return "src/main/java/com/start/" + m.group(1) + "/" + m.group(2) + ".java";
        }
        return "相关源码文件";
    }

    private Path findLogFile() {
        String[] candidates = {
            "/opt/qq-bot/qq-bot.log",
            "/opt/qq-bot/logs/app.log",
            "qq-bot.log",
            "logs/app.log"
        };
        for (String path : candidates) {
            Path p = Paths.get(path);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
