package com.start.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库迁移幂等性集成测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=DatabaseMigrationIdempotentIntegrationTest
 *
 * 验证开发计划第四阶段「数据库迁移重复执行不会破坏已有表」：
 * ensureTables 内部对 ALTER TABLE ADD COLUMN 用 try-catch 吞掉 "Duplicate column" 错误，
 * 重复执行应不抛错，且表结构保持完整。
 *
 * 用反射访问 private ensureTables 方法。
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class DatabaseMigrationIdempotentIntegrationTest {

    @Test
    void ensureTablesIsIdempotentAcrossRepeatedCalls() throws Exception {
        Method ensureTables = DatabaseConfig.class.getDeclaredMethod("ensureTables", Connection.class);
        ensureTables.setAccessible(true);

        try (Connection conn = DatabaseConfig.getConnection()) {
            // 跑 3 次
            ensureTables.invoke(null, conn);
            ensureTables.invoke(null, conn);
            ensureTables.invoke(null, conn);
            // 不抛错 = 幂等通过
        }
    }

    @Test
    void coreTablesExistAfterMigration() throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            // 关键表都应存在
            assertTableExists(conn, "messages");
            assertTableExists(conn, "long_term_memories");
            assertTableExists(conn, "recurring_tasks");
            assertTableExists(conn, "tool_audit_logs");
            assertTableExists(conn, "message_parts");
            assertTableExists(conn, "media_artifacts");
            assertTableExists(conn, "conversation_events");
            assertTableExists(conn, "profile_fact_candidates");
            assertTableExists(conn, "profile_facts");
            assertTableExists(conn, "model_analysis_tasks");
        }
    }

    @Test
    void criticalColumnsPresentAfterMigration() throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            assertColumnExists(conn, "messages", "image_data");
            assertColumnExists(conn, "messages", "raw_content");
            assertColumnExists(conn, "messages", "source_event_key");
            assertColumnExists(conn, "messages", "topics");
            assertColumnExists(conn, "messages", "provider_message_id");
            assertColumnExists(conn, "messages", "raw_event_json");
            assertColumnExists(conn, "messages", "raw_segments_json");
            assertColumnExists(conn, "messages", "availability_status");
            assertColumnExists(conn, "long_term_memories", "trigger_claimed_at");
            assertColumnExists(conn, "recurring_tasks", "fire_claimed_at");
        }
    }

    private static void assertTableExists(Connection conn, String tableName) throws Exception {
        ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null);
        assertTrue(rs.next(), "表 " + tableName + " 应存在");
        rs.close();
    }

    private static void assertColumnExists(Connection conn, String tableName, String columnName) throws Exception {
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName);
        assertTrue(rs.next(), tableName + "." + columnName + " 列应存在");
        rs.close();
    }
}
