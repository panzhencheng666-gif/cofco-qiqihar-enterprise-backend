package com.cofco.qiqihar.graintrade.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class BusinessEventBacklogAuthorizationMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSharedSchemaAndSecurityFixtures() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void upgradesAnInstalledV112ConsumerThroughV113AndV114WithoutChangingV112() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("112").migrate().migrationsExecuted)
                .isGreaterThan(100);
        assertThat(queryInteger("""
                SELECT checksum FROM public.flyway_schema_history WHERE version='112'
                """)).isEqualTo(819644878);
        execute("""
                SELECT platform.ensure_business_event_consumer(
                  'def-103-segmented-upgrade','pre-v114-instance',0)
                """);

        assertThat(DATABASE.flywayToVersion("113").migrate().migrationsExecuted).isOne();
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isOne();

        assertThat(queryString("""
                SELECT lifecycle_status || ':' || retirement_reason
                FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id='def-103-segmented-upgrade'
                """)).isEqualTo("EXPIRED:MIGRATION_RESTART");
        assertThat(queryLong("""
                SELECT count(*) FROM platform.business_event_consumer_lifecycle_event
                WHERE consumer_id='def-103-segmented-upgrade'
                  AND lifecycle_status='EXPIRED' AND reason_code='MIGRATION_RESTART'
                """)).isOne();
        assertThat(queryString("""
                SELECT version FROM public.flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """)).isEqualTo("114");
        assertThat(queryInteger("""
                SELECT checksum FROM public.flyway_schema_history WHERE version='112'
                """)).isEqualTo(819644878);
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private int queryInteger(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }
}
