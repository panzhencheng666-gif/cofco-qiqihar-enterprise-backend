package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SampleIdentityMergePrivilegeMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String ENTRY_SIGNATURE =
            "registry.apply_reviewed_sample_identity_merge(uuid,uuid,character)";

    @AfterEach
    void restoreLatestSchema() {
        DATABASE.flyway().migrate();
    }

    @Test
    void runtimeCanExecuteOnlyTheReviewBoundIdentityMergeEntry() throws Exception {
        DATABASE.flyway().migrate();
        assertThat(query("SELECT to_regprocedure('" + ENTRY_SIGNATURE + "') IS NOT NULL"))
                .isEqualTo("t");
        assertThat(query("""
                SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                       array_to_string(proconfig,',')
                FROM pg_proc
                WHERE oid=to_regprocedure('%s')
                """.formatted(ENTRY_SIGNATURE)))
                .isEqualTo("qiqihar_migration_owner:true:search_path=pg_catalog, registry, production, market, platform");
        assertThat(query("""
                SELECT has_function_privilege('cofco_app','%s','EXECUTE') || ':' ||
                       has_function_privilege('cofco_app',
                         'registry.apply_sample_subject_resolution(uuid,varchar)','EXECUTE') || ':' ||
                       has_table_privilege('cofco_app',
                         'registry.sample_subject_resolution_batch','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'registry.sample_subject_resolution_item','INSERT')
                """.formatted(ENTRY_SIGNATURE)))
                .isEqualTo("true:false:false:false");
        assertThat(query("""
                SELECT has_table_privilege('qiqihar_migration_owner',
                         'market.market_record_fact','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.market_record_fact','SELECT')
                """))
                .isEqualTo("true:true");
    }

    @Test
    void runtimeCannotStageAnIdentityMergeWithoutItsImmutableReviewEvidence() throws Exception {
        DATABASE.flyway().migrate();
        String before = query("SELECT count(*) FROM registry.sample_subject_resolution_batch");
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE cofco_app");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeQuery("""
                    SELECT registry.apply_reviewed_sample_identity_merge(
                      '95500000-0000-0000-0000-000000000901',
                      '95500000-0000-0000-0000-000000000902',
                      CAST(repeat('a',64) AS char(64)))
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity merge submitted request is missing");
            connection.rollback();
        }
        assertThat(query("SELECT count(*) FROM registry.sample_subject_resolution_batch"))
                .isEqualTo(before);
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
