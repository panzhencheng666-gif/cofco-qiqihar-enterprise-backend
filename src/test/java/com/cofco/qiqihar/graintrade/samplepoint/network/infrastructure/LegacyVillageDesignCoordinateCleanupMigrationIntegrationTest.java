package com.cofco.qiqihar.graintrade.samplepoint.network.infrastructure;

import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyVillageDesignCoordinateCleanupMigrationIntegrationTest {

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String DATASET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OPERATION = "legacy-village-coordinate-cleanup-test";
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };

    @BeforeEach
    void resetAndProvisionTrustedLineageFixture() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();

        JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997", "旧坐标测试乡", "230202", "TOWNSHIP", 997);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997001", "旧坐标测试一村", "230202997", "VILLAGE", 1);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997002", "旧坐标测试二村", "230202997", "VILLAGE", 2);
        jdbc.sql("""
                INSERT INTO platform.geography_import_batch(
                  dataset_sha256,source_workbook_sha256,source_revision,
                  township_count,village_count,coordinate_count)
                VALUES(:dataset,repeat('b',64),'legacy-coordinate-test',1,2,3)
                """).param("dataset", DATASET).update();
        jdbc.sql("""
                INSERT INTO platform.region_location(
                  region_code,original_coordinate,wgs84_coordinate,original_crs,target_crs,
                  conversion_method,source_name,source_url,source_revision,place_type,matched_by,
                  match_confidence,review_status,dataset_sha256)
                VALUES
                  ('230202997',ST_SetSRID(ST_MakePoint(123.90,47.30),4490),
                    ST_SetSRID(ST_MakePoint(123.90,47.30),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/legacy-coordinate',
                    'legacy-coordinate-test','乡镇','exact test match','HIGH','REVIEWED',:dataset),
                  ('230202997001',ST_SetSRID(ST_MakePoint(123.91,47.31),4490),
                    ST_SetSRID(ST_MakePoint(123.91,47.31),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/legacy-coordinate',
                    'legacy-coordinate-test','行政村','exact test match','HIGH','REVIEWED',:dataset),
                  ('230202997002',ST_SetSRID(ST_MakePoint(123.92,47.32),4490),
                    ST_SetSRID(ST_MakePoint(123.92,47.32),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/legacy-coordinate',
                    'legacy-coordinate-test','行政村','exact test match','HIGH','REVIEWED',:dataset)
                """).param("dataset", DATASET).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('LEGACY_COORDINATE_TEST','旧坐标清理测试单位',9997)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES ('legacy-coordinate-test-operator','旧坐标清理测试操作员',
                        'LEGACY_COORDINATE_TEST')
                """).update();
    }

    @Test
    void installsAnOwnerOnlyFailClosedBusinessWrapper() throws Exception {
        assertThat(query("""
                SELECT has_function_privilege(
                  'qiqihar_enterprise_runtime',
                  'platform.cleanup_legacy_village_design_coordinates(varchar,varchar,varchar)',
                  'EXECUTE')::text
                """)).isEqualTo("false");

        assertThatThrownBy(() -> execute("""
                SELECT * FROM platform.cleanup_legacy_village_design_coordinates(
                  'legacy-coordinate-test-operator','LEGACY_COORDINATE_TEST',
                  'task-clone-test-authorization')
                """))
                .hasMessageContaining("expected 2332 legacy village coordinate rows")
                .hasMessageContaining("found 0");
        assertThat(countTargetVillageLocations()).isEqualTo(2);
    }

    @Test
    void deletesOnlyTheExpectedVillageRowsAndReplaysWithoutASecondEvent() throws Exception {
        long samplePointCountBefore = count("SELECT count(*) FROM registry.sample_point");
        String expectedCodeDigest = targetCodeDigest();

        CleanupResult first = cleanup(expectedCodeDigest);
        CleanupResult replay = cleanup(expectedCodeDigest);

        assertThat(first.deletedCount()).isEqualTo(2);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(replay.deletedCount()).isZero();
        assertThat(replay.replayed()).isTrue();
        assertThat(countTargetVillageLocations()).isZero();
        assertThat(count("""
                SELECT count(*) FROM platform.region_location location
                JOIN platform.region region ON region.code=location.region_code
                WHERE location.dataset_sha256='%s'
                  AND region.administrative_level='TOWNSHIP'
                """.formatted(DATASET))).isOne();
        assertThat(count("""
                SELECT count(*) FROM platform.region
                WHERE code IN ('230202997001','230202997002')
                """)).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM platform.geography_import_batch
                WHERE dataset_sha256='%s'
                """.formatted(DATASET))).isOne();
        assertThat(count("SELECT count(*) FROM registry.sample_point"))
                .isEqualTo(samplePointCountBefore);
        assertThat(count("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='DESIGN_COORDINATE_DATASET'
                  AND aggregate_id='%s'
                  AND action_code='LEGACY_VILLAGE_DESIGN_COORDINATES_DELETED'
                """.formatted(OPERATION))).isOne();
        assertThat(count("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='DESIGN_COORDINATE_DATASET'
                  AND aggregate_id='%s'
                  AND action_code='LEGACY_VILLAGE_DESIGN_COORDINATES_DELETED'
                  AND cardinality(region_codes)=1
                """.formatted(OPERATION))).isOne();
    }

    @Test
    void rejectsAChangedPreviewAndRollsBackDeletionWhenOutboxWriteFails() throws Exception {
        assertThatThrownBy(() -> cleanup("0".repeat(64)))
                .hasMessageContaining("legacy village coordinate code digest mismatch");
        assertThat(countTargetVillageLocations()).isEqualTo(2);
        assertThat(auditAndOutboxCounts()).isEqualTo("0|0");

        execute("""
                CREATE FUNCTION platform.reject_legacy_cleanup_outbox_test()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.aggregate_id='%s' THEN
                    RAISE EXCEPTION 'forced legacy cleanup outbox failure';
                  END IF;
                  RETURN NEW;
                END
                $$
                """.formatted(OPERATION));
        execute("""
                CREATE TRIGGER reject_legacy_cleanup_outbox_test
                BEFORE INSERT ON platform.business_event_outbox
                FOR EACH ROW EXECUTE FUNCTION platform.reject_legacy_cleanup_outbox_test()
                """);

        assertThatThrownBy(() -> cleanup(targetCodeDigest()))
                .hasMessageContaining("forced legacy cleanup outbox failure");
        assertThat(countTargetVillageLocations()).isEqualTo(2);
        assertThat(auditAndOutboxCounts()).isEqualTo("0|0");
    }

    private CleanupResult cleanup(String expectedCodeDigest) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT event_id,deleted_count,replayed
                        FROM platform.execute_guarded_legacy_village_coordinate_cleanup(
                          '%s',2,'%s',1,2,0,'{}'::jsonb,'legacy-coordinate-test-operator',
                          'LEGACY_COORDINATE_TEST','task-clone-test-authorization','%s')
                        """.formatted(DATASET, expectedCodeDigest, OPERATION))) {
            result.next();
            return new CleanupResult(
                    result.getObject("event_id", UUID.class),
                    result.getInt("deleted_count"),
                    result.getBoolean("replayed"));
        }
    }

    private long countTargetVillageLocations() throws Exception {
        return count("""
                SELECT count(*) FROM platform.region_location location
                JOIN platform.region region ON region.code=location.region_code
                WHERE location.dataset_sha256='%s'
                  AND region.administrative_level='VILLAGE'
                """.formatted(DATASET));
    }

    private String targetCodeDigest() throws Exception {
        return query("""
                SELECT encode(sha256(convert_to(
                  string_agg(region.code,E'\\n' ORDER BY region.code),'UTF8')),'hex')
                FROM platform.region_location location
                JOIN platform.region region ON region.code=location.region_code
                WHERE location.dataset_sha256='%s'
                  AND region.administrative_level='VILLAGE'
                """.formatted(DATASET));
    }

    private String auditAndOutboxCounts() throws Exception {
        return query("""
                SELECT (SELECT count(*) FROM platform.business_audit_event
                        WHERE aggregate_id='%s') || '|' ||
                       (SELECT count(*) FROM platform.business_event_outbox
                        WHERE aggregate_id='%s')
                """.formatted(OPERATION, OPERATION));
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

    private long count(String sql) throws Exception {
        return Long.parseLong(query(sql));
    }

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record CleanupResult(UUID eventId, int deletedCount, boolean replayed) {}
}
