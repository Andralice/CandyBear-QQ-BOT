package com.start.agent;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 重启机器人工具 —— 编译成功后调用，触发自身重启
 */
public class RestartBotTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(RestartBotTool.class);
    private final String realUserId;

    public RestartBotTool() { this.realUserId = "0"; }
    public RestartBotTool(String realUserId) { this.realUserId = realUserId; }

    @Override public String getName() { return "restart_bot"; }

    @Override
    public String getDescription() {
        return "重启糖果熊机器人自身，使代码改动生效。" +
               "编译成功后才调用。重启前会有5秒延迟确保回复消息已发出。" +
               "仅管理员(归儿)可用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "confirm", Map.of("type", "boolean",
                                "description", "是否确认重启？必须为 true。这会让糖果熊短暂离线。")
                ),
                "required", List.of("confirm"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        long uid;
        try { uid = Long.parseLong(realUserId); }
        catch (NumberFormatException e) { return "无法确定用户身份"; }
        if (uid != BotConfig.getAdminQq()) {
            return "重启功能仅对归儿开放。";
        }

        Object confirmObj = args.get("confirm");
        boolean confirmed = confirmObj instanceof Boolean b && b;
        if (!confirmed) {
            return "请设置 confirm=true 来确认重启。";
        }

        String os = System.getProperty("os.name", "").toLowerCase();

        // 延迟重启线程（确保回复消息先发出去）
        Thread restartThread = new Thread(() -> {
            try {
                logger.info("将在5秒后重启...");
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                if (os.contains("win")) {
                    // Windows: 发送提示，不自动重启（开发环境手动操作更安全）
                    logger.info("Windows 环境，请手动重启: cd target && java -jar untitled-1.0-SNAPSHOT.jar");
                    // 不退出进程，让用户手动操作
                } else {
                    // Linux: 用 screen 重启
                    String jarDir = "/opt/qq-bot";
                    String jarName = "untitled-1.0-SNAPSHOT.jar";

                    // 杀掉旧 screen
                    new ProcessBuilder("screen", "-S", "qq-bot", "-X", "quit")
                            .start()
                            .waitFor(3, TimeUnit.SECONDS);

                    // 等旧进程退出
                    Thread.sleep(2000);

                    // 杀掉残留的 java 进程
                    new ProcessBuilder("pkill", "-f", jarName)
                            .start()
                            .waitFor(2, TimeUnit.SECONDS);
                    Thread.sleep(1000);

                    // 启动新 screen
                    new ProcessBuilder("bash", "-c",
                            "cd " + jarDir + " && " +
                            "source .env 2>/dev/null || true; " +
                            "screen -S qq-bot -d -m java -Xms256m -Xmx768m -jar " + jarName + " > qq-bot.log 2>&1")
                            .start();

                    logger.info("重启信号已发送");
                }
            } catch (Exception e) {
                logger.error("重启失败", e);
            }
        }, "bot-restarter");
        restartThread.setDaemon(false);
        restartThread.start();

        if (os.contains("win")) {
            return "代码已编译通过，Windows 环境请手动重启。运行: java -jar target/untitled-1.0-SNAPSHOT.jar";
        }
        return "正在重启... 5秒后断开。糖果熊马上回来~";
    }
}
