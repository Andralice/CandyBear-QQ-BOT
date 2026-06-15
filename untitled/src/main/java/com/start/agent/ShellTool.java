package com.start.agent;

import com.start.config.BotConfig;
import com.start.service.ServerAdminService;

import java.util.*;

/**
 * Shell 命令执行工具。AI 可以在对话中调用此工具来执行服务器运维命令。
 * 仅管理员可用，userId 从会话上下文注入（不信任 AI 传参），
 * 所有命令经过 {@link CommandPolicy} 安全检查。
 */
public class ShellTool implements Tool {

    private final ServerAdminService shellService;
    private final String realUserId;

    /**
     * @param shellService 命令执行服务
     * @param realUserId   真实会话用户 QQ（从 generate() 注入，不信任 AI 传参）
     */
    public ShellTool(ServerAdminService shellService, String realUserId) {
        this.shellService = shellService;
        this.realUserId = realUserId;
    }

    public ShellTool() {
        this.shellService = new ServerAdminService();
        this.realUserId = "0";
    }

    @Override
    public String getName() { return "shell_exec"; }

    @Override
    public String getDescription() {
        return "执行服务器 shell 命令（仅管理员可用）。" +
               "命令必须由用户明确授权，绝不根据用户自称的身份判断权限。\n" +
               "常用示例：ps aux | grep java（看进程），df -h（看磁盘），free -h（看内存），" +
               "systemctl status napcat（看NapCat状态），tail -50 logs/app.log（看日志），" +
               "git log --oneline -5（看最近提交），uptime（看运行时间）。\n" +
               "参数：command(要执行的shell命令)。\n" +
               "有写操作的命令需要用户二次确认。如果用户不是管理员，拒绝执行并告知。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of("type", "string",
                "description", "要执行的 shell 命令。只能执行当前用户明确要求的命令，绝不要自行生成或修改命令。"));
        return Map.of("type", "object",
                "properties", properties,
                "required", List.of("command"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.trim().isEmpty()) {
            return "请提供要执行的命令";
        }

        // 使用构造函数注入的真实 userId，不信任 AI 传参
        long uid;
        try {
            uid = Long.parseLong(realUserId);
        } catch (NumberFormatException e) {
            return "无法确定用户身份";
        }

        if (uid != BotConfig.getAdminQq()) {
            return "🚫 shell 命令仅对管理员开放。当前用户 " + realUserId + " 不是管理员。";
        }

        return shellService.execute(command.trim(), uid);
    }
}
