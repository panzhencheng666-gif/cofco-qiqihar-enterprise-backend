package com.cofco.qiqihar.graintrade.production.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProductionObjectRuntimeGrantMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchema() {
        DATABASE.flyway().migrate();
    }

    @Test
    void freshMigrationGrantsOnlyRepositoryRequiredProductionObjectPrivileges() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();

        assertPrivileges("production.production_object_type_definition", "true:false:false:false");
        assertPrivileges("production.production_source_channel_definition", "true:false:false:false");
        assertPrivileges("production.production_business_role_definition", "true:false:false:false");
        assertPrivileges("production.monitoring_object", "true:true:true:false");
        assertPrivileges("production.monitoring_object_product", "true:true:false:true");
        assertPrivileges("production.monitoring_object_cultivar", "true:true:false:true");
        assertPrivileges("production.monitoring_object_role_assignment", "true:true:false:true");
        assertPrivileges("production.monitoring_object_revision", "true:true:false:false");
    }

    @Test
    void upgradesLiveV153WithTwelveForwardOnlyMigrations() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("153").migrate();

        assertPrivileges("production.monitoring_object", "false:false:false:false");
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(12);
        assertPrivileges("production.monitoring_object", "true:true:true:false");
        assertPrivileges("production.monitoring_object_revision", "true:true:false:false");
    }

    private void assertPrivileges(String relation, String expected) throws Exception {
        assertThat(query("""
                SELECT has_table_privilege('cofco_app','%s','SELECT') || ':' ||
                       has_table_privilege('cofco_app','%s','INSERT') || ':' ||
                       has_table_privilege('cofco_app','%s','UPDATE') || ':' ||
                       has_table_privilege('cofco_app','%s','DELETE')
                """.formatted(relation, relation, relation, relation))).isEqualTo(expected);
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
