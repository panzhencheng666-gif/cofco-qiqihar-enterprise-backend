package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.DISTINCT;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.MATCHED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Outcome.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.SubjectInput;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
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
class JdbcSampleIdentityGovernanceRepositoryIntegrationTest {
    private static final String TOWNSHIP = "230202997";
    private static final String REGION = "230202997001";
    private static final UUID POINT = UUID.fromString("95100000-0000-0000-0000-000000000001");
    private static final String RECORD = "95100000-0000-0000-0000-000000000101";

    @Autowired DataSource dataSource;
    @Autowired JdbcSampleIdentityGovernanceRepository repository;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "身份判定测试乡", "230202", "TOWNSHIP", 997);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, REGION, "身份判定测试村", TOWNSHIP, "VILLAGE", 1);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:region,
                  ST_Multi(ST_GeomFromText(
                    'POLYGON((122 47,124 47,124 49,122 49,122 47))',4326)),
                  'identity assessment fixture','urn:test:sample-identity-assessment','test-v1',
                  'Test fixture',:region,DATE '2026-08-20',repeat('8',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry
                """).param("region", REGION).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:point,'SURVEY_SITE','王振锋',:region,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(122.48,48.07),4326),DATE '2024-01-01',0,
                  'production-tester','production-tester')
                """).param("point", POINT).param("region", REGION).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,sample_point_id,last_modified_by)
                VALUES(:record,'CORN','FARMER',:region,DATE '2024-08-01',now(),
                  10,20,'APPROVED',:point,'production-tester')
                """).param("record", RECORD).param("region", REGION).param("point", POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_NAME','王振锋'),
                      (:record,'PROD_SAMPLE_CONTACT','13800000001')
                """).param("record", RECORD).update();
    }

    @Test
    void assessesOnlyApprovedVisibleIdentityEvidence() {
        assertThat(repository.assess(input("138 0000-0001", "122.4800", "48.0700")).outcome())
                .isEqualTo(MATCHED);
        assertThat(repository.assess(input("13900000002", "122.50", "48.08")).outcome())
                .isEqualTo(DISTINCT);
        assertThat(repository.assess(input("13800000001", "123.00", "47.00")).outcome())
                .isEqualTo(MATCHED);
    }

    @Test
    void includesADifferentlyNamedPointWhenItsNumericCoordinateIsOccupied() {
        SampleIdentityAssessment result = repository.assess(new SubjectInput(
                "PRODUCTION", "同址另一经营主体", "13900000002", REGION,
                new BigDecimal("122.4800"), new BigDecimal("48.070000")));

        assertThat(result.outcome()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo("SAMPLE_COORDINATE_SHARED_REVIEW_REQUIRED");
        assertThat(result.candidates()).extracting(candidate -> candidate.samplePointId())
                .containsExactly(POINT);
    }

    private static SubjectInput input(String contact, String longitude, String latitude) {
        return new SubjectInput("PRODUCTION", "王振锋", contact, REGION,
                new BigDecimal(longitude), new BigDecimal(latitude));
    }
}
