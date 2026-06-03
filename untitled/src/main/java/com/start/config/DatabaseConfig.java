// config/DatabaseConfig.java
// config/DatabaseConfig.java
package com.start.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);
    private static HikariDataSource dataSource;
    private static boolean initialized = false;

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");

    private static String resolve(String value) {
        if (value == null) return null;
        Matcher m = ENV_PATTERN.matcher(value.trim());
        if (m.matches()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) return envValue;
            String defaultValue = m.group(2);
            if (defaultValue != null) return defaultValue;
            logger.warn("环境变量 {} 未设置", envName);
        }
        return value;
    }

    /**
     * 初始化数据库连接池（带重试机制）
     */
    public synchronized static void initConnectionPool() {
        if (initialized) return;

        logger.info("正在初始化数据库连接池...");

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                logger.info("连接尝试 {}/3", attempt);

                // 先测试基本连接
                if (!testBasicConnection()) {
                    logger.warn("基本连接测试失败，等待重试...");
                    Thread.sleep(2000);
                    continue;
                }

                // 加载配置
                Properties props = loadProperties();

                // 配置HikariCP
                HikariConfig config = new HikariConfig();

                String dbUrl = resolve(props.getProperty("database.url",
                        "jdbc:mysql://localhost:3307/candybear_db" +
                                "?useUnicode=true" +
                                "&characterEncoding=utf8mb4" +
                                "&useSSL=false" +
                                "&allowPublicKeyRetrieval=true" +
                                "&serverTimezone=Asia/Shanghai"));

                config.setJdbcUrl(dbUrl);
                config.setUsername(resolve(props.getProperty("database.user", "candybear")));
                config.setPassword(resolve(props.getProperty("database.password", "")));

                // 连接池配置
                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setConnectionTimeout(30000);
                config.setIdleTimeout(600000);
                config.setMaxLifetime(1800000);
                config.setLeakDetectionThreshold(60000);

                // MySQL优化
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

                // 连接测试
                config.setConnectionTestQuery("SELECT 1");
                config.setValidationTimeout(5000);

                dataSource = new HikariDataSource(config);

                // 测试连接池 + 自动迁移表结构
                try (Connection conn = dataSource.getConnection()) {
                    logger.info("✅ 数据库连接池初始化成功");
                    logger.info("连接URL: {}", dbUrl);
                    ensureTables(conn);
                    logger.info("连接池状态: {}", getPoolStatus());
                }

                initialized = true;
                return;

            } catch (Exception e) {
                logger.error("连接尝试 {} 失败: {}", attempt, e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    logger.error("❌ 数据库连接池初始化失败，将使用降级模式");
                    logger.error("提示：请检查：");
                    logger.error("1. SSH隧道是否启动 (ssh -L 3307:localhost:3306 ...)");
                    logger.error("2. MySQL服务是否运行");
                    logger.error("3. 数据库用户密码是否正确");
                }
            }
        }

        // 如果所有尝试都失败，设置一个标志
        logger.warn("警告：数据库连接失败，相关功能将不可用");
    }

    /**
     * 测试基本连接
     */
    private static boolean testBasicConnection() {
        try {
            Properties props = loadProperties();
            String url = resolve(props.getProperty("database.url",
                    "jdbc:mysql://localhost:3307/candybear_db"));
            String user = resolve(props.getProperty("database.user", "candybear"));
            String password = resolve(props.getProperty("database.password", ""));

            logger.info("测试连接: {}", url);

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                logger.info("✅ 基本连接测试成功");
                return true;
            }
        } catch (SQLException e) {
            logger.error("基本连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取数据库连接
     */
    public static Connection getConnection() throws SQLException {
        if (!initialized) {
            initConnectionPool();
        }

        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("数据库连接池不可用");
        }

        return dataSource.getConnection();
    }

    /**
     * 启动时自动建表和加列，幂等操作，重复执行不会出错。
     */
    private static void ensureTables(Connection conn) {
        String[] migrations = {
            // 核心表
            "CREATE TABLE IF NOT EXISTS long_term_memories (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "user_id VARCHAR(50) NOT NULL," +
                "group_id VARCHAR(50)," +
                "source_message_id BIGINT," +
                "content TEXT NOT NULL," +
                "memory_type VARCHAR(20) DEFAULT 'fact'," +
                "keywords TEXT," +
                "importance INT DEFAULT 1," +
                "vector_data JSON," +
                "last_recalled TIMESTAMP NULL," +
                "recall_count INT DEFAULT 0," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_ltm_user_group (user_id, group_id)," +
                "INDEX idx_ltm_type (memory_type)," +
                "INDEX idx_ltm_importance (importance DESC)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 新增列（忽略已存在的错误）
            "ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS trigger_at DATETIME NULL",
            "ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS triggered BOOLEAN DEFAULT FALSE",
            "ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS keywords TEXT",
            "ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS recall_count INT DEFAULT 0",

            // 知识库黑名单
            "CREATE TABLE IF NOT EXISTS knowledge_blacklist (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "pattern VARCHAR(500) NOT NULL COMMENT '被屏蔽的问题模式'," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_pattern (pattern(200))" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // group_mood 表
            "CREATE TABLE IF NOT EXISTS group_mood (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "group_id VARCHAR(50) NOT NULL," +
                "mood INT DEFAULT 50," +
                "last_topic_throw_time BIGINT DEFAULT 0," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_group_id (group_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        };

        for (String sql : migrations) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                logger.debug("迁移成功: {}", sql.substring(0, Math.min(60, sql.length())));
            } catch (SQLException e) {
                // MySQL 5.x 不支持 IF NOT EXISTS for columns，忽略 "Duplicate column" 错误
                if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                    logger.debug("列已存在，跳过: {}", sql.substring(0, Math.min(60, sql.length())));
                } else {
                    logger.warn("迁移跳过 ({}): {}", e.getMessage(), sql.substring(0, Math.min(60, sql.length())));
                }
            }
        }
        logger.info("数据库表结构迁移完成");
    }

    /**
     * 关闭连接池
     */
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("数据库连接池已关闭");
        }
    }

    /**
     * 获取连接池状态
     */
    public static String getPoolStatus() {
        if (dataSource == null) return "连接池未初始化";

        try {
            var pool = dataSource.getHikariPoolMXBean();
            return String.format("活跃=%d, 空闲=%d, 等待=%d, 总计=%d",
                    pool.getActiveConnections(),
                    pool.getIdleConnections(),
                    pool.getThreadsAwaitingConnection(),
                    pool.getTotalConnections());
        } catch (Exception e) {
            return "获取状态失败: " + e.getMessage();
        }
    }

    /**
     * 加载配置文件
     */
    private static Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream is = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
                logger.info("加载配置文件成功");
            }
        } catch (Exception e) {
            logger.error("加载配置文件失败，使用默认值");
        }

        return props;
    }
    public static HikariDataSource getDataSource() {
        if (!initialized) {
            initConnectionPool();
        }
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("数据库连接池初始化失败或已关闭");
        }
        return dataSource;
    }
}