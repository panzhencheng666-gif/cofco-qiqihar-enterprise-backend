package com.cofco.qiqihar.graintrade.supply.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SupplyTemporalMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview", "evidence",
        "registry"
    };

    @AfterEach
    void restoreLatestSharedSchemaAndSecurityFixtures() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void replaysEmptyAndExistingDatabasesWithoutGuessingAmbiguousPeriods() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("86").migrate();
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                      survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                    VALUES('period-evidence-production','CORN','FARMER','230200',DATE '2026-08-10',now(),100,500,
                      'APPROVED','migration-test')
                    """);
            statement.execute("""
                    INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,
                      approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest)
                    VALUES('10000000-0000-0000-0000-000000000001','PRODUCTION','period-evidence-production',0,
                      'APPROVED',now(),'PASSED','CORN','230200','2026/27','migration-source-digest')
                    """);
            statement.execute("""
                    INSERT INTO supply.manual_input_decision(manual_input_id,product_code,region_code,marketing_year,
                      role_code,value,unit_code,reason,status_code,decided_by,approved_at,version)
                    VALUES('10000000-0000-0000-0000-000000000002','CORN','230200','2026/27',
                      'OPENING_INVENTORY',3,'万吨','历史记录没有期间证据','APPROVED','migration-test',now(),0)
                    """);
        }

        MigrateResult existingReplay = DATABASE.flywayToVersion("88").migrate();

        assertThat(existingReplay.migrationsExecuted).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM supply.source_release")).isOne();
        assertThat(queryString("""
                SELECT COALESCE(period_code,'UNASSIGNED') || ':' || temporal_governance_state || ':'
                  || COALESCE(survey_year::text,'NO_YEAR') || ':' || COALESCE(survey_quarter,'NO_QUARTER')
                FROM supply.source_release WHERE source_record_id='period-evidence-production'
                """)).isEqualTo("UNASSIGNED:PENDING_GOVERNANCE:NO_YEAR:NO_QUARTER");
        assertThat(queryString("""
                SELECT COALESCE(period_code,'UNASSIGNED') || ':' || temporal_governance_state
                FROM supply.manual_input_decision WHERE reason='历史记录没有期间证据'
                """)).isEqualTo("UNASSIGNED:PENDING_GOVERNANCE");
        assertThat(queryString("""
                SELECT string_agg(code || ':' || precision || ':' || COALESCE(survey_quarter,'ANNUAL'),',' ORDER BY sort_order)
                FROM platform.supply_survey_period
                """)).isEqualTo("2026:YEAR:ANNUAL,2026-Q3:QUARTER:Q3,2026-Q4:QUARTER:Q4");
        assertThat(queryLong("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='supply_survey_period'
                  AND column_name IN('starts_on','ends_on')
                """)).isZero();
        assertThat(DATABASE.flywayToVersion("88").migrate().migrationsExecuted).isZero();

        resetDatabase();
        MigrateResult emptyReplay = DATABASE.flywayToVersion("88").migrate();
        assertThat(emptyReplay.migrationsExecuted).isGreaterThan(80);
        assertThat(queryString("SELECT max(version::integer)::text FROM public.flyway_schema_history WHERE success"))
                .isEqualTo("88");
        assertThat(queryLong("SELECT count(*) FROM supply.calculation_run")).isZero();
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private long queryLong(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
