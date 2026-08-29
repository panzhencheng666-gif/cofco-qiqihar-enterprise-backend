package com.cofco.qiqihar.graintrade.supplybalance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SupplyDemandBalanceMigrationIntegrationTest {
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
    void createsFormalProductBalanceWithUniqueKeyAndAppendOnlyHistory() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(153);
        assertThat(query("""
                SELECT string_agg(column_name,',' ORDER BY ordinal_position)
                FROM information_schema.columns
                WHERE table_schema='production' AND table_name='supply_demand_balance'
                """)).isEqualTo("region_code,survey_year,product_code,manual_values,notes,version,"
                + "created_by,created_at,updated_by,updated_at");
        assertThat(query("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='production' AND table_name='supply_demand_balance'
                  AND column_name IN ('status','review_status','confirmation_status')
                """)).isEqualTo("0");

        execute("""
                INSERT INTO production.supply_demand_balance(
                  region_code,survey_year,product_code,manual_values,notes,created_by,updated_by)
                VALUES('230208',2026,'CORN','{"OPENING_INVENTORY":10}',
                       '{"OPENING_INVENTORY":"测试"}','migration-test','migration-test')
                """);
        assertThatThrownBy(() -> execute("""
                INSERT INTO production.supply_demand_balance(
                  region_code,survey_year,product_code,created_by,updated_by)
                VALUES('230208',2026,'CORN','migration-test','migration-test')
                """)).hasMessageContaining("supply_demand_balance_pkey");
        assertThatThrownBy(() -> execute("""
                INSERT INTO production.supply_demand_balance(
                  region_code,survey_year,product_code,manual_values,created_by,updated_by)
                VALUES('230208',2026,'CORN','[]','migration-test','migration-test')
                """)).hasMessageContaining("supply_demand_balance_manual_values_check");

        execute("""
                INSERT INTO production.supply_demand_balance_history(
                  region_code,survey_year,product_code,manual_values,notes,
                  source_version,replaced_by)
                VALUES('230208',2026,'CORN','{}','{}',0,'migration-test')
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE production.supply_demand_balance_history SET manual_values='{}'
                WHERE region_code='230208'
                """)).hasMessageContaining("supply demand balance history is append-only");
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
