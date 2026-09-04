package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class FormalSampleFieldUnificationMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchema() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void upgradesV160DataWithoutDesignSampleDependencyAndInstallsGovernedDeletion()
            throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("160").migrate().migrationsExecuted)
                .isEqualTo(160);
        seedV160FormalSampleAndResolution();

        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(16);

        assertThat(query("""
                SELECT object_type.code || ':' || object_type.name
                FROM platform.object_type object_type
                WHERE object_type.code='AGRICULTURAL_INPUT_STORE'
                """)).isEqualTo("AGRICULTURAL_INPUT_STORE:农资店");
        assertThat(query("""
                SELECT count(*)=4 FROM platform.market_core_field_definition
                WHERE code LIKE 'AGRI_INPUT_%'
                """)).isEqualTo("t");
        assertThat(query("""
                SELECT count(*)=(SELECT count(*)*4 FROM platform.product)
                FROM platform.market_core_field_applicability
                WHERE field_code LIKE 'AGRI_INPUT_%'
                """)).isEqualTo("t");
        assertThat(query("""
                SELECT object_type_code || ':' || trade_direction || ':' ||
                  purchase_base_price::text || ':' || sale_base_price::text || ':' ||
                  actual_trade_price::text
                FROM market.market_record WHERE record_id='v162-preserved-market'
                """)).isEqualTo("TRADER:BOTH:2300.0000:2380.0000:2460.0000");
        assertThat(query("""
                SELECT count(*) FROM registry.sample_subject_resolution_item
                WHERE batch_id='16200000-0000-0000-0000-000000000010'
                  AND target_sample_point_id='16200000-0000-0000-0000-000000000001'
                  AND deleted_target_sample_point_id IS NULL
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT count(*) FROM registry.sample_subject_resolution_revision
                WHERE resolution_revision_id='16200000-0000-0000-0000-000000000011'
                  AND target_sample_point_id='16200000-0000-0000-0000-000000000001'
                  AND deleted_target_sample_point_id IS NULL
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT count(*) FROM platform.access_role_permission
                WHERE role_code='SYSTEM_ADMIN' AND permission_code='FORMAL_SAMPLE_DELETE'
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT count(*) FROM platform.access_role_permission
                WHERE role_code='SYSTEM_ADMIN' AND permission_code='FORMAL_SAMPLE_MANAGE'
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT count(*) FROM registry.formal_sample_point_profile
                WHERE sample_point_id='16200000-0000-0000-0000-000000000001'
                """)).isEqualTo("0");
        assertThat(query("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema='registry'
                  AND table_name='sample_point'
                  AND column_name='maintainer_subject_id'
                """)).isEqualTo("YES");
        assertThat(query("""
                SELECT has_table_privilege(
                    'qiqihar_enterprise_runtime','registry.formal_sample_point_profile','SELECT')
                  AND has_table_privilege(
                    'qiqihar_enterprise_runtime','registry.formal_sample_point_profile','INSERT')
                  AND has_table_privilege(
                    'qiqihar_enterprise_runtime','registry.formal_sample_point_profile','UPDATE')
                  AND NOT has_table_privilege(
                    'qiqihar_enterprise_runtime','registry.formal_sample_point_profile','DELETE')
                """)).isEqualTo("t");
        assertThat(query("""
                SELECT p.prosecdef AND pg_get_userbyid(p.proowner)='qiqihar_migration_owner'
                  AND NOT has_function_privilege('public',p.oid,'EXECUTE')
                  AND has_function_privilege('qiqihar_enterprise_runtime',p.oid,'EXECUTE')
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='registry' AND p.proname='delete_formal_sample_point'
                """)).isEqualTo("t");
        assertThat(query("""
                SELECT NOT has_table_privilege(
                  'qiqihar_enterprise_runtime','registry.sample_point','DELETE')
                """)).isEqualTo("t");
        assertSqlRejected("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  trade_direction,status_code,last_modified_by)
                VALUES('invalid-trader-observation','CORN','TRADER','230200',DATE '2026-08-01',
                  now(),'OBSERVATION','DRAFT','migration-test')
                """);
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by)
                VALUES('invalid-agri-price','CORN','AGRICULTURAL_INPUT_STORE','230200',
                  DATE '2026-08-01',now(),10,'PURCHASE',0,0,0,'BULK','DRAFT','migration-test')
                """);
        }
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by)
                VALUES('invalid-null-price-component','CORN','TRADER','230200',
                  DATE '2026-08-01',now(),10,'PURCHASE',NULL,0,0,'BULK','DRAFT','migration-test')
                """);
        }

        String v161 = Files.readString(Path.of(
                "src/main/resources/db/migration/V161__unify_formal_market_object_fields.sql"));
        assertThat(v161).doesNotContain("village_design_sample_point", "design_sample");
    }

    private void seedV160FormalSampleAndResolution() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO registry.sample_point(
                      sample_point_id,kind_code,canonical_name,region_code,approval_state,
                      location_state,effective_from,created_by,updated_by)
                    VALUES('16200000-0000-0000-0000-000000000001','SURVEY_SITE',
                      'V162升级保留正式样本','230200','APPROVED','MISSING',DATE '2026-01-01',
                      'database-master-data-automation','database-master-data-automation')
                    """);
            statement.execute("""
                    INSERT INTO market.market_record(
                      record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                      purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                      packaging_amount,freight_amount,packaging_form,status_code,last_modified_by,
                      sample_point_id)
                    VALUES('v162-preserved-market','CORN','TRADER','230200',DATE '2026-08-01',now(),
                      2300,2380,'BOTH',36,12,72,'BULK','APPROVED',
                      'database-master-data-automation','16200000-0000-0000-0000-000000000001')
                    """);
            statement.execute("""
                    INSERT INTO registry.sample_subject_resolution_batch(
                      batch_id,idempotency_key,input_digest,expected_item_count,status_code,
                      created_at,created_by,applied_at,applied_by)
                    VALUES('16200000-0000-0000-0000-000000000010','v162-upgrade-reference',
                      repeat('a',64),1,'APPLIED',now(),'migration-test',now(),'migration-test')
                    """);
            statement.execute("""
                    INSERT INTO registry.sample_subject_resolution_item(
                      batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                      resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code,
                      before_snapshot,after_snapshot,before_sha256,after_sha256,
                      applied_source_version,applied_resolution_revision_id,applied_at,applied_by)
                    VALUES('16200000-0000-0000-0000-000000000010',1,'MARKET',
                      'v162-preserved-market',0,'LINK','v162-preserved-subject',
                      '16200000-0000-0000-0000-000000000001','UPGRADE_REFERENCE','APPLIED',
                      '{}','{}',repeat('b',64),repeat('c',64),0,
                      '16200000-0000-0000-0000-000000000011',now(),'migration-test')
                    """);
            statement.execute("""
                    INSERT INTO registry.sample_subject_resolution_revision(
                      resolution_revision_id,source_domain,source_record_id,resolution_sequence,
                      resolution_action,stable_subject_id,target_sample_point_id,source_version,
                      predecessor_revision_id,batch_id,item_sequence,before_sha256,after_sha256,
                      occurred_at,actor)
                    VALUES('16200000-0000-0000-0000-000000000011','MARKET',
                      'v162-preserved-market',1,'LINK','v162-preserved-subject',
                      '16200000-0000-0000-0000-000000000001',0,NULL,
                      '16200000-0000-0000-0000-000000000010',1,repeat('b',64),repeat('c',64),
                      now(),'migration-test')
                    """);
        }
    }

    private void assertSqlRejected(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(java.sql.SQLException.class);
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
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
            result.next();
            return result.getString(1);
        }
    }
}
