package com.start;

import com.start.config.DatabaseConfig;
import com.start.repository.UserAffinityRepository;
import com.start.runtime.conversation.InMemoryConversationStateStore;
import com.start.runtime.trace.WebDashboardListener;
import com.start.service.BaiLianService;
import com.start.service.ConversationInterpreter;
import com.start.service.KeywordKnowledgeService;
import com.start.service.ManualMessageReplayService;
import com.start.service.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

/** Standalone, read-only HTTP replay process with no OneBot connection. */
public final class ReplayOnlyApplication {
    private static final Logger logger = LoggerFactory.getLogger(ReplayOnlyApplication.class);

    private ReplayOnlyApplication() {}

    public static void run() throws InterruptedException {
        BaiLianService ai = null;
        WebDashboardListener dashboard = null;
        try {
            DatabaseConfig.initReadOnlyConnectionPool();
            var dataSource = DatabaseConfig.getDataSource();
            verifyReadOnlyDatabase(dataSource);
            KeywordKnowledgeService knowledge = new KeywordKnowledgeService(dataSource);
            ai = new BaiLianService(knowledge,
                    new UserAffinityRepository(dataSource), new TtsService(false), false, false);
            var profile = ai.snapshotReplayProfile();
            if (profile.candidate() == null || profile.candidate().apiKey().isBlank()) {
                throw new IllegalStateException("回放测试启动失败：未配置可用主模型密钥");
            }

            dashboard = new WebDashboardListener(true);
            ConversationInterpreter replayInterpreter = new ConversationInterpreter(
                    new InMemoryConversationStateStore(new java.util.concurrent.ConcurrentHashMap<>(),
                            new java.util.concurrent.ConcurrentHashMap<>(),
                            new java.util.concurrent.ConcurrentHashMap<>(),
                            new java.util.concurrent.ConcurrentHashMap<>()),
                    ai.getAiDatabaseService());
            dashboard.setReplayService(new ManualMessageReplayService(dataSource,
                    ai::snapshotReplayProfile, ai::generateReplay, replayInterpreter));
            dashboard.start();
            if (WebDashboardListener.getInstance() != dashboard) {
                throw new IllegalStateException("回放测试 HTTP 服务启动失败");
            }

            BaiLianService shutdownAi = ai;
            WebDashboardListener shutdownDashboard = dashboard;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> shutdown(shutdownDashboard, shutdownAi), "Replay-Only-Shutdown"));
            logger.info("回放测试程序已启动；NapCat 未连接；Dashboard 地址 http://{}:{}",
                    System.getProperty("dashboard.replay.host",
                            System.getenv().getOrDefault("DASHBOARD_REPLAY_HOST", "127.0.0.1")),
                    System.getProperty("dashboard.replay.port",
                            System.getenv().getOrDefault("DASHBOARD_REPLAY_PORT", "8766")));
            new CountDownLatch(1).await();
        } finally {
            shutdown(dashboard, ai);
        }
    }

    private static void shutdown(WebDashboardListener dashboard, BaiLianService ai) {
        if (dashboard != null) dashboard.stop();
        if (ai != null) ai.close();
        DatabaseConfig.close();
    }

    private static void verifyReadOnlyDatabase(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT @@session.transaction_read_only")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("回放测试拒绝启动：数据库会话未处于只读状态");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("回放测试拒绝启动：无法验证数据库只读会话", e);
        }
    }
}
