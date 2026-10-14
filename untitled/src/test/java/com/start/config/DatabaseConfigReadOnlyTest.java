package com.start.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DatabaseConfigReadOnlyTest {
    @Test
    void readOnlyInitializationSkipsSchemaMigration() throws Exception {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:readonly_schema;MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection connection = dataSource.getConnection()) {
                DatabaseConfig.initializeSchema(connection, true);
                try (var tables = connection.getMetaData().getTables(null, null, "MESSAGES", new String[]{"TABLE"})) {
                    assertFalse(tables.next());
                }
            }
        }
    }
}
