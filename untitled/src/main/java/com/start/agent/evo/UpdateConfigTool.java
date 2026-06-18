package com.start.agent.evo;

import com.start.agent.Tool;

import com.start.config.BotConfig;
import com.start.service.RuntimeConfigService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热重载配置工具 — 修改提示词/工具描述等运行时配置。
 * 仅管理员(归儿)可用。
 *
 * 提示词保护：
 * - system_prompt_override / system_prompt_patch → 写入时自动备份，改为提案暂存
 * - 人工说"确认#N"才能生效
 * - 人工说"撤回#N"则丢弃
 * - 可用 restore 回滚到最近备份
 */
public class UpdateConfigTool implements Tool {

    private static final Set<String> PROMPT_KEYS = Set.of("system_prompt_override", "system_prompt_patch");

    private final RuntimeConfigService configService;
    private final String realUserId;

    public UpdateConfigTool(RuntimeConfigService configService, String realUserId) {
        this.configService = configService;
        this.realUserId = realUserId;
    }

    @Override public String getName() { return "update_config"; }

    @Override
    public String getDescription() {
        return "热重载运行时配置（提示词、工具描述等），改完立即生效无需重启。\n" +
               "参数: action(list/set/delete/approve/reject/pending/restore), key(配置键名), value(配置值)。\n" +
               "常用键: system_prompt_override(完全替换提示词), system_prompt_patch(追加到提示词末尾), " +
               "tool_desc_<工具名>(覆盖工具描述)。\n" +
               "⚠️ system_prompt_override 和 system_prompt_patch 写入时会自动备份旧值，并转为提案暂存，需归儿人工确认后才生效。\n" +
               "仅管理员(归儿)可用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "list/pending(查看), set(设置), delete(清除), approve/reject(确认/撤回提案,需id), restore(回滚,需key)"),
                        "key", Map.of("type", "string",
                                "description", "配置键名。如: system_prompt_patch, tool_desc_send_sticker"),
                        "value", Map.of("type", "string",
                                "description", "配置值（set时必填）"),
                        "id", Map.of("type", "integer",
                                "description", "提案编号（approve/reject时必填）")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        long uid;
        try { uid = Long.parseLong(realUserId); }
        catch (NumberFormatException e) { return "无法确定用户身份"; }
        if (uid != BotConfig.getAdminQq()) {
            return "update_config 仅对归儿开放。";
        }

        String action = (String) args.get("action");
        if (action == null) return "请指定 action";

        return switch (action) {
            case "list" -> listAll();
            case "pending" -> configService.listProposals();
            case "set" -> handleSet(args);
            case "delete" -> handleDelete(args);
            case "approve" -> handleApprove(args);
            case "reject" -> handleReject(args);
            case "restore" -> handleRestore(args);
            default -> "不支持的操作: " + action + "。支持: list / pending / set / delete / approve / reject / restore";
        };
    }

    private String listAll() {
        Map<String, String> all = configService.listAll();
        if (all.isEmpty()) return "当前没有保存的运行时配置。";
        StringBuilder sb = new StringBuilder("当前运行时配置:\n");
        all.forEach((k, v) -> {
            // 隐藏备份条目
            if (k.contains(".bak.")) return;
            String display = v.length() > 80 ? v.substring(0, 80) + "..." : v;
            sb.append("[").append(k).append("] → ").append(display).append("\n");
        });
        return sb.toString();
    }

    private String handleSet(Map<String, Object> args) {
        String key = (String) args.get("key");
        String value = (String) args.get("value");
        if (key == null || key.isBlank()) return "请指定 key";
        if (value == null || value.isBlank()) return "请指定 value";

        // 提示词 key → 提案暂存，不直接写入
        if (PROMPT_KEYS.contains(key)) {
            return configService.propose(key, value, realUserId);
        }

        // 非提示词 key → 直接写入
        return configService.directSet(key, value, realUserId);
    }

    private String handleDelete(Map<String, Object> args) {
        String key = (String) args.get("key");
        if (key == null || key.isBlank()) return "请指定 key";
        if (PROMPT_KEYS.contains(key)) {
            return configService.propose(key, "", realUserId);
        }
        return configService.directSet(key, "", realUserId);
    }

    private String handleApprove(Map<String, Object> args) {
        Object idObj = args.get("id");
        if (idObj == null) return "请指定提案 id（如 approve id=3）";
        int id = idObj instanceof Number n ? n.intValue() : Integer.parseInt(idObj.toString());
        return configService.approveProposal(id, realUserId);
    }

    private String handleReject(Map<String, Object> args) {
        Object idObj = args.get("id");
        if (idObj == null) return "请指定提案 id（如 reject id=3）";
        int id = idObj instanceof Number n ? n.intValue() : Integer.parseInt(idObj.toString());
        return configService.rejectProposal(id);
    }

    private String handleRestore(Map<String, Object> args) {
        String key = (String) args.get("key");
        if (key == null || key.isBlank()) return "请指定要回滚的 key";
        return configService.restore(key);
    }
}
