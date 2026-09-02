package com.cofco.qiqihar.graintrade.designsample.point.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DesignSamplePointUpgradeReplayTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };
    private JdbcClient jdbc;

    @BeforeEach
    void migrateTrustedEquivalentSeedToV159() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("159").migrate().migrationsExecuted)
                .isEqualTo(157);
        jdbc = JdbcClient.create(DATABASE.dataSource());
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjectsWithoutBoundaries(jdbc);
        for (int index = 1; index <= 232; index++) {
            GovernedMasterDataFixtures.insertRegion(
                    jdbc, "2302027%05d".formatted(index),
                    "V159升级隔离乡镇%03d".formatted(index),
                    "230202", "TOWNSHIP", 9700 + index);
        }
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230202799999", "V159升级隔离村",
                "230202700001", "VILLAGE", 9999);
        jdbc.sql("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,version,created_by,created_at,
                  submitted_by,submitted_at,reviewed_by,reviewed_at,published_by,published_at)
                VALUES(2199,'PUBLISHED',0,'production-tester',CURRENT_TIMESTAMP,
                  'production-tester',CURRENT_TIMESTAMP,'market-tester',CURRENT_TIMESTAMP,
                  'production-tester',CURRENT_TIMESTAMP);
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                SELECT CAST(md5('v159-upgrade-formal-sentinel-' || value) AS uuid),
                  'SURVEY_SITE','V159升级正式样本-' || value,'230202','APPROVED',
                  'MISSING',DATE '2199-01-01','production-tester','production-tester'
                FROM generate_series(1,1064) value;
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                SELECT 2199,CAST(md5('v159-upgrade-formal-sentinel-' || value) AS uuid),
                  '230202799999','ACTIVE','MANUAL',0,'production-tester',CURRENT_TIMESTAMP,
                  'production-tester',CURRENT_TIMESTAMP
                FROM generate_series(1,1064) value
                """).update();
    }

    @AfterEach
    void restoreLatestCleanSchema() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void upgradesFromV159ThroughV168WithoutChangingFormalSamplesOrTownshipMasterData() {
        assertThat(formalCount()).isEqualTo(1064);
        assertThat(townshipCount()).isEqualTo(232);

        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(11);
        assertThat(formalCount()).isEqualTo(1064);
        assertThat(townshipCount()).isEqualTo(232);

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(
                  design_sample_point_id,contract_version,domain_code,product_code,
                  object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                VALUES(:id,'design-sample-fields-v1','PRODUCTION','CORN','FARMER',
                  jsonb_build_object('DSP_NAME','V159升级写入哨兵','DSP_REGION_CODE','230202',
                    'DSP_LONGITUDE',123.95,'DSP_LATITUDE',47.35,
                    'OBSERVED_ON','2199-06-01','PROD_AREA_MU',1),
                  'V159升级写入哨兵','230202',ST_SetSRID(ST_MakePoint(123.95,47.35),4326),
                  'v159-upgrade-write-sentinel',repeat('c',64),
                  'production-tester','production-tester');
                DELETE FROM platform.design_sample_point WHERE design_sample_point_id=:id
                """).param("id", id).update();

        assertThat(formalCount()).isEqualTo(1064);
        assertThat(townshipCount()).isEqualTo(232);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.design_sample_point")
                .query(Long.class).single()).isZero();
    }

    private long formalCount() {
        return jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership membership
                JOIN registry.sample_point point USING(sample_point_id)
                WHERE membership.network_year=2199 AND membership.status_code='ACTIVE'
                  AND point.approval_state='APPROVED'
                  AND point.canonical_name LIKE 'V159升级正式样本-%'
                """).query(Long.class).single();
    }

    private long townshipCount() {
        return jdbc.sql("""
                SELECT count(*) FROM platform.region
                WHERE administrative_level='TOWNSHIP' AND code LIKE '2302027%'
                """).query(Long.class).single();
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
}
