package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.model.AuditReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 巡检报告翻译器：把主 AI 输出的 JSON 报告翻译为给归儿的可读私聊消息。
 * 属于 Expression 层：只做排版，不做理解。
 *
 * 鲁棒性：主 AI 输出经常夹带 markdown ```json 包裹、前后废话，
 * 这里用正则抽出最外层 JSON 对象再解析，解析失败返回 null（交给 ErrorMonitorService 兜底）。
 */
public class AuditReportBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AuditReportBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从主 AI 输出文本中解析 AuditReport。失败返回 null。
     * 容忍：```json 包裹、首尾废话、空格换行。
     */
    public static AuditReport parse(String aiOutput) {
        if (aiOutput == null) return null;
        String trimmed = aiOutput.trim();
        if (trimmed.isEmpty()) return null;

        String jsonText = extractJsonObject(trimmed);
        if (jsonText == null) {
            logger.debug("AuditReportBuilder: 未找到 JSON 对象");
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(jsonText);
            if (!isReportObject(root)) {
                logger.debug("AuditReportBuilder: JSON 未包含已知报告字段");
                return null;
            }
            AuditReport report = new AuditReport();
            report.setSeverity(firstText(root, "可忽略", "severity"));
            report.setSummary(firstText(root, "", "summary", "issue"));
            String location = firstText(root, "", "location");
            String affectedFiles = affectedFiles(root);
            if (!affectedFiles.isEmpty()) {
                location = location.isEmpty() ? affectedFiles : location + "；" + affectedFiles;
            }
            report.setLocation(location);
            report.setExceptionType(firstText(root, "", "exceptionType"));
            report.setRootCause(firstText(root, "", "rootCause", "root_cause"));
            report.setEvidence(firstText(root, "", "evidence"));
            report.setNote(firstText(root, "", "note"));

            List<String> suggestions = new ArrayList<>();
            appendSuggestions(suggestions, root.path("suggestions"));
            if (suggestions.isEmpty()) appendSuggestions(suggestions, root.path("suggested_fix"));
            report.setSuggestions(suggestions);

            JsonNode nf = root.has("needsFix") ? root.path("needsFix") : root.path("need_fix");
            if (nf.isBoolean()) {
                report.setNeedsFix(nf.asBoolean());
            } else {
                // fallback：从 severity 推断
                report.setNeedsFix(report.getSeverity().contains("严重"));
            }
            return report;
        } catch (Exception e) {
            // 某些模型会把 Markdown 中的下划线转义成非法 JSON 的 \_。
            if (jsonText.contains("\\_")) {
                return parse(jsonText.replace("\\_", "_"));
            }
            logger.warn("AuditReportBuilder: JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 AuditReport 翻译为给归儿的私聊消息（Markdown 格式）。
     * 设计：可读 + 重点信息靠前 + 修复建议清单化。
     */
    public static String render(AuditReport report) {
        if (report == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("【巡检报告】\n");

        // 严重程度图标
        String sev = report.getSeverity();
        String icon = "ℹ️";
        if (sev.contains("严重")) icon = "🔴";
        else if (sev.contains("一般")) icon = "🟡";
        else if (sev.contains("忽略")) icon = "🟢";
        sb.append(icon).append(" 严重程度：").append(sev).append("\n");

        // 一句话结论（必填）
        if (!report.getSummary().isEmpty()) {
            sb.append("📌 结论：").append(report.getSummary()).append("\n");
        }

        // 异常类型 + 位置
        if (!report.getExceptionType().isEmpty()) {
            sb.append("⚠️ 异常：").append(report.getExceptionType()).append("\n");
        }
        if (!report.getRootCause().isEmpty()) {
            sb.append("🧭 根因：").append(report.getRootCause()).append("\n");
        }
        if (!report.getEvidence().isEmpty()) {
            sb.append("🔎 证据：").append(report.getEvidence()).append("\n");
        }
        if (!report.getLocation().isEmpty()) {
            sb.append("📍 位置：").append(report.getLocation()).append("\n");
        }

        // 建议清单
        if (report.getSuggestions() != null && !report.getSuggestions().isEmpty()) {
            sb.append("\n💡 建议：\n");
            for (String sug : report.getSuggestions()) {
                sb.append("  • ").append(sug).append("\n");
            }
        }
        if (!report.getNote().isEmpty()) {
            sb.append("\n📝 备注：").append(report.getNote()).append("\n");
        }

        // 是否需要修
        sb.append("\n").append(report.isNeedsFix() ? "🔧 建议修复" : "✅ 暂可观察").append("\n");
        return sb.toString().trim();
    }

    /**
     * 提取字符串中的 JSON 对象。优先匹配 ```json ... ``` 代码块，否则匹配第一个 {...}。
     */
    private static String extractJsonObject(String text) {
        // 1. 尝试 ```json ... ``` 代码块
        Pattern codeBlock = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher m = codeBlock.matcher(text);
        if (m.find()) return m.group(1);

        // 2. 尝试找第一个完整的 {...}（贪心匹配到能解析为止）
        int start = text.indexOf('{');
        if (start < 0) return null;

        // 从第一个 { 开始，平衡大括号找到匹配的 }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String textOr(JsonNode root, String field, String fallback) {
        JsonNode n = root.path(field);
        if (n.isMissingNode() || n.isNull()) return fallback;
        String v = n.asText(fallback);
        return v == null ? fallback : v;
    }

    private static String firstText(JsonNode root, String fallback, String... fields) {
        for (String field : fields) {
            String value = textOr(root, field, "").trim();
            if (!value.isEmpty()) return value;
        }
        return fallback;
    }

    private static String affectedFiles(JsonNode root) {
        JsonNode files = root.path("affected_files");
        if (!files.isArray()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode file : files) {
            String value = file.asText("").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return String.join("、", values);
    }

    private static boolean isReportObject(JsonNode root) {
        if (root == null || !root.isObject()) return false;
        return root.has("severity") || root.has("summary") || root.has("issue")
                || root.has("needsFix") || root.has("need_fix") || root.has("suggestions")
                || root.has("suggested_fix") || root.has("root_cause") || root.has("affected_files");
    }

    private static void appendSuggestions(List<String> suggestions, JsonNode sugNode) {
        if (sugNode.isArray()) {
            for (JsonNode s : sugNode) {
                String value = s.asText("").trim();
                if (!value.isEmpty()) suggestions.add(value);
            }
        } else if (sugNode.isTextual()) {
            for (String line : sugNode.asText("").split("[\\n;；]")) {
                String value = line.trim();
                if (!value.isEmpty()) suggestions.add(value);
            }
        }
    }
}
