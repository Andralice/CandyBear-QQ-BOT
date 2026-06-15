package com.start.service;

import com.start.agent.CommandPolicy;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * 服务器管理服务。执行 shell 命令，含安全策略、超时、输出截断。
 * 只允许管理员使用，工作目录锁定在项目路径。
 */
public class ServerAdminService {

    private static final Logger logger = LoggerFactory.getLogger(ServerAdminService.class);

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final Path WORK_DIR = Path.of("/opt/qq-bot");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shell-exec");
        t.setDaemon(true);
        return t;
    });

    // 待确认的命令（30秒窗口）
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    private static class PendingCommand {
        final String command;
        final long createdAt;

        PendingCommand(String command) {
            this.command = command;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > 30_000;
        }
    }

    /**
     * 执行 shell 命令。返回结果字符串。
     *
     * @param command 要执行的命令
     * @param userId  发起者 QQ
     * @return 执行结果
     */
    public String execute(String command, long userId) {
        long adminQq = BotConfig.getAdminQq();
        CommandPolicy.Verdict verdict = CommandPolicy.evaluate(command, userId, adminQq);

        if (verdict == CommandPolicy.Verdict.DENY) {
            logger.warn("🚫 [Shell] 管理员 {} 的命令被拦截: {}", userId, command);
            return "🚫 命令被安全策略拦截\n" + "命令: " + command + "\n" +
                   "原因: 不在白名单或包含危险模式\n" +
                   "如需执行，请 SSH 到服务器操作。";
        }

        if (verdict == CommandPolicy.Verdict.NEED_CONFIRM) {
            String token = String.valueOf(System.currentTimeMillis() % 100000);
            pendingCommands.put(token, new PendingCommand(command));
            logger.warn("⚠️ [Shell] 管理员 {} 的命令需确认: {} (token={})", userId, command, token);
            return "⚠️ 该命令需要二次确认\n" +
                   "命令: " + command + "\n" +
                   "回复 \"确认执行 " + token + "\" 来执行，30秒内有效";
        }

        return doExecute(command);
    }

    /**
     * 二次确认后执行。
     */
    public String confirmExecute(String token, long userId) {
        long adminQq = BotConfig.getAdminQq();
        if (userId != adminQq) {
            return "🚫 只有管理员可以执行此操作。";
        }

        PendingCommand pending = pendingCommands.remove(token);
        if (pending == null) {
            return "⚠️ 未找到待确认的命令，或已过期。";
        }
        if (pending.isExpired()) {
            return "⚠️ 确认已过期（超过30秒），请重新执行。";
        }

        logger.info("✅ [Shell] 管理员 {} 确认执行: {}", userId, pending.command);
        return doExecute(pending.command);
    }

    /**
     * 实际执行命令。
     */
    private String doExecute(String command) {
        long start = System.currentTimeMillis();
        logger.info("🔧 [Shell] 执行: {}", command);

        try {
            ProcessBuilder pb = new ProcessBuilder();
            // Windows 用 cmd，Linux 用 bash
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            pb.directory(WORK_DIR.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            Future<String> future = executor.submit(() -> {
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                        // 实时截断
                        if (out.length() > MAX_OUTPUT_CHARS + 500) break;
                    }
                }
                return out.toString();
            });

            String output;
            try {
                output = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                future.cancel(true);
                long elapsed = System.currentTimeMillis() - start;
                logger.warn("⏰ [Shell] 命令超时 ({}ms): {}", elapsed, command);
                return String.format("⏰ 命令超时（%d秒限制）\n命令: %s", TIMEOUT_SECONDS, command);
            }

            long elapsed = System.currentTimeMillis() - start;
            String trimmed = output.length() > MAX_OUTPUT_CHARS
                    ? output.substring(0, MAX_OUTPUT_CHARS) + "\n... [截断，共 " + output.length() + " 字符]"
                    : output;

            if (trimmed.isBlank()) {
                trimmed = "(命令执行完毕，无输出)";
            }

            logger.info("✅ [Shell] 完成 ({}ms, {} chars): {}", elapsed, trimmed.length(), command);
            return String.format("📟 %s\n耗时: %dms\n\n%s", command, elapsed, trimmed.stripTrailing());

        } catch (Exception e) {
            logger.error("❌ [Shell] 执行失败: {}", command, e);
            return "❌ 执行失败: " + e.getMessage();
        }
    }
}
