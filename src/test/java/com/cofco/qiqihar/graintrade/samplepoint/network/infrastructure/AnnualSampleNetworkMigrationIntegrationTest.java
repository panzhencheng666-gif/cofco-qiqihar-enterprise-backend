package com.cofco.qiqihar.graintrade.samplepoint.network.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnnualSampleNetworkMigrationIntegrationTest {
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
    void freshMigrationExposesExactlyOneDesignReferenceForEveryVillage() throws Exception {
        resetDatabase();

        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(165);
        insertVillageFixtures(true);
        assertThat(query("""
                SELECT (SELECT count(*) FROM platform.region
                        WHERE administrative_level='VILLAGE') || ':' ||
                       count(*) || ':' ||
                       count(*) FILTER (WHERE longitude IS NOT NULL AND latitude IS NOT NULL)
                FROM registry.village_design_sample_point
                """)).isEqualTo("2:2:2");
        assertThat(query("""
                SELECT count(*) FROM registry.village_design_sample_point reference
                JOIN platform.region village ON village.code=reference.village_region_code
                WHERE village.administrative_level<>'VILLAGE'
                """)).isEqualTo("0");
    }

    @Test
    void migratesVillageMembersToDesignRelationsAndAllowsCountyMembersWithoutAVillage()
            throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("133").migrate();
        insertVillageFixtures(true);
        execute("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES('13300000-0000-0000-0000-000000000001','SURVEY_SITE',
                  '年度网络迁移测试样本点','230202997001','APPROVED','MISSING',
                  DATE '2026-01-01','database-master-data-automation',
                  'database-master-data-automation')
                """);
        execute("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES('13300000-0000-0000-0000-000000000002','SURVEY_SITE',
                  'V133年度网络迁移测试区县样本点','230202','APPROVED','MISSING',
                  DATE '2026-01-01','database-master-data-automation',
                  'database-master-data-automation'),
                 ('13300000-0000-0000-0000-000000000003','SURVEY_SITE',
                  'V133年度网络迁移同审核人样本点','230202997002','APPROVED','MISSING',
                  DATE '2026-01-01','database-master-data-automation',
                  'database-master-data-automation')
                """);
        execute("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('annual-network-reviewer','年度网络审核员','DATABASE_AUTOMATION')
                """);
        execute("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,version,created_by,created_at,
                  submitted_by,submitted_at,reviewed_by,reviewed_at,published_by,published_at)
                VALUES(2026,'PUBLISHED',0,'database-master-data-automation',now(),
                  'database-master-data-automation',now(),'annual-network-reviewer',now(),
                  'database-master-data-automation',now())
                """);
        execute("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000001','230202997001',
                  'ACTIVE','NEW',0,'database-master-data-automation',now(),
                  'database-master-data-automation',now())
                """);
        execute("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000002','230202997001',
                  'ACTIVE','NEW',0,'database-master-data-automation',now(),
                  'database-master-data-automation',now()),
                 (2026,'13300000-0000-0000-0000-000000000003','230202997002',
                  'ACTIVE','NEW',0,'annual-network-reviewer',now(),
                  'annual-network-reviewer',now())
                """);

        assertThatCode(() -> DATABASE.flyway().migrate()).doesNotThrowAnyException();

        assertThat(query("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema='registry' AND table_name='sample_network_membership'
                  AND column_name='village_region_code'
                """)).isEqualTo("YES");
        assertThat(query("""
                SELECT count(*) FROM registry.sample_network_design_relation
                WHERE relation_type='EXACT_VILLAGE' AND review_status='APPROVED'
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT relation_type || ':' || review_status || ':' || evidence_reference || ':' ||
                       COALESCE(reviewed_by,'NULL')
                FROM registry.sample_network_design_relation
                WHERE sample_point_id='13300000-0000-0000-0000-000000000002'
                """)).isEqualTo(
                "EXPLICIT_REPRESENTATION:PENDING_REVIEW:legacy V133 binding requiring annual review:NULL");
        assertThat(query("""
                SELECT relation_type || ':' || review_status || ':' || COALESCE(reviewed_by,'NULL')
                FROM registry.sample_network_design_relation
                WHERE sample_point_id='13300000-0000-0000-0000-000000000003'
                """)).isEqualTo("EXACT_VILLAGE:PENDING_REVIEW:NULL");
        execute("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES('13300000-0000-0000-0000-000000000004','SURVEY_SITE',
                  '年度网络迁移测试区县样本点','230202','APPROVED','MISSING',
                  DATE '2026-01-01','database-master-data-automation',
                  'database-master-data-automation')
                """);
        execute("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000004',
                  'ACTIVE','NEW',0,'database-master-data-automation',now(),
                  'database-master-data-automation',now())
                """);
        assertThat(query("""
                SELECT point.region_code || ':' || COALESCE(membership.village_region_code,'NULL')
                FROM registry.sample_network_membership membership
                JOIN registry.sample_point point USING(sample_point_id)
                WHERE membership.sample_point_id='13300000-0000-0000-0000-000000000004'
                """)).isEqualTo("230202:NULL");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000001','230202997002',
                  'EXACT_VILLAGE','test evidence','PENDING_REVIEW',
                  'database-master-data-automation')
                """)).hasMessageContaining("EXACT_VILLAGE relation must match the sample member village");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXACT_VILLAGE','test evidence','PENDING_REVIEW',
                  'database-master-data-automation')
                """)).hasMessageContaining("EXACT_VILLAGE relation requires a village-level sample member");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXPLICIT_REPRESENTATION','test evidence','APPROVED',
                  'database-master-data-automation')
                """)).hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by,reviewed_by,reviewed_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXPLICIT_REPRESENTATION','test evidence','APPROVED',
                  'database-master-data-automation','database-master-data-automation',now())
                """)).hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by,reviewed_by,reviewed_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXPLICIT_REPRESENTATION','test evidence','PENDING_REVIEW',
                  'database-master-data-automation','annual-network-reviewer',now())
                """)).hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXPLICIT_REPRESENTATION','test evidence','RETURNED',
                  'database-master-data-automation')
                """)).hasMessageContaining("violates check constraint");
        execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000004','230202997001',
                  'EXPLICIT_REPRESENTATION','test evidence','PENDING_REVIEW',
                  'database-master-data-automation')
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE registry.sample_network_design_relation
                SET relation_type='EXACT_VILLAGE'
                WHERE network_year=2026
                  AND sample_point_id='13300000-0000-0000-0000-000000000004'
                  AND design_village_region_code='230202997001'
                  AND relation_type='EXPLICIT_REPRESENTATION'
                """)).hasMessageContaining("EXACT_VILLAGE relation requires a village-level sample member");

        assertThat(query("""
                SELECT network_year || ':' || status_code || ':' || source_code
                FROM registry.sample_network_membership
                WHERE sample_point_id='13300000-0000-0000-0000-000000000001'
                """)).isEqualTo("2026:ACTIVE:NEW");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                VALUES(2026,'13300000-0000-0000-0000-000000000001','230202997001',
                  'ACTIVE','NEW',0,'database-master-data-automation',now(),
                  'database-master-data-automation',now())
                """)).hasMessageContaining("sample_network_membership_pkey");
        execute("""
                UPDATE registry.sample_network_membership
                SET village_region_code=NULL
                WHERE sample_point_id='13300000-0000-0000-0000-000000000001'
                """);
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000005','230202997001',
                  'EXACT_VILLAGE','test evidence','PENDING_REVIEW',
                  'database-master-data-automation')
                """)).hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  evidence_reference,review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000001','230202997',
                  'EXACT_VILLAGE','test evidence','PENDING_REVIEW',
                  'database-master-data-automation')
                """)).hasMessageContaining("design relation village must be a village");
        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_design_relation(
                  network_year,sample_point_id,design_village_region_code,relation_type,
                  review_status,created_by)
                VALUES(2026,'13300000-0000-0000-0000-000000000001','230202997001',
                  'EXPLICIT_REPRESENTATION','PENDING_REVIEW',
                  'database-master-data-automation')
                """)).hasMessageContaining("violates check constraint");
    }

    @Test
    void rejectsInvalidLifecycleCodesAndKeepsTheDesignViewReadOnlyForRuntime() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();

        assertThatThrownBy(() -> execute("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,version,created_by,created_at)
                VALUES(2026,'ACTIVE',0,'database-master-data-automation',now())
                """)).hasMessageContaining("violates check constraint");
        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'registry.village_design_sample_point','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'registry.village_design_sample_point','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'registry.village_design_sample_point','UPDATE') || ':' ||
                       has_table_privilege('cofco_app',
                         'registry.village_design_sample_point','DELETE')
                """)).isEqualTo("true:false:false:false");
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

    private void insertVillageFixtures(boolean includeSecondVillageLocations) {
        JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997", "年度网络测试乡", "230202", "TOWNSHIP", 997);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997001", "年度网络测试一村", "230202997", "VILLAGE", 1);
        if (!includeSecondVillageLocations) return;
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202997002", "年度网络测试二村", "230202997", "VILLAGE", 2);
        jdbc.sql("""
                INSERT INTO platform.geography_import_batch(
                  dataset_sha256,source_workbook_sha256,source_revision,
                  township_count,village_count,coordinate_count)
                VALUES(repeat('a',64),repeat('b',64),'annual-network-test',1,2,2)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.region_location(
                  region_code,original_coordinate,wgs84_coordinate,original_crs,target_crs,
                  conversion_method,source_name,source_url,source_revision,place_type,matched_by,
                  match_confidence,review_status,dataset_sha256)
                VALUES
                  ('230202997001',ST_SetSRID(ST_MakePoint(123.90,47.30),4490),
                    ST_SetSRID(ST_MakePoint(123.90,47.30),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-test','行政村','exact test match','HIGH','REVIEWED',repeat('a',64)),
                  ('230202997002',ST_SetSRID(ST_MakePoint(123.91,47.31),4490),
                    ST_SetSRID(ST_MakePoint(123.91,47.31),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-test','行政村','exact test match','HIGH','REVIEWED',repeat('a',64))
                """).update();
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
}
