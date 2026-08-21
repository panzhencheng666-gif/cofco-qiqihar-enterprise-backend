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
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','已占用坐标样本点',:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.51,47.92),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", OCCUPIED_POINT.toString()).param("region", REGION).update();
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
