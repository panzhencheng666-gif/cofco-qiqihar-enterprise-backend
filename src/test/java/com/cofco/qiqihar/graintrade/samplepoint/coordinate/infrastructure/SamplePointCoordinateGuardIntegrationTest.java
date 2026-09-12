package com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class SamplePointCoordinateGuardIntegrationTest {
    private static final String TOWNSHIP = "230202998";
    private static final String REGION = "230202998001";
    private static final UUID OCCUPIED_POINT =
            UUID.fromString("95000000-0000-0000-0000-000000000001");

    @Autowired DataSource dataSource;
    @Autowired SamplePointCoordinateGuard guard;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "坐标唯一性测试乡", "230202", "TOWNSHIP", 998);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, REGION, "坐标唯一性测试村", TOWNSHIP, "VILLAGE", 1);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:region,
                  ST_Multi(ST_GeomFromText('POLYGON((123 47,124 47,124 48,123 48,123 47))',4326)),
                  'coordinate guard fixture','urn:test:sample-point-coordinate-guard','test-v1',
                  'Test fixture',:region,DATE '2026-08-20',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=excluded.geometry,source_name=excluded.source_name,
                  source_url=excluded.source_url,source_revision=excluded.source_revision,
                  source_license=excluded.source_license,source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,
                  geometry_sha256=excluded.geometry_sha256
                """).param("region", REGION).update();
        GovernedMasterDataFixtures.publishBoundary(jdbc, REGION);
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','已占用坐标样本点',:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.51,47.92),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", OCCUPIED_POINT.toString()).param("region", REGION).update();
    }

    @Autowired com.cofco.qiqihar.graintrade.production.application.ProductionRecordRepository production;
    @Autowired com.cofco.qiqihar.graintrade.market.application.MarketMonitoringRepository market;
    @Autowired com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointRepository design;
    @Autowired com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointRepository formal;

    @Test
    void acceptsCountyCoordinatesOutsideTheDeclaredVillageAcrossAllIngressReaders() {
        countyBoundary();
        BigDecimal longitude = new BigDecimal("124.5");
        BigDecimal latitude = new BigDecimal("48.5");
        assertThat(production.supportsSampleLocation(REGION, latitude, longitude)).isTrue();
        assertThat(market.supportsSampleLocation(REGION, latitude, longitude)).isTrue();
        assertThat(design.coordinateBoundaryState(REGION, longitude, latitude).orElseThrow().name())
                .isEqualTo("INSIDE");
        assertThat(formal.coordinateBoundaryState(REGION, longitude, latitude).orElseThrow().name())
                .isEqualTo("INSIDE");
    }

    @Test
    void rejectsOutOfCountyCoordinatesEvenWhenTheDisplayBoundaryIsAvailable() {
        countyBoundary();
        BigDecimal longitude = new BigDecimal("130");
        BigDecimal latitude = new BigDecimal("50");
        assertThat(production.supportsSampleLocation(REGION, latitude, longitude)).isFalse();
        assertThat(market.supportsSampleLocation(REGION, latitude, longitude)).isFalse();
        assertThat(design.coordinateBoundaryState(REGION, longitude, latitude).orElseThrow().name())
                .isEqualTo("OUTSIDE");
        assertThat(formal.coordinateBoundaryState(REGION, longitude, latitude).orElseThrow().name())
                .isEqualTo("OUTSIDE");
    }

    @Test
    void allowsIndependentIdentitiesSharingCountyCoordinatesAcrossDeclaredRegions() {
        countyBoundary();
        assertThatCode(() -> guard.lockAndRequireAvailableForRegion(null,
                new BigDecimal("123.51"), new BigDecimal("47.92"), "230202"))
                .doesNotThrowAnyException();
    }

    @Test
    void separatesDisplayPointsWithoutChangingSharedReportedCoordinates() {
        UUID other = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE','独立共址身份',:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.51,47.92),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", other).param("region", REGION).update();
        assertThat(jdbc.sql("""
                SELECT ST_Equals(a.governed_point,b.governed_point)
                  AND NOT ST_Equals(a.display_point,b.display_point)
                  AND ST_Covers(boundary.geometry,b.display_point)
                FROM registry.sample_point a, registry.sample_point b,
                  overview.administrative_boundary_render boundary
                WHERE a.sample_point_id=:first AND b.sample_point_id=:second
                  AND boundary.region_code=b.region_code
                """).param("first", OCCUPIED_POINT).param("second", other)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void resolvesTheLowestNamedRegionOnlyWithinTheSelectedHierarchy() {
        assertThat(jdbc.sql("SELECT overview.sample_address_anchor('230202',:address)")
                .param("address", "坐标唯一性测试乡坐标唯一性测试村三组")
                .query(String.class).single()).isEqualTo(REGION);
        assertThat(jdbc.sql("SELECT overview.sample_address_anchor('230202',:address)")
                .param("address", "坐标唯一性测试乡街道12号")
                .query(String.class).single()).isEqualTo(TOWNSHIP);
        assertThat(jdbc.sql("SELECT overview.sample_address_anchor('230203',:address)")
                .param("address", "坐标唯一性测试乡坐标唯一性测试村")
                .query(String.class).single()).isEqualTo("230203");
    }

    @Test
    void formalAddressChangeReanchorsWithoutChangingReportedCoordinates() {
        jdbc.sql("UPDATE registry.sample_point SET region_code='230202' WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).update();
        String reported = jdbc.sql("SELECT ST_AsEWKT(governed_point) FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).query(String.class).single();
        jdbc.sql("""
                INSERT INTO registry.formal_sample_point_profile(sample_point_id,object_type_code,address,created_by,updated_by)
                VALUES(:id,'FARMER','坐标唯一性测试乡坐标唯一性测试村','production-tester','production-tester')
                """).param("id", OCCUPIED_POINT).update();
        assertThat(jdbc.sql("""
                SELECT p.display_region_code=:region AND ST_Covers(b.geometry,p.display_point)
                AND p.location_mode='REGION_SCHEMATIC' AND ST_AsEWKT(p.governed_point)=:reported
                FROM registry.sample_point p JOIN overview.administrative_boundary_render b
                ON b.region_code=p.display_region_code WHERE p.sample_point_id=:id
                """).param("region", REGION).param("reported", reported).param("id", OCCUPIED_POINT)
                .query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE registry.formal_sample_point_profile SET address='建华区未匹配街道' WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).update();
        assertThat(jdbc.sql("SELECT display_region_code FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).query(String.class).single()).isEqualTo("230202");
    }

    @Test
    void designAddressReanchorsAndKeepsPlacementStableAcrossCoordinateEdits() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                VALUES(:id,'design-sample-fields-v1','PRODUCTION','CORN','FARMER',
                  '{"DSP_ADDRESS":"坐标唯一性测试乡坐标唯一性测试村"}'::jsonb,'地址锚点测试','230202',
                  ST_SetSRID(ST_MakePoint(124.5,48.5),4326),:key,repeat('a',64),'production-tester','production-tester')
                """).param("id", id).param("key", "address-"+id).update();
        String display = jdbc.sql("SELECT ST_AsEWKT(display_point) FROM platform.design_sample_point WHERE design_sample_point_id=:id")
                .param("id", id).query(String.class).single();
        jdbc.sql("UPDATE platform.design_sample_point SET governed_point=ST_SetSRID(ST_MakePoint(124.6,48.6),4326) WHERE design_sample_point_id=:id")
                .param("id", id).update();
        assertThat(jdbc.sql("""
                SELECT display_region_code=:region AND ST_AsEWKT(display_point)=:display
                AND NOT ST_Equals(display_point,governed_point)
                FROM platform.design_sample_point WHERE design_sample_point_id=:id
                """).param("region", REGION).param("display", display).param("id", id)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void disambiguatesRepeatedVillageNamesUsingTheNamedTownship() {
        GovernedMasterDataFixtures.insertRegion(jdbc, "230202997", "另一个测试乡", "230202", "TOWNSHIP", 997);
        GovernedMasterDataFixtures.insertRegion(jdbc, "230202997001", "坐标唯一性测试村", "230202997", "VILLAGE", 1);
        assertThat(jdbc.sql("SELECT overview.sample_address_anchor('230202',:address)")
                .param("address", "坐标唯一性测试乡坐标唯一性测试村三组")
                .query(String.class).single()).isEqualTo(REGION);
        assertThat(jdbc.sql("SELECT overview.sample_address_anchor('230202',:address)")
                .param("address", "坐标唯一性测试村三组")
                .query(String.class).single()).isEqualTo("230202");
    }

    @Test
    void missingVillageRenderBoundaryFallsBackWithinTheSelectedHierarchy() {
        GovernedMasterDataFixtures.insertRegion(jdbc, "230202998002", "无边界测试村", TOWNSHIP, "VILLAGE", 2);
        jdbc.sql("UPDATE registry.sample_point SET region_code='230202' WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).update();
        assertThatCode(() -> jdbc.sql("""
                INSERT INTO registry.formal_sample_point_profile(sample_point_id,object_type_code,address,created_by,updated_by)
                VALUES(:id,'FARMER','坐标唯一性测试乡无边界测试村','production-tester','production-tester')
                """).param("id", OCCUPIED_POINT).update()).doesNotThrowAnyException();
        assertThat(jdbc.sql("""
                SELECT ST_Covers(b.geometry,p.display_point)
                FROM registry.sample_point p JOIN overview.administrative_boundary_render b
                ON b.region_code=p.display_region_code WHERE sample_point_id=:id
                """).param("id", OCCUPIED_POINT).query(Boolean.class).single()).isTrue();
    }

    @Test
    void mapRevisionChangesWithEditsAndRollsBackWithTheTransaction() {
        String before = jdbc.sql("SELECT revision::text FROM overview.map_revision").query(String.class).single();
        jdbc.sql("SAVEPOINT map_edit").update();
        jdbc.sql("UPDATE registry.sample_point SET canonical_name='地图版本测试' WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT).update();
        assertThat(jdbc.sql("SELECT revision::text FROM overview.map_revision").query(String.class).single())
                .isNotEqualTo(before);
        jdbc.sql("ROLLBACK TO SAVEPOINT map_edit").update();
        assertThat(jdbc.sql("SELECT revision::text FROM overview.map_revision").query(String.class).single())
                .isEqualTo(before);
    }

    private void countyBoundary() {
        jdbc.sql("""
                UPDATE overview.administrative_boundary
                SET geometry=ST_Multi(ST_MakeEnvelope(122,46,125,49,4326))
                WHERE region_code='230202'
                """).update();
    }

    @Test
    void rejectsNumericallyEqualCoordinatesForADifferentStablePoint() {
        UUID otherPoint = UUID.fromString("95000000-0000-0000-0000-000000000002");

        assertThatThrownBy(() -> guard.lockAndRequireAvailable(
                otherPoint, new BigDecimal("123.510000"), new BigDecimal("47.9200")))
                .isInstanceOfSatisfying(ConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SAMPLE_POINT_COORDINATE_OCCUPIED");
                    assertThat(exception.clientMessage()).isEqualTo(
                            "该经纬度已被其他样本点使用，请核对真实坐标");
                });
    }

    @Test
    void permitsTheSameCoordinateForTheSameStablePointAcrossBusinessHistory() {
        assertThatCode(() -> guard.lockAndRequireAvailable(
                OCCUPIED_POINT, new BigDecimal("123.5100"), new BigDecimal("47.920000")))
                .doesNotThrowAnyException();
    }

    @Test
    void permitsDifferentNumericCoordinatesWithoutImposingDecimalPlaces() {
        assertThatCode(() -> guard.lockAndRequireAvailable(
                UUID.fromString("95000000-0000-0000-0000-000000000003"),
                new BigDecimal("123.5101"), new BigDecimal("47.9200")))
                .doesNotThrowAnyException();
    }

    @Test
    void permitsOnlyTheExactOccupantSetBoundIntoAReviewedColocationDecision() {
        assertThatCode(() -> guard.lockAndRequireReviewedSharing(
                null, new BigDecimal("123.5100"), new BigDecimal("47.920000"),
                Set.of(OCCUPIED_POINT))).doesNotThrowAnyException();

        UUID lateOccupant = UUID.fromString("95000000-0000-0000-0000-000000000004");
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE','审核后新增占用者',:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.51,47.92),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", lateOccupant).param("region", REGION).update();

        assertThatThrownBy(() -> guard.lockAndRequireReviewedSharing(
                null, new BigDecimal("123.51"), new BigDecimal("47.92"),
                Set.of(OCCUPIED_POINT)))
                .isInstanceOfSatisfying(ConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                "SAMPLE_POINT_COORDINATE_REVIEW_STALE"));
    }
}
