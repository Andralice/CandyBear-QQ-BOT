package com.start.agent;

import com.start.repository.EvolutionRecordRepository;

import java.util.List;
import java.util.Map;

/**
 * 查询自我进化历史记录。
 */
public class EvolutionHistoryTool implements Tool {

    private final EvolutionRecordRepository evoRepo;

    public EvolutionHistoryTool(EvolutionRecordRepository evoRepo) {
        this.evoRepo = evoRepo;
    }

    @Override
    public String getName() { return "evolution_history"; }

    @Override
    public String getDescription() {
        return "查询糖果熊的自我进化历史记录。\n" +
               "参数: action(recent/stats), limit(条数, 默认10)。\n" +
               "- recent: 查看最近 N 次进化记录（含文件、原因、结果、时间）\n" +
               "- stats: 查看进化统计（成功/失败次数）";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "recent 或 stats"),
                        "limit", Map.of("type", "integer", "description", "查询条数，默认10，最大50")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.getOrDefault("action", "recent");
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 10;
        if (limit > 50) limit = 50;

        return switch (action) {
            case "recent" -> queryRecent(limit);
            case "stats" -> queryStats();
            default -> "不支持的操作: " + action + "。支持: recent / stats";
        };
    }

    private String queryRecent(int limit) {
        List<Map<String, Object>> records = evoRepo.queryRecent(limit);
        if (records.isEmpty()) return "暂无自我进化记录。";

        StringBuilder sb = new StringBuilder();
        sb.append("📋 最近 ").append(records.size()).append(" 次自我进化:\n\n");

        for (Map<String, Object> r : records) {
            String resultEmoji = switch ((String) r.get("result")) {
                case "success" -> "✅";
                case "compile_fail", "compile_timeout" -> "❌编译";
                case "package_fail", "package_timeout" -> "❌打包";
                case "error" -> "⚠️";
                default -> "❓";
            };
            sb.append(resultEmoji).append(" #").append(r.get("id"))
              .append(" ").append(r.get("target_file"))
              .append("\n  原因: ").append(r.get("reason"))
              .append("\n  结果: ").append(r.get("result"));
            if (Boolean.TRUE.equals(r.get("git_pushed"))) {
                sb.append(" | 已推送");
            }
            sb.append("\n  时间: ").append(r.get("created_at")).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String queryStats() {
        Map<String, Integer> stats = evoRepo.countByResult();
        if (stats.isEmpty()) return "暂无自我进化统计。";

        long total = stats.values().stream().mapToInt(Integer::intValue).sum();
        long success = stats.getOrDefault("success", 0);
        long fail = total - success;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 自我进化统计:\n");
        sb.append("总次数: ").append(total).append("\n");
        sb.append("成功: ").append(success).append("\n");
        sb.append("失败: ").append(fail).append("\n");
        if (total > 0) {
            sb.append("成功率: ").append(String.format("%.1f%%", 100.0 * success / total)).append("\n");
        }
        sb.append("\n明细:\n");
        for (Map.Entry<String, Integer> e : stats.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString().trim();
    }
}
