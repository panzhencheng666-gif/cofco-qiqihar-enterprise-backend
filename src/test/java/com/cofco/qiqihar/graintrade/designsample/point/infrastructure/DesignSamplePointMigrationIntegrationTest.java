package com.cofco.qiqihar.graintrade.designsample.point.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class DesignSamplePointMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void placesSchematicPointsInTheExactSelectedCountyTownshipOrVillage() {
        JdbcClient jdbc=JdbcClient.create(dataSource);
        com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures.insertRegion(
                jdbc,"230202999","示意测试乡镇","230202","TOWNSHIP",990991);
        com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures.insertRegion(
                jdbc,"230202999999","示意测试行政村","230202999","VILLAGE",990992);
        for (String code:java.util.List.of("230202999","230202999999")) {
            jdbc.sql("""
                    INSERT INTO overview.administrative_boundary(
                      region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                    SELECT :code,ST_Multi(ST_MakeEnvelope(123.94,47.34,123.96,47.36,4326)),
                      'isolated fixture','urn:test:schematic','test-v1','Test fixture',repeat('a',64)
                    """).param("code",code).update();
            jdbc.sql("""
                    INSERT INTO overview.administrative_boundary_render(
                      region_code,geometry,geo_json,simplify_tolerance,full_point_count,render_point_count,
                      source_geometry_sha256,source_name,source_revision,source_license)
                    SELECT region_code,geometry,ST_AsGeoJSON(geometry,15),0,ST_NPoints(geometry),
                      ST_NPoints(geometry),geometry_sha256,source_name,source_revision,source_license
                    FROM overview.administrative_boundary WHERE region_code=:code
                    """).param("code",code).update();
        }
        for (String level: java.util.List.of("COUNTY","TOWNSHIP","VILLAGE")) {
            String region=jdbc.sql("""
                    SELECT b.region_code FROM overview.administrative_boundary_render b
                    JOIN platform.region r ON r.code=b.region_code
                    WHERE r.administrative_level=:level ORDER BY b.region_code LIMIT 1
                    """).param("level",level).query(String.class).single();
            UUID id=UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO platform.design_sample_point(
                      design_sample_point_id,contract_version,domain_code,product_code,
                      object_type_code,values_json,sample_name,region_code,governed_point,
                      idempotency_key,request_digest,created_by,updated_by)
                    VALUES(:id,'design-sample-fields-v1','PRODUCTION','CORN','FARMER',
                      '{}'::jsonb,:name,:region,ST_SetSRID(ST_MakePoint(1,1),4326),
                      :key,repeat('a',64),'production-tester','production-tester')
                    """).param("id",id).param("name","示意层级-"+level)
                    .param("region",region).param("key",id.toString()).update();
            assertThat(jdbc.sql("""
                    SELECT p.display_region_code=p.region_code
                      AND p.location_mode='REGION_SCHEMATIC'
                      AND ST_X(p.governed_point)=1 AND ST_Y(p.governed_point)=1
                      AND ST_Covers(ST_GeomFromGeoJSON(b.geo_json),p.display_point)
                    FROM platform.design_sample_point p
                    JOIN overview.administrative_boundary_render b ON b.region_code=p.region_code
                    WHERE p.design_sample_point_id=:id
                    """).param("id",id).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void isYearIndependentRuntimeWritableAndSeparateFromExistingSampleStores() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='design_sample_point'
                  AND column_name IN ('survey_year','network_year','year')
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT has_table_privilege('qiqihar_enterprise_runtime',
                  'platform.design_sample_point','SELECT,INSERT,UPDATE,DELETE')
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.table_privileges
                WHERE grantee='PUBLIC' AND table_schema='platform'
                  AND table_name='design_sample_point'
                  AND privilege_type IN ('SELECT','INSERT','UPDATE','DELETE')
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM pg_constraint constraint_record
                WHERE constraint_record.conrelid='platform.design_sample_point'::regclass
                  AND constraint_record.contype='f'
                  AND constraint_record.confrelid IN (
                    'registry.sample_point'::regclass,
                    'registry.sample_network_year'::regclass,
                    'registry.sample_network_membership'::regclass,
                    'platform.region_location'::regclass)
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT obj_description('platform.design_sample_point'::regclass)
                """).query(String.class).single()).contains("Year-independent");
    }
}
