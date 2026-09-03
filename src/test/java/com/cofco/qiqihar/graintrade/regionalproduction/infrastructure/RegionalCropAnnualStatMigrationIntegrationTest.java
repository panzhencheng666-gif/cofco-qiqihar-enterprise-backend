package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RegionalCropAnnualStatMigrationIntegrationTest {
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
    void createsOneFormalCountyAnnualStatWithGeneratedOutputAndAppendOnlyHistory()
            throws Exception {
        resetDatabase();
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(171);

        assertThat(query("""
                SELECT string_agg(column_name,',' ORDER BY ordinal_position)
                FROM information_schema.columns
                WHERE table_schema='production' AND table_name='regional_crop_annual_stat'
                """)).isEqualTo(
                "region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,total_output_kg," +
                "version,created_by,created_at,updated_by,updated_at");
        assertThat(query("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='production' AND table_name='regional_crop_annual_stat'
                  AND column_name IN ('status','review_status','confirmation_status')
                """)).isEqualTo("0");

        execute("""
                INSERT INTO production.regional_crop_annual_stat(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                  created_by,updated_by)
                VALUES('230208',2026,'CORN',2.5,3.2,'migration-test','migration-test')
                """);
        assertThat(query("""
                SELECT planted_area_mu || ':' || yield_per_mu_kg || ':' || total_output_kg || ':' || version
                FROM production.regional_crop_annual_stat
                WHERE region_code='230208' AND data_year=2026 AND product_code='CORN'
                """)).isEqualTo("2.5000:3.2000:8.0000:0");

        execute("""
                INSERT INTO production.regional_crop_annual_stat(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                  created_by,updated_by)
                VALUES('230208',2026,'SOYBEAN',4.5,NULL,'migration-test','migration-test')
                """);
        assertThat(query("""
                SELECT planted_area_mu || ':' || COALESCE(yield_per_mu_kg::text,'NULL') || ':' ||
                       COALESCE(total_output_kg::text,'NULL')
                FROM production.regional_crop_annual_stat
                WHERE region_code='230208' AND data_year=2026 AND product_code='SOYBEAN'
                """)).isEqualTo("4.5000:NULL:NULL");

        assertThatThrownBy(() -> execute("""
                INSERT INTO production.regional_crop_annual_stat(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                  created_by,updated_by)
                VALUES('230208',2026,'CORN',1,1,'migration-test','migration-test')
                """)).hasMessageContaining("regional_crop_annual_stat_pkey");
        assertThatThrownBy(() -> execute("""
                INSERT INTO production.regional_crop_annual_stat(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                  created_by,updated_by)
                VALUES('230208',2025,'CORN',-1,1,'migration-test','migration-test')
                """)).hasMessageContaining("regional_crop_annual_stat_planted_area_mu_check");

        execute("""
                INSERT INTO production.regional_crop_annual_stat_history(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                  total_output_kg,source_version,replaced_by)
                VALUES('230208',2026,'CORN',2.5,3.2,8,0,'migration-test')
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE production.regional_crop_annual_stat_history
                SET planted_area_mu=9 WHERE region_code='230208'
                """)).hasMessageContaining("regional crop annual stat history is append-only");
        assertThatThrownBy(() -> execute("""
                DELETE FROM production.regional_crop_annual_stat_history WHERE region_code='230208'
                """)).hasMessageContaining("regional crop annual stat history is append-only");
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

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
