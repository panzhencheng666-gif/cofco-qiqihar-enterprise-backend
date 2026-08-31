package com.cofco.qiqihar.graintrade.samplepoint.network.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePointReader;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkRepository;
import com.cofco.qiqihar.graintrade.samplepoint.network.infrastructure.JdbcAnnualSampleNetworkRepository;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.security.require-read-authentication=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(AnnualSampleNetworkRestIntegrationTest.RepositoryTestConfiguration.class)
class AnnualSampleNetworkRestIntegrationTest {
    private static final String OPERATOR = "annual-network-operator";
    private static final String REVIEWER = "annual-network-reviewer";
    private static final String NO_PERMISSION = "annual-network-no-permission";
    private static final String WORK_UNIT = "ANNUAL_NETWORK_TEST";
    private static final String PREFECTURE = "230200";
    private static final String TOWNSHIP = "230202995";
    private static final String VILLAGE_ONE = "230202995001";
    private static final String VILLAGE_TWO = "230202995002";
    private static final String OUTSIDE_COUNTY = "231199";
    private static final String OUTSIDE_TOWNSHIP = "231199995";
    private static final String OUTSIDE_VILLAGE = "231199995001";
    private static final String VILLAGE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000002";
    private static final String TOWNSHIP_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000003";
    private static final String COUNTY_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000004";
    private static final String PREFECTURE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000005";
    private static final String OUTSIDE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000006";
    private static final String MISSING_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000099";
    private static final String APPROVED_PRODUCTION_RECORD =
            "13300000-0000-0000-0000-000000000101";
    private static final String APPROVED_MARKET_RECORD =
            "13300000-0000-0000-0000-000000000102";
    private static final String LOGISTICS_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000007";
    private static final String APPROVED_LOGISTICS_RECORD =
            "13300000-0000-0000-0000-000000000103";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CoordinatedAnnualSampleNetworkRepository networkRepository;
    private JdbcClient jdbc;
    private boolean concurrentTransactionsTerminated = true;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        cleanOperationalRows();
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "年度网络接口测试乡", "230202", "TOWNSHIP", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE_ONE, "年度网络接口测试一村", TOWNSHIP, "VILLAGE", 1);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE_TWO, "年度网络接口测试二村", TOWNSHIP, "VILLAGE", 2);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_COUNTY, "年度网络接口测试外辖区县", "231100", "COUNTY", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_TOWNSHIP, "年度网络接口测试外辖乡", OUTSIDE_COUNTY,
                "TOWNSHIP", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_VILLAGE, "年度网络接口测试外辖村", OUTSIDE_TOWNSHIP,
                "VILLAGE", 1);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES
                  (:township,ST_Multi(ST_MakeEnvelope(123.7,47.1,123.9,47.3,4326)),
                    '年度网络测试边界','urn:test:annual-network','test-1','测试',:township,
                    DATE '2026-01-01',repeat('a',64)),
                  (:one,ST_Multi(ST_MakeEnvelope(123.75,47.15,123.805,47.205,4326)),
                    '年度网络测试边界','urn:test:annual-network','test-1','测试',:one,
                    DATE '2026-01-01',repeat('b',64)),
                  (:two,ST_Multi(ST_MakeEnvelope(123.805,47.205,123.86,47.26,4326)),
                    '年度网络测试边界','urn:test:annual-network','test-1','测试',:two,
                    DATE '2026-01-01',repeat('c',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=EXCLUDED.geometry,
                  source_name=EXCLUDED.source_name,source_url=EXCLUDED.source_url,
                  source_revision=EXCLUDED.source_revision,source_license=EXCLUDED.source_license,
                  source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).param("township", TOWNSHIP).param("one", VILLAGE_ONE)
                .param("two", VILLAGE_TWO).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        jdbc.sql("""
                INSERT INTO platform.geography_import_batch(
                  dataset_sha256,source_workbook_sha256,source_revision,
                  township_count,village_count,coordinate_count)
                VALUES(repeat('c',64),repeat('d',64),'annual-network-rest-test',1,2,2)
                ON CONFLICT(dataset_sha256) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.region_location(
                  region_code,original_coordinate,wgs84_coordinate,original_crs,target_crs,
                  conversion_method,source_name,source_url,source_revision,place_type,matched_by,
                  match_confidence,review_status,dataset_sha256)
                VALUES
                  (:one,ST_SetSRID(ST_MakePoint(123.80,47.20),4490),
                    ST_SetSRID(ST_MakePoint(123.80,47.20),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-rest-test','行政村','exact test match','HIGH','REVIEWED',repeat('c',64)),
                  (:two,ST_SetSRID(ST_MakePoint(123.81,47.21),4490),
                    ST_SetSRID(ST_MakePoint(123.81,47.21),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-rest-test','行政村','exact test match','HIGH','REVIEWED',repeat('c',64))
                """).param("one", VILLAGE_ONE).param("two", VILLAGE_TWO).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'年度样本网络测试单位',9935)
                ON CONFLICT(code) DO UPDATE SET
                  name=EXCLUDED.name,active=true,sort_order=EXCLUDED.sort_order;
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:prefecture) ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:operator,'年度网络填报员',:unit),(:reviewer,'年度网络管理员',:unit),
                      (:noPermission,'年度网络无写权限人员',:unit)
                ON CONFLICT(subject_id) DO UPDATE SET
                  display_name=EXCLUDED.display_name,
                  work_unit_code=EXCLUDED.work_unit_code,
                  enabled=true,account_status='ACTIVE',employment_status='ACTIVE',
                  termination_effective_at=NULL;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:operator,'BUSINESS_OPERATOR'),(:reviewer,'BUSINESS_REVIEWER')
                ON CONFLICT(subject_id,role_code,valid_from) DO UPDATE SET
                  valid_until=NULL,review_due_at=NULL;
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:operator,:prefecture),(:reviewer,:prefecture)
                ON CONFLICT(subject_id,region_code,valid_from) DO UPDATE SET
                  valid_until=NULL,review_due_at=NULL
                """).param("unit", WORK_UNIT).param("operator", OPERATOR)
                .param("reviewer", REVIEWER).param("noPermission", NO_PERMISSION)
                .param("prefecture", PREFECTURE).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES
                  (CAST(:villageId AS uuid),'SURVEY_SITE','年度网络村级真实样本点',:village,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:townshipId AS uuid),'SURVEY_SITE','年度网络乡镇级真实样本点',:township,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:countyId AS uuid),'SURVEY_SITE','年度网络区县级真实样本点','230202',
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:prefectureId AS uuid),'SURVEY_SITE','年度网络地市级真实样本点',:prefecture,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator)
                """).param("villageId", VILLAGE_SAMPLE_POINT)
                .param("townshipId", TOWNSHIP_SAMPLE_POINT)
                .param("countyId", COUNTY_SAMPLE_POINT)
                .param("prefectureId", PREFECTURE_SAMPLE_POINT)
                .param("village", VILLAGE_ONE).param("township", TOWNSHIP)
                .param("prefecture", PREFECTURE)
                .param("operator", OPERATOR).update();
        jdbc.sql("""
                UPDATE registry.sample_point point
                SET location_state='VALID',governed_point=ST_PointOnSurface(boundary.geometry)
                FROM overview.administrative_boundary boundary
                WHERE boundary.region_code=point.region_code
                  AND point.sample_point_id IN (
                    CAST(:villageId AS uuid),CAST(:townshipId AS uuid),CAST(:countyId AS uuid),
                    CAST(:prefectureId AS uuid))
                """).param("villageId", VILLAGE_SAMPLE_POINT)
                .param("townshipId", TOWNSHIP_SAMPLE_POINT)
                .param("countyId", COUNTY_SAMPLE_POINT)
                .param("prefectureId", PREFECTURE_SAMPLE_POINT).update();
    }

    @AfterEach
    void tearDown() {
        if (!concurrentTransactionsTerminated) {
            return;
        }
        TransactionTemplate cleanup = new TransactionTemplate(transactions);
        cleanup.setTimeout(10);
        cleanup.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
            jdbc.sql("SET LOCAL statement_timeout = '8s'").update();
            cleanOperationalRows();
            jdbc.sql("DELETE FROM platform.security_user_region_scope "
                            + "WHERE subject_id IN (:subjects)")
                    .param("subjects", List.of(OPERATOR, REVIEWER, NO_PERMISSION)).update();
            jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                    .param("unit", WORK_UNIT).update();
            jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN (:codes)")
                    .param("codes", List.of(VILLAGE_ONE, VILLAGE_TWO, TOWNSHIP)).update();
            jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                    .query(Object.class).single();
            GovernedMasterDataFixtures.deleteRegions(
                    jdbc, List.of(VILLAGE_ONE, VILLAGE_TWO, TOWNSHIP,
                            OUTSIDE_VILLAGE, OUTSIDE_TOWNSHIP, OUTSIDE_COUNTY));
        });
    }

    @Test
    void reportsEffectiveApprovedBusinessPointsWithoutAFormalAnnualNetwork() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-08-20',CURRENT_TIMESTAMP,
                  10,20,'APPROVED',:actor,CAST(:point AS uuid),2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", APPROVED_PRODUCTION_RECORD).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).param("point", VILLAGE_SAMPLE_POINT).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  packaging_amount,freight_amount,packaging_form,status_code,last_modified_by,
                  sample_point_id,survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state)
                VALUES(:id,'CORN','TRADER',:region,DATE '2026-08-20',CURRENT_TIMESTAMP,
                  2200,2250,'BOTH',0,0,0,'BULK','APPROVED',:actor,CAST(:point AS uuid),
                  2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", APPROVED_MARKET_RECORD).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).param("point", VILLAGE_SAMPLE_POINT).update();

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.networkStatus").value("NOT_CREATED"))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(1))
                .andExpect(jsonPath("$.data.actualPoints[0].samplePointId")
                        .value(VILLAGE_SAMPLE_POINT))
                .andExpect(jsonPath("$.data.actualPoints[0].membershipStatusCode").value("ACTIVE"));

        mvc.perform(get("/api/v1/sample-networks/{year}/design-comparison", 2026)
                        .principal(() -> OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.networkStatus").value("NOT_CREATED"))
                .andExpect(jsonPath("$.data.designPointCount").isNumber())
                .andExpect(jsonPath("$.data.designPoints").isArray())
                .andExpect(jsonPath("$.data.relations").isArray())
                .andExpect(jsonPath("$.data.actualPoints").doesNotExist());

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "RICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(0));

        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(
                  record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_LONGITUDE','130.0000000'),
                      (:id,'PROD_SAMPLE_LATITUDE','50.0000000')
                """).param("id", APPROVED_PRODUCTION_RECORD).update();

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(1));
    }

    @Test
    void removesAnInvalidatedCoordinateFromTheCurrentAnnualSampleCount() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-08-20',CURRENT_TIMESTAMP,
                  10,20,'APPROVED',:actor,CAST(:point AS uuid),2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", APPROVED_PRODUCTION_RECORD).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).param("point", VILLAGE_SAMPLE_POINT).update();

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(1));

        jdbc.sql("""
                UPDATE registry.sample_point
                SET location_state='OUTSIDE_REGION',governed_point=NULL,
                    containment_boundary_sha256=NULL,containment_boundary_revision=NULL
                WHERE sample_point_id=CAST(:point AS uuid)
                """).param("point", VILLAGE_SAMPLE_POINT).update();

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(0));
    }

    @Test
    void includesApprovedConfirmedLogisticsInTheCurrentAnnualSampleCount() throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                SELECT CAST(:point AS uuid),'LOGISTICS_NODE','年度网络物流样本点',:region,
                       'APPROVED','VALID',ST_PointOnSurface(boundary.geometry),
                       DATE '2026-01-01',:actor,:actor
                FROM overview.administrative_boundary boundary
                WHERE boundary.region_code=:region
                """).param("point", LOGISTICS_SAMPLE_POINT).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,collection_date,reported_at,
                  origin_region_code,destination_region_code,transport_mode_code,
                  direction_code,source_organization,reporter,status_code,version,
                  created_by,last_modified_by,created_at,updated_at,business_region_code,
                  survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state,sample_point_id,
                  sample_longitude,sample_latitude)
                SELECT CAST(:id AS uuid),'CORN',DATE '2026-08-20',CURRENT_TIMESTAMP,
                       :region,:region,'ROAD','INFLOW','年度网络物流来源','年度网络物流调研员',
                       'APPROVED',0,:actor,:actor,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,:region,
                       2026,8,'YEAR_MONTH','CONFIRMED',CAST(:point AS uuid),
                       ST_X(point.governed_point),ST_Y(point.governed_point)
                FROM registry.sample_point point
                WHERE point.sample_point_id=CAST(:point AS uuid)
                """).param("id", APPROVED_LOGISTICS_RECORD)
                .param("point", LOGISTICS_SAMPLE_POINT).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).update();

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE_ONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(1))
                .andExpect(jsonPath("$.data.actualPoints[0].samplePointId")
                        .value(LOGISTICS_SAMPLE_POINT))
                .andExpect(jsonPath("$.data.actualPoints[0].samplePointKindCode")
                        .value("LOGISTICS_NODE"));
    }

    @Test
    void authenticatesAndAuthorizesBeforeLookingUpTheSamplePointIdentity() throws Exception {
        String body = """
                {"statusCode":"ACTIVE","sourceCode":"NEW",
                 "reason":"不得用响应差异枚举样本身份","version":0}
                """;

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, MISSING_SAMPLE_POINT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> NO_PERMISSION)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, MISSING_SAMPLE_POINT).principal(() -> NO_PERMISSION)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
    }

    @Test
    void blocksSubmitUntilTheLockedMemberTransactionCommits() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"并发提交前既有成员","version":0}
                                """))
                .andExpect(status().isOk());

        CountDownLatch memberReachedWrite = new CountDownLatch(1);
        CountDownLatch releaseMemberWrite = new CountDownLatch(1);
        CountDownLatch submitReachedRepository = new CountDownLatch(1);
        networkRepository.coordinateNextMembershipWrite(
                memberReachedWrite, releaseMemberWrite, submitReachedRepository);

        Future<MvcResult> member = null;
        Future<Integer> submit = null;
        TransactionTemplate submitTransaction = new TransactionTemplate(transactions);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        concurrentTransactionsTerminated = false;
        Throwable failure = null;
        try {
            member = executor.submit(() -> mvc.perform(
                            put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                                    2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"designVillageRegionCode":"%s",
                                             "relationType":"EXACT_VILLAGE",
                                             "statusCode":"ACTIVE","sourceCode":"NEW",
                                             "reason":"并发成员写入","version":0}
                                            """.formatted(VILLAGE_ONE)))
                    .andReturn());
            assertThat(memberReachedWrite.await(5, TimeUnit.SECONDS)).isTrue();

            submit = executor.submit(() -> submitTransaction.execute(status ->
                    networkRepository.submit(2026, 0, OPERATOR, Instant.now())));

            assertThat(submitReachedRepository.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitSubmitRowLock()).isTrue();
            assertThat(submit.isDone()).isFalse();
            releaseMemberWrite.countDown();

            assertThat(member.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(submit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } catch (Throwable primaryFailure) {
            failure = primaryFailure;
        } finally {
            boolean memberFinished = false;
            boolean submitFinished = false;
            Throwable cleanupFailure = null;
            try {
                releaseMemberWrite.countDown();
                executor.shutdown();
                memberFinished = awaitFutureCompletion(member);
                submitFinished = awaitFutureCompletion(submit);
                awaitExecutorTermination(executor);
            } catch (Throwable unexpectedCleanupFailure) {
                cleanupFailure = unexpectedCleanupFailure;
            } finally {
                concurrentTransactionsTerminated = executor.isTerminated();
                try {
                    networkRepository.resetCoordination();
                } catch (Throwable resetFailure) {
                    cleanupFailure = retainPrimaryFailure(cleanupFailure, resetFailure);
                }
            }
            cleanupFailure = retainPrimaryFailure(cleanupFailure, concurrentCleanupFailure(
                    memberFinished, submitFinished, concurrentTransactionsTerminated));
            failure = retainPrimaryFailure(failure, cleanupFailure);
        }
        if (failure != null) {
            rethrow(failure);
        }

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM registry.sample_network_membership
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                     + (SELECT count(*) FROM registry.sample_network_design_relation
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                """).param("sample", VILLAGE_SAMPLE_POINT).query(Long.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT status_code FROM registry.sample_network_year WHERE network_year=2026
                """).query(String.class).single()).isEqualTo("IN_REVIEW");
    }

    private static Throwable concurrentCleanupFailure(
            boolean memberFinished, boolean submitFinished, boolean executorFinished) {
        if (memberFinished && submitFinished && executorFinished) {
            return null;
        }
        return new AssertionError("Concurrent transaction cleanup failed: memberFinished="
                + memberFinished + ", submitFinished=" + submitFinished
                + ", executorFinished=" + executorFinished);
    }

    private static Throwable retainPrimaryFailure(
            Throwable primaryFailure, Throwable cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (cleanupFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
        return primaryFailure;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private static boolean awaitFutureCompletion(Future<?> future) {
        if (future == null) {
            return true;
        }
        try {
            future.get(5, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | CancellationException ignored) {
            return true;
        } catch (TimeoutException exception) {
            future.cancel(true);
            return false;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean awaitExecutorTermination(ExecutorService executor) {
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void exposesDesignReferencesAndPublishesAnIndependentlyReviewedAnnualNetwork() throws Exception {
        long initialEventSequence = jdbc.sql("""
                SELECT COALESCE(max(event_sequence),0)
                FROM platform.business_event_outbox
                """).query(Long.class).single();
        mvc.perform(get("/api/v1/sample-networks/design-points")
                        .principal(() -> OPERATOR).queryParam("regionCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].coordinateSourceRevision")
                        .value("annual-network-rest-test"));

        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("DRAFT"))
                .andExpect(jsonPath("$.data.memberships.length()").value(0));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":"EXACT_VILLAGE",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年真实在网样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships[0].samplePointId")
                        .value(VILLAGE_SAMPLE_POINT))
                .andExpect(jsonPath("$.data.memberships[0].statusCode").value("ACTIVE"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"2026年二村代表关系核验材料",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年乡镇级真实在网样本","version":0}
                                """.formatted(VILLAGE_TWO)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年区县级真实在网样本","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, PREFECTURE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年地市级真实在网样本","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", VILLAGE_TWO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designPointCount").value(1))
                .andExpect(jsonPath("$.data.networkStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(0))
                .andExpect(jsonPath("$.data.relations.length()").value(1))
                .andExpect(jsonPath("$.data.relations[0].reviewStatus")
                        .value("PENDING_REVIEW"));

        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(post("/api/v1/sample-networks/{year}/review", 2026)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"decision\":\"APPROVE\",\"reason\":\"名单核验通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(2));

        assertThat(jdbc.sql("""
                SELECT action_code
                FROM platform.business_event_outbox
                WHERE aggregate_type='SAMPLE_NETWORK_YEAR' AND aggregate_id='2026'
                  AND event_sequence>:initialEventSequence
                ORDER BY event_sequence
                """).param("initialEventSequence", initialEventSequence)
                .query(String.class).list()).containsExactly(
                        "SAMPLE_NETWORK_CREATED",
                        "SAMPLE_NETWORK_MEMBER_DECIDED",
                        "SAMPLE_NETWORK_MEMBER_DECIDED",
                        "SAMPLE_NETWORK_MEMBER_DECIDED",
                        "SAMPLE_NETWORK_MEMBER_DECIDED",
                        "SAMPLE_NETWORK_SUBMITTED",
                        "SAMPLE_NETWORK_PUBLISHED");
        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM platform.business_event_outbox
                WHERE aggregate_type='SAMPLE_NETWORK_YEAR' AND aggregate_id='2026'
                  AND event_sequence>:initialEventSequence
                  AND detail->>'surveyYear'='2026'
                  AND :prefecture=ANY(region_codes)
                """).param("initialEventSequence", initialEventSequence)
                .param("prefecture", PREFECTURE).query(Long.class).single()).isEqualTo(7L);

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designPointCount").value(2))
                .andExpect(jsonPath("$.data.designCoordinateCount").value(2))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(4))
                .andExpect(jsonPath("$.data.approvedSubmissionSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.pendingVerificationDesignPointCount").value(2))
                .andExpect(jsonPath("$.data.multipleActualPerDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.anomalyCount").value(0))
                .andExpect(jsonPath("$.data.actualLevelCounts.prefecture").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.township").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.county").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.village").value(1))
                .andExpect(jsonPath("$.data.exactCoveredDesignPointCount").value(1))
                .andExpect(jsonPath("$.data.representedDesignPointCount").value(1))
                .andExpect(jsonPath("$.data.regionalAssociationDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.unrelatedDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.designPoints.length()").value(2))
                .andExpect(jsonPath("$.data.designPoints[0].coordinateSourceName")
                        .value("test source"))
                .andExpect(jsonPath("$.data.designPoints[0].coordinateSourceRevision")
                        .value("annual-network-rest-test"))
                .andExpect(jsonPath("$.data.designPoints[0].coordinateMatchConfidence")
                        .value("HIGH"))
                .andExpect(jsonPath("$.data.designPoints[0].coordinateReviewStatus")
                        .value("REVIEWED"))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(4))
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='TOWNSHIP')]")
                        .exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='VILLAGE')]")
                        .exists())
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='REGIONAL_ASSOCIATION')]").exists())
                .andExpect(jsonPath("$.data.relations[?(@.relationType=='EXACT_VILLAGE')]")
                        .exists())
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='EXPLICIT_REPRESENTATION')]"
                                + ".reviewStatus")
                        .value("APPROVED"))
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='EXPLICIT_REPRESENTATION')]"
                                + ".reviewedBy")
                        .value(REVIEWER));

        mvc.perform(post("/api/v1/sample-networks/{year}", 2027)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carriedFromYear\":2026}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("DRAFT"))
                .andExpect(jsonPath("$.data.carriedFromYear").value(2026))
                .andExpect(jsonPath("$.data.memberships.length()").value(4))
                .andExpect(jsonPath("$.data.memberships[?(@.samplePointId=='%s')]"
                        .formatted(PREFECTURE_SAMPLE_POINT) + ".statusCode").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.memberships[0].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[1].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[2].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[3].sourceCode").value("CARRIED_FORWARD"));

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2027)
                        .principal(() -> OPERATOR).queryParam("regionCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.networkStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(0))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(0))
                .andExpect(jsonPath("$.data.relations.length()").value(2))
                .andExpect(jsonPath("$.data.relations[0].reviewStatus")
                        .value("PENDING_REVIEW"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM production.production_record WHERE survey_year=2027)
                     + (SELECT count(*) FROM market.market_record WHERE survey_year=2027)
                """).query(Long.class).single()).isZero();
    }

    @Test
    @Transactional
    void governsFormalAnnualNetworkWhenTheDesignReferenceViewIsEmpty() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-08-20',CURRENT_TIMESTAMP,
                  10,20,'APPROVED',:actor,CAST(:point AS uuid),2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", APPROVED_PRODUCTION_RECORD).param("region", VILLAGE_ONE)
                .param("actor", OPERATOR).param("point", VILLAGE_SAMPLE_POINT).update();
        long formalSampleCount = jdbc.sql("SELECT count(*) FROM registry.sample_point")
                .query(Long.class).single();
        long approvedBusinessRecordCount = jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE record_id=:id AND status_code='APPROVED'
                """).param("id", APPROVED_PRODUCTION_RECORD).query(Long.class).single();

        jdbc.sql("""
                DELETE FROM platform.region_location
                WHERE region_code IN (
                  SELECT village_region_code FROM registry.village_design_sample_point)
                """).update();
        assertThat(jdbc.sql("SELECT count(*) FROM registry.village_design_sample_point")
                .query(Long.class).single()).isZero();

        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("DRAFT"));
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"正式样本独立纳入年度网络","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships.length()").value(1));
        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("IN_REVIEW"));
        mvc.perform(post("/api/v1/sample-networks/{year}/review", 2026)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"decision\":\"APPROVE\","
                                + "\"reason\":\"正式样本年度名单核验通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("PUBLISHED"));

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designPointCount").value(0))
                .andExpect(jsonPath("$.data.designCoordinateCount").value(0))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(1))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(1))
                .andExpect(jsonPath("$.data.relations.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM registry.sample_point")
                .query(Long.class).single()).isEqualTo(formalSampleCount);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE record_id=:id AND status_code='APPROVED'
                """).param("id", APPROVED_PRODUCTION_RECORD).query(Long.class).single())
                .isEqualTo(approvedBusinessRecordCount);
    }

    @Test
    void returnsAnEmptyMembershipListWhenTheAuthorizedRegionSetIsEmpty() {
        jdbc.sql("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,version,created_by,created_at)
                VALUES(2028,'DRAFT',0,:actor,CURRENT_TIMESTAMP)
                """).param("actor", OPERATOR).update();

        assertThat(networkRepository.find(2028, Set.of())).hasValueSatisfying(network ->
                assertThat(network.memberships()).isEmpty());
        jdbc.sql("""
                UPDATE registry.sample_network_year
                SET status_code='PUBLISHED',submitted_by=:submitter,
                    submitted_at=CURRENT_TIMESTAMP,reviewed_by=:reviewer,
                    reviewed_at=CURRENT_TIMESTAMP,review_reason='测试发布态',
                    published_by=:reviewer,published_at=CURRENT_TIMESTAMP
                WHERE network_year=2028
                """).param("submitter", OPERATOR).param("reviewer", REVIEWER).update();
        assertThat(networkRepository.comparison(2028, null, null, Set.of()))
                .satisfies(comparison -> {
                    assertThat(comparison.designPoints()).isEmpty();
                    assertThat(comparison.actualPoints()).isEmpty();
                    assertThat(comparison.relations()).isEmpty();
                });
    }

    @Test
    void rejectsAnUnknownProductInsteadOfReportingZeroApprovedSubmissions()
            throws Exception {
        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR)
                        .queryParam("productCode", "UNKNOWN_PRODUCT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_PRODUCT_INVALID"));
    }

    @Test
    void preventsTheAnnualNetworkSubmitterFromApprovingTheirOwnList() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2028)
                        .principal(() -> REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2028, VILLAGE_SAMPLE_POINT).principal(() -> REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":"EXACT_VILLAGE",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2028年真实在网样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2028)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/sample-networks/{year}/review", 2028)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"decision\":\"APPROVE\",\"reason\":\"自审不允许\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void rejectsRelationsThatAreIncompleteOrClaimAnExactMatchOutsideTheMemberVillage()
            throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXACT_VILLAGE","statusCode":"ACTIVE",
                                 "sourceCode":"NEW","reason":"错误跨村精确关系","version":0}
                                """.formatted(VILLAGE_TWO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","statusCode":"ACTIVE",
                                 "sourceCode":"NEW","reason":"缺少关系类型","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"缺少代表关系依据","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership WHERE network_year=2026
                """).query(Long.class).single()).isZero();
    }

    @Test
    void hidesOutOfScopeActualIdentitiesAndRejectsOutOfScopeDesignRegions() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        insertOutsideFormalSamplePoint("APPROVED");

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, OUTSIDE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"外辖样本不得写入",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"外辖样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_NOT_FOUND"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, MISSING_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"不存在样本也不得被枚举",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"不存在样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_NOT_FOUND"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"外辖设计村不得写入",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"外辖设计关系","version":0}
                                """.formatted(OUTSIDE_VILLAGE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership WHERE network_year=2026
                """).query(Long.class).single()).isZero();
    }

    @Test
    void keepsVillageMembersWithoutReviewedRelationsOutOfRegionalAssociations() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"未建立设计关系的村级真实点","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", VILLAGE_ONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualPoints.length()").value(0))
                .andExpect(jsonPath("$.data.relations.length()").value(0))
                .andExpect(jsonPath("$.data.regionalAssociationDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.unrelatedDesignPointCount").value(1));
    }

    @Test
    void refusesToSubmitAWholeNetworkContainingMembersOutsideTheActorScope() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        insertOutsideFormalSamplePoint("PENDING_REVIEW");
        jdbc.sql("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,status_code,source_code,version,
                  decision_reason,decided_by,decided_at,created_by,created_at)
                VALUES(2026,CAST(:sample AS uuid),'ACTIVE','NEW',0,
                       '辖区外成员',:operator,CURRENT_TIMESTAMP,:operator,CURRENT_TIMESTAMP)
                """).param("sample", OUTSIDE_SAMPLE_POINT).param("operator", OPERATOR).update();

        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT status_code FROM registry.sample_network_year WHERE network_year=2026
                """).query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    @Transactional
    void refusesToCreateTheCitywideNetworkFromARestrictedCountyScope() throws Exception {
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:operator")
                .param("operator", OPERATOR).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                .param("unit", WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:operator,'230202')
                """).param("operator", OPERATOR).update();
        jdbc.sql("""
                DELETE FROM platform.region_location
                WHERE region_code IN (
                  SELECT village_region_code FROM registry.village_design_sample_point)
                """).update();
        assertThat(jdbc.sql("SELECT count(*) FROM registry.village_design_sample_point")
                .query(Long.class).single()).isZero();

        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_year WHERE network_year=2026
                """).query(Long.class).single()).isZero();
    }

    @Test
    void evaluatesSamplePointEligibilityAgainstTheRequestedNetworkYear() throws Exception {
        jdbc.sql("""
                UPDATE registry.sample_point
                SET effective_from=DATE '2025-01-01',effective_to=DATE '2025-12-31'
                WHERE sample_point_id=CAST(:sample AS uuid)
                """).param("sample", VILLAGE_SAMPLE_POINT).update();
        mvc.perform(post("/api/v1/sample-networks/{year}", 2025)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2025, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2025年度有效样本","version":0}
                                """))
                .andExpect(status().isOk());

        jdbc.sql("""
                UPDATE registry.sample_point
                SET effective_from=DATE '2027-01-01',effective_to=NULL
                WHERE sample_point_id=CAST(:sample AS uuid)
                """).param("sample", TOWNSHIP_SAMPLE_POINT).update();
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2025, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"未来样本不得提前加入","version":0}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_NOT_FOUND"));
    }

    @Test
    void rejectsNullMemberFieldsAndLeavesNoPartialRowsAfterTheDraftCloses() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":null,"sourceCode":"NEW",
                                 "reason":"空状态","version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_MEMBER_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":null,
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"空关系类型","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"触发非村级设计关系约束",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"验证关系失败整体回滚","version":0}
                                """.formatted(TOWNSHIP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM registry.sample_network_membership
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                     + (SELECT count(*) FROM registry.sample_network_design_relation
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                """).param("sample", TOWNSHIP_SAMPLE_POINT).query(Long.class).single()).isZero();

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":null,
                                 "reason":"空来源","version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_MEMBER_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"先建立可提交成员","version":0}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"关闭草稿后的关系",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"关闭后不得写入","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_NOT_EDITABLE"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM registry.sample_network_membership
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                     + (SELECT count(*) FROM registry.sample_network_design_relation
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                """).param("sample", TOWNSHIP_SAMPLE_POINT).query(Long.class).single()).isZero();
    }

    private boolean awaitSubmitRowLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean waiting = Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS(
                      SELECT 1
                      FROM pg_stat_activity
                      WHERE datname=current_database()
                        AND pid<>pg_backend_pid()
                        AND wait_event_type='Lock'
                        AND query LIKE '%UPDATE registry.sample_network_year%')
                    """).query(Boolean.class).single());
            if (waiting) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void cleanOperationalRows() {
        jdbc.sql("DELETE FROM logistics.route_event WHERE event_id=CAST(:id AS uuid)")
                .param("id", APPROVED_LOGISTICS_RECORD).update();
        jdbc.sql("DELETE FROM market.market_record WHERE record_id=:id")
                .param("id", APPROVED_MARKET_RECORD).update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id=:id")
                .param("id", APPROVED_PRODUCTION_RECORD).update();
        jdbc.sql("DELETE FROM registry.sample_network_design_relation "
                + "WHERE network_year IN (2025,2026,2027,2028)").update();
        jdbc.sql("DELETE FROM registry.sample_network_membership "
                + "WHERE network_year IN (2025,2026,2027,2028)")
                .update();
        jdbc.sql("DELETE FROM registry.sample_network_year "
                + "WHERE network_year IN (2025,2026,2027,2028)")
                .update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:ids)")
                .param("ids", List.of(UUID.fromString(VILLAGE_SAMPLE_POINT),
                        UUID.fromString(TOWNSHIP_SAMPLE_POINT),
                        UUID.fromString(COUNTY_SAMPLE_POINT),
                        UUID.fromString(PREFECTURE_SAMPLE_POINT),
                        UUID.fromString(OUTSIDE_SAMPLE_POINT),
                        UUID.fromString(LOGISTICS_SAMPLE_POINT)))
                .update();
        jdbc.sql("DELETE FROM platform.region_location WHERE region_code IN (:regions)")
                .param("regions", List.of(VILLAGE_ONE, VILLAGE_TWO)).update();
        jdbc.sql("DELETE FROM platform.geography_import_batch WHERE dataset_sha256=repeat('c',64)")
                .update();
    }

    private void insertOutsideFormalSamplePoint(String approvalState) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','年度网络外辖真实样本点','231100',
                  :approvalState,'MISSING',DATE '2026-01-01',:operator,:operator)
                """).param("id", OUTSIDE_SAMPLE_POINT).param("approvalState", approvalState)
                .param("operator", OPERATOR).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryTestConfiguration {
        @Bean
        @Primary
        CoordinatedAnnualSampleNetworkRepository coordinatedAnnualSampleNetworkRepository(
                DataSource dataSource, CurrentOverviewSamplePointReader overviewSamplePoints) {
            return new CoordinatedAnnualSampleNetworkRepository(
                    JdbcClient.create(dataSource), overviewSamplePoints);
        }
    }

    static class CoordinatedAnnualSampleNetworkRepository
            extends JdbcAnnualSampleNetworkRepository {
        private final JdbcClient jdbc;
        private volatile CountDownLatch membershipWriteReached;
        private volatile CountDownLatch membershipWriteRelease;
        private volatile CountDownLatch submitReached;

        CoordinatedAnnualSampleNetworkRepository(
                JdbcClient jdbc, CurrentOverviewSamplePointReader overviewSamplePoints) {
            super(jdbc, overviewSamplePoints);
            this.jdbc = jdbc;
        }

        @Override
        public AnnualSampleNetworkRepository.MembershipWriteResult upsertMembership(
                int year, UUID samplePointId, String designVillageRegionCode,
                String relationType, String evidenceReference, String statusCode,
                String sourceCode, String reason, long version, String actor, Instant now) {
            CountDownLatch reached = membershipWriteReached;
            CountDownLatch release = membershipWriteRelease;
            if (reached != null && release != null) {
                boundConcurrentStatements();
                reached.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Member write was not released by the concurrency test");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Concurrent member write was interrupted", exception);
                }
            }
            return super.upsertMembership(
                    year, samplePointId, designVillageRegionCode, relationType,
                    evidenceReference, statusCode, sourceCode, reason, version, actor, now);
        }

        @Override
        public int submit(int year, long version, String actor, Instant now) {
            CountDownLatch reached = submitReached;
            if (reached != null) {
                boundConcurrentStatements();
                reached.countDown();
            }
            return super.submit(year, version, actor, now);
        }

        private void boundConcurrentStatements() {
            jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
            jdbc.sql("SET LOCAL statement_timeout = '10s'").update();
        }

        void coordinateNextMembershipWrite(
                CountDownLatch reached, CountDownLatch release, CountDownLatch submit) {
            membershipWriteReached = reached;
            membershipWriteRelease = release;
            submitReached = submit;
        }

        void resetCoordination() {
            membershipWriteReached = null;
            membershipWriteRelease = null;
            submitReached = null;
        }
    }
}
