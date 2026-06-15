package com.start.agent;

import com.start.config.BotConfig;
import com.start.service.RuntimeConfigService;

import java.util.List;
import java.util.Map;

/**
 * 热重载配置工具 — 修改提示词/工具描述等运行时配置，改完即生效，无需编译重启。
 * 仅管理员(归儿)可用。
 */
public class UpdateConfigTool implements Tool {

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
               "参数: action(list/set/delete), key(配置键名), value(配置值)。\n" +
               "常用键: system_prompt_override(完全替换提示词), system_prompt_patch(追加到提示词末尾), " +
               "tool_desc_<工具名>(覆盖工具描述)。\n" +
               "仅管理员(归儿)可用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "操作类型: list(查看所有配置), set(设置/更新), delete(删除)"),
                        "key", Map.of("type", "string",
                                "description", "配置键名。如: system_prompt_patch, tool_desc_send_sticker"),
                        "value", Map.of("type", "string",
                                "description", "配置值（set时必填，list/delete时不用填）")
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
            case "list" -> {
                Map<String, String> all = configService.listAll();
                if (all.isEmpty()) {
                    yield "当前没有保存的运行时配置。";
                }
                StringBuilder sb = new StringBuilder("当前运行时配置:\n");
                all.forEach((k, v) -> {
                    String display = v.length() > 80 ? v.substring(0, 80) + "..." : v;
                    sb.append("[").append(k).append("] → ").append(display).append("\n");
                });
                yield sb.toString();
            }
            case "set" -> {
                String key = (String) args.get("key");
                String value = (String) args.get("value");
                if (key == null || key.isBlank()) yield "请指定 key";
                if (value == null || value.isBlank()) yield "请指定 value";
                boolean ok = configService.set(key, value, realUserId);
                yield ok ? "配置已更新: " + key + "（已生效）" : "配置更新失败: " + key;
            }
            case "delete" -> {
                String key = (String) args.get("key");
                if (key == null || key.isBlank()) yield "请指定 key";
                // 设为空字符串即删除效果
                boolean ok = configService.set(key, "", realUserId);
                yield ok ? "配置已清除: " + key + "（已恢复默认）" : "清除失败: " + key;
            }
            default -> "不支持的操作: " + action + "。支持: list / set / delete";
        };
    }
}
