package com.start.config;

import com.start.util.EnvResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    // ===== 健康指标：迁移结果（5.2 健康状态指标） =====
    public static volatile long lastMigrationAt = 0;
    public static volatile boolean lastMigrationSuccess = false;
    private static HikariDataSource dataSource;
    private static boolean initialized = false;

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

                String dbUrl = EnvResolver.resolve(props.getProperty("database.url",
                        "jdbc:mysql://localhost:3307/candybear_db" +
                                "?useUnicode=true" +
                                "&characterEncoding=utf8mb4" +
                                "&useSSL=false" +
                                "&allowPublicKeyRetrieval=true" +
                                "&serverTimezone=Asia/Shanghai"));

                config.setJdbcUrl(dbUrl);
                config.setUsername(EnvResolver.resolve(props.getProperty("database.user", "candybear")));
                config.setPassword(EnvResolver.resolve(props.getProperty("database.password", "")));

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
                if (dataSource != null) {
                    try { dataSource.close(); } catch (Exception ignored) {}
                    dataSource = null;
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    logger.error("❌ 数据库连接池初始化失败，机器人将停止启动");
                    logger.error("提示：请检查：");
                    logger.error("1. SSH隧道是否启动 (ssh -L 3307:localhost:3306 ...)");
                    logger.error("2. MySQL服务是否运行");
                    logger.error("3. 数据库用户密码是否正确");
                }
            }
        }

        throw new IllegalStateException("数据库连接池初始化失败，机器人不能以不完整 schema 启动");
    }

    /**
     * 测试基本连接
     */
    private static boolean testBasicConnection() {
        try {
            Properties props = loadProperties();
            String url = EnvResolver.resolve(props.getProperty("database.url",
                    "jdbc:mysql://localhost:3307/candybear_db"));
            String user = EnvResolver.resolve(props.getProperty("database.user", "candybear"));
            String password = EnvResolver.resolve(props.getProperty("database.password", ""));

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
            // 消息记录表（search_chat_history 等工具依赖）
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "session_id VARCHAR(100) NOT NULL," +
                "user_id VARCHAR(50) NOT NULL," +
                "content TEXT NOT NULL," +
                "raw_content TEXT," +
                "source_event_key VARCHAR(180)," +
                "is_robot_reply BOOLEAN DEFAULT FALSE," +
                "is_private BOOLEAN DEFAULT FALSE," +
                "group_id VARCHAR(50)," +
                "reply_to_id BIGINT," +
                "topics TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_msg_group (group_id)," +
                "INDEX idx_msg_user (user_id)," +
                "INDEX idx_msg_created (created_at DESC)," +
                "INDEX idx_msg_session (session_id)," +
                "UNIQUE KEY uq_msg_source_event (source_event_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "ALTER TABLE messages ADD COLUMN image_data TEXT COMMENT 'JSON image url+desc' AFTER content",
            "ALTER TABLE messages ADD COLUMN raw_content TEXT AFTER content",
            "ALTER TABLE messages ADD COLUMN source_event_key VARCHAR(180)",
            "ALTER TABLE messages ADD UNIQUE INDEX uq_msg_source_event (source_event_key)",
            "ALTER TABLE messages ADD COLUMN provider_message_id VARCHAR(64)",
            "ALTER TABLE messages ADD COLUMN raw_event_json JSON",
            "ALTER TABLE messages ADD COLUMN raw_segments_json JSON",
            "ALTER TABLE messages ADD COLUMN reply_provider_message_id VARCHAR(64)",
            "ALTER TABLE messages ADD COLUMN sender_type VARCHAR(16) NOT NULL DEFAULT 'USER'",
            "ALTER TABLE messages ADD COLUMN availability_status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE'",
            "ALTER TABLE messages ADD COLUMN received_at DATETIME(3) NULL",
            "ALTER TABLE messages ADD COLUMN retracted_at DATETIME(3) NULL",
            "ALTER TABLE messages ADD COLUMN redacted_at DATETIME(3) NULL",
            "ALTER TABLE messages ADD INDEX idx_msg_provider_scope (group_id, provider_message_id)",

            // 证据优先画像：原始消息部件和派生解析产物分开保存。
            """
            CREATE TABLE IF NOT EXISTS message_parts (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id BIGINT NOT NULL,
                part_index INT NOT NULL,
                part_type VARCHAR(32) NOT NULL,
                raw_part_json JSON NOT NULL,
                text_value TEXT NULL,
                source_url TEXT NULL,
                provider_file_id VARCHAR(255) NULL,
                target_id VARCHAR(64) NULL,
                mime_type VARCHAR(128) NULL,
                byte_size BIGINT NULL,
                sha256 CHAR(64) NULL,
                storage_key VARCHAR(512) NULL,
                availability_status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
                error_code VARCHAR(64) NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                UNIQUE KEY uq_message_part (message_id, part_index),
                INDEX idx_part_type_status (part_type, availability_status),
                INDEX idx_part_provider_file (provider_file_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS media_artifacts (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                part_id BIGINT NOT NULL,
                artifact_type VARCHAR(40) NOT NULL,
                parser_name VARCHAR(80) NOT NULL,
                parser_version VARCHAR(40) NOT NULL,
                input_sha256 CHAR(64) NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                content_json JSON NULL,
                confidence DECIMAL(4,3) NULL,
                language VARCHAR(32) NULL,
                error_code VARCHAR(64) NULL,
                error_detail TEXT NULL,
                started_at DATETIME(3) NULL,
                completed_at DATETIME(3) NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                UNIQUE KEY uq_media_artifact (part_id, artifact_type, parser_version, input_sha256),
                INDEX idx_artifact_status (status, created_at),
                INDEX idx_artifact_part (part_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS forward_message_edges (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                parent_part_id BIGINT NOT NULL,
                child_message_id BIGINT NULL,
                provider_node_id VARCHAR(128) NULL,
                depth INT NOT NULL,
                ordinal INT NOT NULL,
                original_sender_id VARCHAR(64) NULL,
                original_sender_name VARCHAR(255) NULL,
                original_sent_at DATETIME(3) NULL,
                raw_node_json JSON NULL,
                availability_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                UNIQUE KEY uq_forward_node (parent_part_id, depth, ordinal),
                INDEX idx_forward_child (child_message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS conversation_events (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id VARCHAR(64) NULL,
                event_type VARCHAR(40) NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
                topic_key VARCHAR(255) NULL,
                topic_text TEXT NULL,
                participant_ids JSON NULL,
                summary_text TEXT NULL,
                started_at DATETIME(3) NOT NULL,
                ended_at DATETIME(3) NULL,
                builder_version VARCHAR(40) NOT NULL,
                analysis_task_id BIGINT NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_event_group_time (group_id, started_at),
                INDEX idx_event_topic (topic_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS conversation_event_messages (
                event_id BIGINT NOT NULL,
                message_id BIGINT NOT NULL,
                ordinal INT NOT NULL,
                relation_role VARCHAR(32) NOT NULL,
                relevance DECIMAL(4,3) NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                PRIMARY KEY (event_id, message_id),
                INDEX idx_event_message (message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS model_analysis_tasks (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                task_key VARCHAR(255) NOT NULL,
                task_type VARCHAR(48) NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                priority INT NOT NULL DEFAULT 0,
                scope_group_id VARCHAR(64) NULL,
                scope_user_id VARCHAR(64) NULL,
                input_refs JSON NOT NULL,
                attempt_count INT NOT NULL DEFAULT 0,
                max_attempts INT NOT NULL DEFAULT 3,
                not_before DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                lease_owner VARCHAR(128) NULL,
                lease_until DATETIME(3) NULL,
                model_name VARCHAR(128) NULL,
                prompt_version VARCHAR(40) NULL,
                parser_version VARCHAR(40) NULL,
                result_json JSON NULL,
                error_code VARCHAR(64) NULL,
                error_detail TEXT NULL,
                input_tokens INT NULL,
                output_tokens INT NULL,
                cost_microunits BIGINT NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                UNIQUE KEY uq_analysis_task_key (task_key),
                INDEX idx_analysis_claim (status, not_before, priority),
                INDEX idx_analysis_scope (scope_user_id, scope_group_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_fact_candidates (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                subject_user_id VARCHAR(64) NOT NULL,
                source_group_id VARCHAR(64) NULL,
                kind VARCHAR(40) NOT NULL,
                epistemic_type VARCHAR(40) NOT NULL,
                claim_key VARCHAR(255) NOT NULL,
                claim TEXT NOT NULL,
                polarity VARCHAR(16) NOT NULL DEFAULT 'ASSERT',
                status VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
                stability VARCHAR(24) NOT NULL DEFAULT 'ONE_OFF',
                confidence DECIMAL(4,3) NOT NULL,
                scope VARCHAR(24) NOT NULL DEFAULT 'PERSONAL',
                first_observed_at DATETIME(3) NOT NULL,
                last_observed_at DATETIME(3) NOT NULL,
                valid_from DATETIME(3) NOT NULL,
                valid_until DATETIME(3) NULL,
                audit_rationale TEXT NULL,
                analysis_task_id BIGINT NULL,
                model_version VARCHAR(128) NULL,
                dedupe_key CHAR(64) NOT NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                UNIQUE KEY uq_profile_candidate_dedupe (dedupe_key),
                INDEX idx_candidate_subject (subject_user_id, status, kind),
                INDEX idx_candidate_validity (status, valid_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_facts (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                subject_user_id VARCHAR(64) NOT NULL,
                kind VARCHAR(40) NOT NULL,
                epistemic_type VARCHAR(40) NOT NULL,
                claim_key VARCHAR(255) NOT NULL,
                claim TEXT NOT NULL,
                polarity VARCHAR(16) NOT NULL DEFAULT 'ASSERT',
                status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                stability VARCHAR(24) NOT NULL,
                confidence DECIMAL(4,3) NOT NULL,
                scope VARCHAR(24) NOT NULL DEFAULT 'PERSONAL',
                first_observed_at DATETIME(3) NOT NULL,
                last_observed_at DATETIME(3) NOT NULL,
                valid_from DATETIME(3) NOT NULL,
                valid_until DATETIME(3) NULL,
                source_candidate_id BIGINT NOT NULL,
                confirmed_at DATETIME(3) NULL,
                superseded_by_fact_id BIGINT NULL,
                review_state VARCHAR(24) NOT NULL DEFAULT 'AUTO',
                version INT NOT NULL DEFAULT 1,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_fact_subject (subject_user_id, status, kind),
                INDEX idx_fact_claim (subject_user_id, claim_key, version),
                INDEX idx_fact_validity (status, valid_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_fact_evidence (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                candidate_id BIGINT NULL,
                fact_id BIGINT NULL,
                message_id BIGINT NOT NULL,
                part_id BIGINT NULL,
                artifact_id BIGINT NULL,
                event_id BIGINT NULL,
                evidence_role VARCHAR(24) NOT NULL DEFAULT 'SUPPORT',
                modality VARCHAR(24) NOT NULL,
                quote_or_span JSON NULL,
                availability_snapshot VARCHAR(32) NOT NULL,
                weight DECIMAL(4,3) NOT NULL DEFAULT 1.0,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_evidence_candidate (candidate_id),
                INDEX idx_evidence_fact (fact_id),
                INDEX idx_evidence_message (message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS group_atmosphere_events (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id VARCHAR(64) NOT NULL,
                event_id BIGINT NOT NULL,
                kind VARCHAR(40) NOT NULL,
                claim TEXT NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                confidence DECIMAL(4,3) NOT NULL,
                valid_from DATETIME(3) NOT NULL,
                valid_until DATETIME(3) NULL,
                analysis_task_id BIGINT NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_atmosphere_group (group_id, status, valid_from)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_fact_conflicts (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                existing_fact_id BIGINT NOT NULL,
                incoming_candidate_id BIGINT NOT NULL,
                conflict_type VARCHAR(40) NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
                resolution VARCHAR(40) NULL,
                resolved_by VARCHAR(64) NULL,
                resolved_at DATETIME(3) NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_conflict_status (status, created_at),
                INDEX idx_conflict_fact (existing_fact_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            // Legacy free-text cache is retained for compatibility and imported only as a candidate.
            """
            CREATE TABLE IF NOT EXISTS user_profiles (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id VARCHAR(64) NOT NULL,
                group_id VARCHAR(64) NULL,
                profile_text TEXT NULL,
                message_count_snapshot INT DEFAULT 0,
                last_message_id BIGINT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uq_legacy_profile_user_group (user_id, group_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            "ALTER TABLE profile_fact_candidates ADD COLUMN polarity VARCHAR(16) NOT NULL DEFAULT 'ASSERT' AFTER claim",
            "ALTER TABLE profile_facts ADD COLUMN polarity VARCHAR(16) NOT NULL DEFAULT 'ASSERT' AFTER claim",
            """
            INSERT IGNORE INTO profile_fact_candidates
                (subject_user_id, source_group_id, kind, epistemic_type, claim_key, claim, polarity, status,
                 stability, confidence, scope, first_observed_at, last_observed_at, valid_from, valid_until,
                 audit_rationale, analysis_task_id, model_version, dedupe_key)
            SELECT user_id, group_id, 'legacy_summary', 'MODEL_INFERENCE', 'legacy_profile', profile_text, 'ASSERT',
                   'CANDIDATE', 'ONE_OFF', 0.10, 'PERSONAL', COALESCE(updated_at, created_at, NOW()),
                   COALESCE(updated_at, created_at, NOW()), COALESCE(updated_at, created_at, NOW()), NULL,
                   '旧 profileText 仅作为低可信迁移候选；没有原始消息证据，不自动晋级', NULL, 'legacy-profile-v1',
                   SHA2(CONCAT('legacy|', user_id, '|', COALESCE(group_id, ''), '|', profile_text), 256)
            FROM user_profiles
            WHERE profile_text IS NOT NULL AND TRIM(profile_text) <> ''
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_fact_changes (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                entity_type VARCHAR(32) NOT NULL,
                entity_id BIGINT NOT NULL,
                from_status VARCHAR(24) NULL,
                to_status VARCHAR(24) NULL,
                old_snapshot JSON NULL,
                new_snapshot JSON NULL,
                reason_code VARCHAR(64) NOT NULL,
                actor_type VARCHAR(24) NOT NULL,
                actor_id VARCHAR(64) NULL,
                task_id BIGINT NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_fact_change_entity (entity_type, entity_id, created_at),
                INDEX idx_fact_change_actor (actor_type, actor_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS message_state_changes (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id BIGINT NOT NULL,
                from_status VARCHAR(32) NULL,
                to_status VARCHAR(32) NOT NULL,
                notice_event_hash CHAR(64) NULL,
                reason_code VARCHAR(64) NOT NULL,
                actor_id VARCHAR(64) NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_message_state (message_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS profile_privacy_requests (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                subject_user_id VARCHAR(64) NOT NULL,
                requester_user_id VARCHAR(64) NOT NULL,
                scope VARCHAR(64) NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                confirmation_hash CHAR(64) NOT NULL,
                expires_at DATETIME(3) NOT NULL,
                confirmed_at DATETIME(3) NULL,
                completed_at DATETIME(3) NULL,
                deleted_counts JSON NULL,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                INDEX idx_privacy_request_user (requester_user_id, status, created_at),
                INDEX idx_privacy_request_expiry (status, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            // 主动回复决策日志表
            "CREATE TABLE IF NOT EXISTS active_reply_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "group_id VARCHAR(50) NOT NULL," +
                "user_id VARCHAR(50) NOT NULL," +
                "message_content TEXT," +
                "decision VARCHAR(20)," +
                "decision_reason TEXT," +
                "confidence DOUBLE DEFAULT 0.5," +
                "replied_content TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_arl_group (group_id)," +
                "INDEX idx_arl_decision (decision)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            // Existing installations may have created this column as VARCHAR(100);
            // shadow cognition summaries are intentionally longer and must be retained.
            "ALTER TABLE active_reply_logs MODIFY COLUMN decision_reason TEXT",

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

            // 新增列（MySQL 不支持 ADD COLUMN IF NOT EXISTS，去掉该语法，重复执行时异常被 catch 处理）
            "ALTER TABLE long_term_memories ADD COLUMN trigger_at DATETIME NULL",
            "ALTER TABLE long_term_memories ADD COLUMN triggered BOOLEAN DEFAULT FALSE",
            "ALTER TABLE long_term_memories ADD COLUMN trigger_claimed_at DATETIME NULL",
            "ALTER TABLE long_term_memories ADD COLUMN keywords TEXT",
            "ALTER TABLE long_term_memories ADD COLUMN recall_count INT DEFAULT 0",
            "ALTER TABLE long_term_memories ADD COLUMN source VARCHAR(20) DEFAULT 'SELF_REPORTED'",
            "ALTER TABLE long_term_memories ADD COLUMN last_confirmed_at TIMESTAMP NULL",
            "ALTER TABLE long_term_memories ADD COLUMN last_seen_at TIMESTAMP NULL",
            "ALTER TABLE long_term_memories ADD COLUMN last_used_at TIMESTAMP NULL",
            "ALTER TABLE long_term_memories ADD COLUMN confidence DOUBLE DEFAULT 1.0",
            "ALTER TABLE long_term_memories ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE'",
            "ALTER TABLE long_term_memories ADD COLUMN expires_at TIMESTAMP NULL",

            // 知识库黑名单
            "CREATE TABLE IF NOT EXISTS knowledge_blacklist (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "pattern VARCHAR(500) NOT NULL COMMENT '被屏蔽的问题模式'," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_pattern (pattern(200))" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 知识库主表
            "CREATE TABLE IF NOT EXISTS knowledge_base (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "question_pattern TEXT NOT NULL," +
                "answer_template TEXT NOT NULL," +
                "category VARCHAR(100)," +
                "priority INT DEFAULT 5," +
                "keywords TEXT," +
                "hit_count INT DEFAULT 0," +
                "last_hit TIMESTAMP NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_question_pattern (question_pattern(300))" +
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

            // 糖果熊自身记忆表
            "CREATE TABLE IF NOT EXISTS bot_memories (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "group_id VARCHAR(50) NOT NULL," +
                "entry_type VARCHAR(20) NOT NULL," +
                "target VARCHAR(100)," +
                "detail TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_bm_group (group_id)," +
                "INDEX idx_bm_type (entry_type)," +
                "INDEX idx_bm_created (created_at DESC)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊日程表
            "CREATE TABLE IF NOT EXISTS candy_bear_schedule (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "schedule_date DATE NOT NULL," +
                "day_of_week VARCHAR(10)," +
                "time_slot VARCHAR(20)," +
                "start_time TIME NOT NULL," +
                "end_time TIME NOT NULL," +
                "activity VARCHAR(200)," +
                "location VARCHAR(100)," +
                "mood VARCHAR(50)," +
                "is_school_day BOOLEAN DEFAULT FALSE," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_cbs_date (schedule_date)," +
                "INDEX idx_cbs_time (schedule_date, start_time)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊人生引擎：story_arc（2~3周章节）
            "CREATE TABLE IF NOT EXISTS candy_bear_story_arcs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "arc_name VARCHAR(100) NOT NULL," +
                "start_date DATE NOT NULL," +
                "end_date DATE NOT NULL," +
                "summary TEXT," +
                "major_events TEXT," +
                "mood_trend VARCHAR(50)," +
                "active BOOLEAN DEFAULT TRUE," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊人生引擎：weekly_diary（每周日生成）
            "CREATE TABLE IF NOT EXISTS candy_bear_weekly_diaries (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "week_start DATE NOT NULL," +
                "week_end DATE NOT NULL," +
                "summary TEXT," +
                "major_events TEXT," +
                "emotion VARCHAR(50)," +
                "next_week_plan TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_week_start (week_start)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊人生引擎：daily_journal（每天凌晨生成昨日日记）
            "CREATE TABLE IF NOT EXISTS candy_bear_daily_journals (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "journal_date DATE NOT NULL UNIQUE," +
                "important_events TEXT," +
                "emotion VARCHAR(50)," +
                "summary TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊人生状态（单行表，随剧情演进更新）
            "CREATE TABLE IF NOT EXISTS candy_bear_life_state (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "school VARCHAR(100) DEFAULT ''," +
                "grade VARCHAR(20) DEFAULT '高二'," +
                "friends VARCHAR(500) DEFAULT '小雨,阿乐'," +
                "hobbies VARCHAR(500) DEFAULT '三角洲行动,洛克王国,崩铁,追番,画画,看小说'," +
                "recent_problem TEXT," +
                "current_goal TEXT," +
                "location VARCHAR(100) DEFAULT '北京'," +
                "health_note VARCHAR(500) DEFAULT '轻微心脏问题，不需每天上学'," +
                "updated_at DATE NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 糖果熊事件流：记录一天中实际发生的事（日程执行、聊天互动、心情变化）
            "CREATE TABLE IF NOT EXISTS candy_bear_event_log (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "event_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "event_date DATE NOT NULL," +
                "event_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL'," +
                "summary TEXT NOT NULL," +
                "emotion VARCHAR(20) DEFAULT ''," +
                "emotion_impact INT DEFAULT 0," +
                "source_group_id VARCHAR(50) DEFAULT ''," +
                "source_user_id VARCHAR(50) DEFAULT ''," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_el_date (event_date)," +
                "INDEX idx_el_type (event_type)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 周期任务（工具联动）：LLM 存入 prompt，调度线程到时取出发给 LLM 自由执行
            "CREATE TABLE IF NOT EXISTS recurring_tasks (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "user_id VARCHAR(50) NOT NULL," +
                "group_id VARCHAR(50)," +
                "task_name VARCHAR(100)," +
                "cron_expr VARCHAR(100) NOT NULL," +
                "trigger_prompt TEXT NOT NULL," +
                "expire_days INT DEFAULT 7," +
                "enabled BOOLEAN DEFAULT TRUE," +
                "last_fired_at TIMESTAMP NULL," +
                "next_fire_at TIMESTAMP NULL," +
                "fire_claimed_at TIMESTAMP NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_rt_next_fire (next_fire_at)," +
                "INDEX idx_rt_user_group (user_id, group_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "ALTER TABLE recurring_tasks ADD COLUMN fire_claimed_at TIMESTAMP NULL",

            // 用户职业（有状态，运势驱动位阶波动）
            "CREATE TABLE IF NOT EXISTS user_professions (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "user_id BIGINT NOT NULL," +
                "group_id VARCHAR(50) NOT NULL," +
                "profession_path VARCHAR(20) NOT NULL," +
                "profession_name VARCHAR(50) NOT NULL," +
                "tier INT DEFAULT 1," +
                "rarity VARCHAR(10) DEFAULT '普通'," +
                "combat_power INT DEFAULT 100," +
                "streak_good INT DEFAULT 0," +
                "streak_bad INT DEFAULT 0," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_user_group (user_id, group_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "ALTER TABLE user_professions ADD COLUMN best_tier INT DEFAULT 1",

            // 每日职业变动日志（运气漂移 + PK 明细）
            "CREATE TABLE IF NOT EXISTS profession_daily_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "user_id BIGINT NOT NULL," +
                "group_id VARCHAR(50) NOT NULL," +
                "log_date DATE NOT NULL," +
                "profession_path VARCHAR(20)," +
                "profession_name VARCHAR(50)," +
                "tier INT DEFAULT 1," +
                "rarity VARCHAR(10)," +
                "yesterday_power INT DEFAULT 0," +
                "base_power INT DEFAULT 0," +
                "power_from_luck INT DEFAULT 0," +
                "power_from_pk INT DEFAULT 0," +
                "final_power INT DEFAULT 0," +
                "luck_value INT DEFAULT 50," +
                "change_summary TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_daily_user_group_date (user_id, group_id, log_date)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // PK记录表
            "CREATE TABLE IF NOT EXISTS pk_records (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "attacker_id BIGINT NOT NULL," +
                "defender_id BIGINT NOT NULL," +
                "group_id VARCHAR(50) NOT NULL," +
                "attacker_tier INT DEFAULT 1," +
                "defender_tier INT DEFAULT 1," +
                "win BOOLEAN DEFAULT FALSE," +
                "power_change INT DEFAULT 0," +
                "is_bully BOOLEAN DEFAULT FALSE," +
                "pk_date DATE NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_pk_attacker_date (attacker_id, pk_date)," +
                "INDEX idx_pk_group_date (group_id, pk_date)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 运行时配置（热重载提示词、工具描述等，无需重启）
            "CREATE TABLE IF NOT EXISTS bot_config (" +
                "config_key VARCHAR(128) PRIMARY KEY," +
                "config_value TEXT NOT NULL," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "updated_by VARCHAR(32) DEFAULT 'system'" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 自我进化记录表
            "CREATE TABLE IF NOT EXISTS evolution_records (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "target_file VARCHAR(255) NOT NULL," +
                "reason VARCHAR(500)," +
                "result VARCHAR(20) NOT NULL COMMENT 'success / compile_fail / test_fail / package_fail / rollback / error'," +
                "error_message TEXT," +
                "git_pushed BOOLEAN DEFAULT FALSE," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_er_result (result)," +
                "INDEX idx_er_created (created_at DESC)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            // 工具审计记录表
            "CREATE TABLE IF NOT EXISTS tool_audit_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "tool_name VARCHAR(100) NOT NULL," +
                "caller_user_id VARCHAR(50)," +
                "group_id VARCHAR(50)," +
                "session_id VARCHAR(200)," +
                "args_summary TEXT," +
                "result_summary TEXT," +
                "rejected BOOLEAN DEFAULT FALSE," +
                "success BOOLEAN DEFAULT TRUE," +
                "error_message TEXT," +
                "latency_ms BIGINT DEFAULT 0," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_audit_tool (tool_name, created_at)," +
                "INDEX idx_audit_caller (caller_user_id, created_at)," +
                "INDEX idx_audit_group (group_id, created_at)," +
                "INDEX idx_audit_time (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        };

        List<String> failures = new ArrayList<>();
        for (String sql : migrations) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                logger.debug("迁移成功: {}", sql.substring(0, Math.min(60, sql.length())));
            } catch (SQLException e) {
                // MySQL 5.x 不支持 IF NOT EXISTS for columns/indexes，忽略已存在错误。
                String migrationError = e.getMessage();
                if (migrationError != null && (migrationError.contains("Duplicate column")
                        || migrationError.contains("Duplicate key name"))) {
                    logger.debug("迁移对象已存在，跳过: {}", sql.substring(0, Math.min(60, sql.length())));
                } else {
                    logger.error("迁移失败 ({}): {}", e.getMessage(), sql.substring(0, Math.min(60, sql.length())));
                    failures.add(e.getMessage() != null ? e.getMessage() : "unknown migration error");
                }
            }
        }
        if (!failures.isEmpty()) {
            lastMigrationAt = System.currentTimeMillis();
            lastMigrationSuccess = false;
            throw new IllegalStateException("数据库迁移失败 " + failures.size() + " 项: " + failures.get(0));
        }
        lastMigrationAt = System.currentTimeMillis();
        lastMigrationSuccess = true;
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
