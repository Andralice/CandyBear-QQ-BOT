package com.start.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.agent.QueryMessageContextTool;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceMessageContextTest {
    @Test void realSqlSupportsLatestCenterPaginationAndPrivateIsolation() throws Exception {
        try (HikariDataSource db = new HikariDataSource()) {
            db.setJdbcUrl("jdbc:h2:mem:message_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            try (var connection = db.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE messages (id BIGINT PRIMARY KEY, provider_message_id VARCHAR, "
                        + "user_id VARCHAR, sender_type VARCHAR, group_id VARCHAR, session_id VARCHAR, content VARCHAR, "
                        + "raw_content VARCHAR, reply_to_id BIGINT, reply_provider_message_id VARCHAR, "
                        + "availability_status VARCHAR, created_at TIMESTAMP, retracted_at TIMESTAMP)");
                statement.execute("INSERT INTO messages(id,group_id,session_id,content) VALUES "
                        + "(1,'g','s','一'),(2,'g','s','二'),(3,'other','s','群外'),"
                        + "(4,NULL,'private-a','私聊甲'),(5,NULL,'private-b','私聊乙'),(6,'g','s','撤回')");
                statement.execute("UPDATE messages SET retracted_at=CURRENT_TIMESTAMP WHERE id=6");
                statement.execute("UPDATE messages SET user_id='u' WHERE id=2");
                statement.execute("UPDATE messages SET user_id='other-user' WHERE id=1");
            }
            EvidenceQueryRepository repo = new EvidenceQueryRepository() {
                @Override public DataSource getDataSource() { return db; }
            };
            var json = new ObjectMapper();
            var group = new QueryMessageContextTool(repo, new MessageRepository(), "u", "g", "s");
            var latest = json.readTree(group.execute(Map.of("limit", 1)));
            assertEquals("OK", latest.path("status").asText());
            assertEquals(2, latest.path("items").get(0).path("id").asLong());
            assertEquals("u", latest.path("caller_user_id").asText());
            assertTrue(latest.path("items").get(0).path("is_current_requester").asBoolean());
            var older = json.readTree(group.execute(Map.of("cursor", 2, "limit", 12)));
            assertEquals(1, older.path("items").size());
            assertEquals(1, older.path("items").get(0).path("id").asLong());
            assertFalse(older.path("items").get(0).path("is_current_requester").asBoolean());
            assertEquals(1, repo.messageContext("g", "s", 1, 0, 20).size());
            var privateTool = new QueryMessageContextTool(repo, new MessageRepository(), "u", null, "private-a");
            var privateResult = json.readTree(privateTool.execute(Map.of()));
            assertEquals("OK", privateResult.path("status").asText());
            assertEquals(4, privateResult.path("items").get(0).path("id").asLong());
            assertEquals(1, privateResult.path("items").size());
            assertEquals("ERROR", json.readTree(group.execute(Map.of("group_id", "other"))).path("status").asText());
        }
    }
}
