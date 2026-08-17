package com.cofco.qiqihar.graintrade.importing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PhotoAwareImportDraftMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchemaAndSecurityFixtures() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void freshMigrationPublishesPhotoAwareDraftStorageWithoutWeakeningRuntimeBoundaries() throws Exception {
        resetDatabase();

        DATABASE.flyway().migrate();

        assertV121AppliedExactlyOnce();
        assertThat(queryString("""
                SELECT string_agg(column_name, ',' ORDER BY ordinal_position)
                FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='business_import_draft'
                """)).contains(
                        "import_draft_id", "domain_code", "product_code", "object_type_code",
                        "sample_name", "region_code", "survey_period", "values_json",
                        "missing_fields_json", "state_code", "created_by", "import_job_id",
                        "source_row_number", "version", "canonical_record_id");
        assertThat(queryInteger("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema='platform' AND table_name='business_import_draft'
                  AND constraint_type='FOREIGN KEY'
                """)).isGreaterThanOrEqualTo(5);
        assertThat(queryBoolean("""
                SELECT EXISTS (
                  SELECT 1 FROM pg_indexes
                  WHERE schemaname='platform' AND tablename='business_import_draft'
                    AND indexdef LIKE '%UNIQUE%import_job_id%source_row_number%')
                  AND EXISTS (
                  SELECT 1 FROM pg_indexes
                  WHERE schemaname='platform' AND tablename='import_job_photo'
                    AND indexdef LIKE '%UNIQUE%import_job_id%normalized_filename%')
                """)).isTrue();
        assertThat(queryString("""
                SELECT string_agg(column_name || ':' || is_nullable, ',' ORDER BY column_name)
                FROM information_schema.columns
                WHERE table_schema='evidence' AND table_name='evidence_photo'
                  AND column_name IN ('captured_at','capture_latitude','capture_longitude')
                """)).isEqualTo("capture_latitude:YES,capture_longitude:YES,captured_at:YES");
        assertThat(queryString("""
                SELECT string_agg(column_name || ':' || is_nullable, ',' ORDER BY ordinal_position)
                FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='import_row_result'
                  AND column_name IN ('warning_code','warning_message')
                """)).isEqualTo("warning_code:YES,warning_message:YES");
        assertThat(queryString("""
                SELECT pg_get_viewdef('evidence.evidence_photo_consistency'::regclass, true)
                """)).contains("LOGISTICS", "logistics.route_event");
        assertThat(queryBoolean("""
                SELECT has_table_privilege('qiqihar_enterprise_runtime',
                         'platform.business_import_draft','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege('qiqihar_enterprise_runtime',
                         'platform.business_import_draft_evidence','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege('qiqihar_enterprise_runtime',
                         'platform.import_job_photo','SELECT,INSERT,UPDATE,DELETE')
                """)).isTrue();
    }

    @Test
    void upgradesV120ImportHistoryAndMasterDataWithoutRewritingEither() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("120").migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
        execute("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,completed_at,attempt_count)
                SELECT '41000000-0000-0000-0000-000000000001','PRODUCTION','v120-import',repeat('a',64),
                  'legacy xlsx','production-tester',work_unit_code,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1
                FROM platform.security_user WHERE subject_id='production-tester'
                """);
        execute("""
                INSERT INTO platform.import_row_result(import_job_id,row_number,outcome_code,business_record_id,row_data)
                VALUES('41000000-0000-0000-0000-000000000001',2,'IMPORTED','legacy-record','{}'::jsonb)
                """);
        String masterCounts = queryString("""
                SELECT (SELECT count(*) FROM platform.product) || ':' ||
                       (SELECT count(*) FROM platform.object_type) || ':' ||
                       (SELECT count(*) FROM platform.region)
                """);

        assertThat(DATABASE.flywayToVersion("121").migrate().migrationsExecuted).isOne();

        assertV121AppliedExactlyOnce();
        assertThat(queryString("""
                SELECT (SELECT count(*) FROM platform.product) || ':' ||
                       (SELECT count(*) FROM platform.object_type) || ':' ||
                       (SELECT count(*) FROM platform.region)
                """)).isEqualTo(masterCounts);
        assertThat(queryString("""
                SELECT outcome_code || ':' || business_record_id || ':' ||
                       COALESCE(warning_code,'NO_WARNING')
                FROM platform.import_row_result
                WHERE import_job_id='41000000-0000-0000-0000-000000000001' AND row_number=2
                """)).isEqualTo("IMPORTED:legacy-record:NO_WARNING");
        execute("""
                UPDATE platform.import_row_result
                SET warning_code='PHOTO_SKIPPED',warning_message='照片无效，业务草稿仍可使用'
                WHERE import_job_id='41000000-0000-0000-0000-000000000001' AND row_number=2
                """);
        execute("""
                INSERT INTO platform.business_import_draft(import_draft_id,domain_code,product_code,
                  object_type_code,sample_name,region_code,survey_period,values_json,missing_fields_json,
                  state_code,created_by,import_job_id,source_row_number,version,created_at,updated_at)
                VALUES('42000000-0000-0000-0000-000000000001','PRODUCTION','CORN',NULL,
                  '迁移验证样本点','230200',NULL,'{}'::jsonb,'["objectType"]'::jsonb,'DRAFT',
                  'production-tester','41000000-0000-0000-0000-000000000001',3,0,
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        assertThat(queryString("""
                SELECT state_code || ':' || COALESCE(object_type_code,'NO_OBJECT_TYPE') || ':' || version
                FROM platform.business_import_draft
                WHERE import_draft_id='42000000-0000-0000-0000-000000000001'
                """)).isEqualTo("DRAFT:NO_OBJECT_TYPE:0");
        assertThat(DATABASE.flywayToVersion("121").migrate().migrationsExecuted).isZero();
    }

    private void assertV121AppliedExactlyOnce() throws Exception {
        assertThat(queryInteger("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE version='121' AND script='V121__stage_photo_aware_business_import_drafts.sql' AND success
                """)).isOne();
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

    private int queryInteger(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }

    private boolean queryBoolean(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
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
