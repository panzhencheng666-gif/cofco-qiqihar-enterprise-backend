package com.cofco.qiqihar.graintrade.production.infrastructure;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionV14MigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @Test
    void definesNormalizedFactCategoriesWithoutSeedingBusinessRecords() throws SQLException {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM platform.production_fact_definition")) {
                row.next();
                assertThat(row.getLong(1)).isEqualTo(31);
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM production.production_record")) {
                row.next();
                assertThat(row.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO platform.production_fact_definition
                        (code, category, label, value_type, decimal_precision, decimal_scale)
                    VALUES ('BAD_CATEGORY', 'OTHER', '非法', 'DECIMAL', 18, 4)
                    """)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void enforcesFactCodesApplicabilityAndProductCultivarAsDatabaseFacts() throws SQLException {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("""
                        INSERT INTO platform.production_fact_definition
                            (code, category, label, value_type, decimal_precision, decimal_scale)
                        VALUES ('QUALITY_TEST', 'QUALITY', '测试质量', 'DECIMAL', 18, 4)
                        """);
                statement.execute("""
                        INSERT INTO platform.production_fact_applicability
                            (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
                        VALUES ('QUALITY_TEST', 'SOYBEAN', 'FARMER', 'PRODUCTION', 'MONITORING', 10)
                        """);
                Savepoint cultivarCheck = connection.setSavepoint();
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO production.production_record
                            (record_id, product_code, object_type_code, region_code, cultivar_code,
                             survey_date, reported_at, cultivated_area_mu, yield_per_mu_kg,
                             status_code, last_modified_by)
                        VALUES ('bad-cultivar', 'CORN', 'FARMER', '230202', 'HEINONG_84',
                                DATE '2026-08-01', TIMESTAMPTZ '2026-08-02 08:00:00+08', 1, 1,
                                'DRAFT', 'test')
                        """)).isInstanceOf(SQLException.class);
                connection.rollback(cultivarCheck);
                statement.execute("""
                        INSERT INTO production.production_record
                            (record_id, product_code, object_type_code, region_code,
                             survey_date, reported_at, cultivated_area_mu, yield_per_mu_kg,
                             status_code, last_modified_by)
                        VALUES ('fact-owner', 'CORN', 'FARMER', '230202',
                                DATE '2026-08-01', TIMESTAMPTZ '2026-08-02 08:00:00+08', 1, 1,
                                'DRAFT', 'test')
                        """);
                Savepoint applicabilityCheck = connection.setSavepoint();
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO production.production_record_quality(record_id, quality_code, value)
                        VALUES ('fact-owner', 'QUALITY_TEST', 1)
                        """)).isInstanceOf(SQLException.class);
                connection.rollback(applicabilityCheck);
            } finally {
                connection.rollback();
            }
        }
    }
}
