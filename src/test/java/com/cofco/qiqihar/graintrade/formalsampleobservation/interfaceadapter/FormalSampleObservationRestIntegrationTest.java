package com.cofco.qiqihar.graintrade.formalsampleobservation.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.AdministrativeBoundarySnapshot;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class FormalSampleObservationRestIntegrationTest {
    private static final String ACTOR = "production-tester";
    private static final String RESTRICTED_ACTOR = "formal-observation-restricted";
    private static final UUID SAMPLE_POINT_ID =
            UUID.fromString("f5100000-0000-0000-0000-000000000001");
    private static final String RECORD_ID = "f5100000-0000-0000-0000-000000000002";
    private static final String MARKET_RECORD_ID = "f5100000-0000-0000-0000-000000000003";
    private static final String LOGISTICS_RECORD_ID = "f5100000-0000-0000-0000-000000000004";
    private static final UUID SHADOWED_SAMPLE_POINT_ID =
            UUID.fromString("f5100000-0000-0000-0000-000000000005");
    private static final String SHADOWING_PRODUCTION_RECORD_ID =
            "f5100000-0000-0000-0000-000000000006";
    private static final String SHADOWING_MARKET_RECORD_ID =
            "f5100000-0000-0000-0000-000000000007";
    private static final String FUTURE_PRODUCTION_RECORD_ID =
            "f5100000-0000-0000-0000-000000000008";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private AdministrativeBoundarySnapshot boundarySnapshot;

    @BeforeEach
    void setUpFormalProductionSample() {
        jdbc = JdbcClient.create(dataSource);
        clearFixture();
        boundarySnapshot = AdministrativeBoundarySnapshot.capture(jdbc, "230221");
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                SELECT :subject,'受限观测测试员',work_unit_code,true FROM platform.security_user
                WHERE subject_id=:actor
                """).param("subject", RESTRICTED_ACTOR).param("actor", ACTOR).update();
        jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:subject,'BUSINESS_OPERATOR')")
                .param("subject", RESTRICTED_ACTOR).update();
        jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES(:subject,'230202')")
                .param("subject", RESTRICTED_ACTOR).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230221',ST_Multi(ST_MakeEnvelope(123.0,47.0,123.5,47.6,4326)),
                  'formal observation fixture','urn:test:formal-observation','test-v1','Test fixture',
                  '230221',DATE '2026-08-28',repeat('5',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,
                  source_name=excluded.source_name,source_url=excluded.source_url,
                  source_revision=excluded.source_revision,source_license=excluded.source_license,
                  source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,
                  geometry_sha256=excluded.geometry_sha256
                """).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                VALUES(:samplePointId,'SURVEY_SITE','龙江县既有正式样本','230221','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.2000000,47.3000000),4326),DATE '2026-01-01',:actor,:actor)
                """).param("samplePointId", SAMPLE_POINT_ID).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:recordId,'CORN','FARMER','230221',DATE '2026-08-20',
                  TIMESTAMPTZ '2026-08-20 09:30:00+08',100,500,'APPROVED',:actor,
                  2026,'YEAR','CONFIRMED',:samplePointId)
                """).param("recordId", RECORD_ID).param("actor", ACTOR)
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:recordId,'PROD_SAMPLE_NAME','龙江县既有正式样本'),
                      (:recordId,'PROD_SAMPLE_CONTACT','13800000000'),
                      (:recordId,'PROD_SAMPLE_LATITUDE','47.3000000'),
                      (:recordId,'PROD_SAMPLE_LONGITUDE','123.2000000')
                """).param("recordId", RECORD_ID).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  packaging_amount,freight_amount,packaging_form,status_code,last_modified_by,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:id,'CORN','TRADER','230221',DATE '2026-08-20',
                  TIMESTAMPTZ '2026-08-20 09:30:00+08',2300,2380,'BOTH',36,12,72,'BULK',
                  'APPROVED',:actor,2026,8,'YEAR_MONTH','CONFIRMED',:samplePointId)
                """).param("id", MARKET_RECORD_ID).param("actor", ACTOR)
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_SAMPLE_NAME','EXTENSION','龙江县既有正式样本'),
                      (:id,'CORN','MKT_SAMPLE_CONTACT','EXTENSION','13800000000'),
                      (:id,'CORN','MKT_SAMPLE_LATITUDE','EXTENSION','47.3000000'),
                      (:id,'CORN','MKT_SAMPLE_LONGITUDE','EXTENSION','123.2000000')
                """).param("id", MARKET_RECORD_ID).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,collection_date,reported_at,origin_region_code,destination_region_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,version,
                  created_by,last_modified_by,created_at,updated_at,business_region_code,sample_contact,
                  sample_latitude,sample_longitude,survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state,sample_point_id)
                VALUES(:id,'CORN',DATE '2026-08-20',TIMESTAMPTZ '2026-08-20 09:30:00+08',
                  '230221','230221','ROAD','INFLOW','龙江县既有正式样本','产情测试员','APPROVED',0,
                  :actor,:actor,now(),now(),'230221','13800000000',47.3,123.2,2026,8,
                  'YEAR_MONTH','CONFIRMED',:samplePointId)
                """).param("id", UUID.fromString(LOGISTICS_RECORD_ID)).param("actor", ACTOR)
                .param("samplePointId", SAMPLE_POINT_ID).update();
    }

    @AfterEach
    void tearDown() {
        clearFixture();
        if (boundarySnapshot != null) boundarySnapshot.restore(jdbc);
    }

    @Test
    void listsOnlyWritableEffectiveFormalSamplesWithoutWorkflowState() throws Exception {
        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230221")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SAMPLE_POINT_ID.toString()))
                .andExpect(jsonPath("$.data[0].sampleName").value("龙江县既有正式样本"))
                .andExpect(jsonPath("$.data[0].objectTypeCode").value("FARMER"))
                .andExpect(jsonPath("$.data[0].objectTypeName").isNotEmpty())
                .andExpect(jsonPath("$.data[0].regionCode").value("230221"))
                .andExpect(jsonPath("$.data[0].regionName").isNotEmpty())
                .andExpect(jsonPath("$.data[0].latitude").value("47.3000000"))
                .andExpect(jsonPath("$.data[0].longitude").value("123.2000000"))
                .andExpect(jsonPath("$.data[0].effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.data[0].latestObservationId").value(RECORD_ID))
                .andExpect(jsonPath("$.data[0].latestObservedAt").value("2026-08-20T01:30:00Z"))
                .andExpect(jsonPath("$.data[0].latestValues.PROD_SAMPLE_CONTACT").value("13800000000"))
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.data[0].allowedActions").doesNotExist());
    }

    @Test
    void filtersEligibleSamplesByAuthoritativeObjectTypeAndBoundedKeyword() throws Exception {
        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "TRADER")
                        .queryParam("keyword", "  既有正式  ")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SAMPLE_POINT_ID.toString()))
                .andExpect(jsonPath("$.data[0].objectTypeCode").value("TRADER"))
                .andExpect(jsonPath("$.data[0].objectTypeName").isNotEmpty());

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("keyword", "%")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "NOT_A_MARKET_OBJECT")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_OBSERVATION_QUERY"));

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("keyword", "x".repeat(101))
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_OBSERVATION_QUERY"));
    }

    @Test
    void exposesTheSameCurrentFormalSampleProjectionAsOverviewForEachBusinessDomain()
            throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                VALUES(:samplePointId,'SURVEY_SITE','龙江县当前正式样本','230221','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.2100000,47.3100000),4326),DATE '2026-01-01',:actor,:actor)
                """).param("samplePointId", SHADOWED_SAMPLE_POINT_ID).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:recordId,'CORN','FARMER','230221',DATE '2026-08-21',
                  TIMESTAMPTZ '2026-08-21 09:30:00+08',110,510,'APPROVED',:actor,
                  2026,'YEAR','CONFIRMED',:samplePointId)
                """).param("recordId", SHADOWING_PRODUCTION_RECORD_ID).param("actor", ACTOR)
                .param("samplePointId", SHADOWED_SAMPLE_POINT_ID).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:recordId,'PROD_SAMPLE_NAME','龙江县既有正式样本'),
                      (:recordId,'PROD_SAMPLE_CONTACT','13800000000'),
                      (:recordId,'PROD_SAMPLE_LATITUDE','47.3100000'),
                      (:recordId,'PROD_SAMPLE_LONGITUDE','123.2100000')
                """).param("recordId", SHADOWING_PRODUCTION_RECORD_ID).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  packaging_amount,freight_amount,packaging_form,status_code,last_modified_by,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:id,'CORN','TRADER','230221',DATE '2026-08-21',
                  TIMESTAMPTZ '2026-08-21 09:30:00+08',2310,2390,'BOTH',36,12,72,'BULK',
                  'APPROVED',:actor,2026,8,'YEAR_MONTH','CONFIRMED',:samplePointId)
                """).param("id", SHADOWING_MARKET_RECORD_ID).param("actor", ACTOR)
                .param("samplePointId", SHADOWED_SAMPLE_POINT_ID).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_SAMPLE_NAME','EXTENSION','龙江县既有正式样本'),
                      (:id,'CORN','MKT_SAMPLE_CONTACT','EXTENSION','13800000000'),
                      (:id,'CORN','MKT_SAMPLE_LATITUDE','EXTENSION','47.3100000'),
                      (:id,'CORN','MKT_SAMPLE_LONGITUDE','EXTENSION','123.2100000')
                """).param("id", SHADOWING_MARKET_RECORD_ID).update();

        for (String domain : java.util.List.of("PRODUCTION", "MARKET", "LOGISTICS")) {
            String eligible = mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                            .principal(() -> ACTOR).queryParam("domain", domain)
                            .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                            .queryParam("year", "2026")
                            .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String overview = mvc.perform(get("/api/v1/overview/sample-points")
                            .principal(() -> ACTOR).queryParam("categoryCode", domain)
                            .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                            .queryParam("year", "2026"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(samplePointIds(eligible, "/data"))
                    .as("%s eligible samples must use the overview formal-sample projection", domain)
                    .isEqualTo(samplePointIds(overview, "/data/items"));
        }
    }

    @Test
    void readsEligibleLatestValuesFromTheSelectedYearProjection() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:recordId,'CORN','FARMER','230221',DATE '2027-08-20',
                  TIMESTAMPTZ '2027-08-20 09:30:00+08',120,520,'APPROVED',:actor,
                  2027,'YEAR','CONFIRMED',:samplePointId)
                """).param("recordId", FUTURE_PRODUCTION_RECORD_ID).param("actor", ACTOR)
                .param("samplePointId", SAMPLE_POINT_ID).update();

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR).queryParam("domain", "PRODUCTION")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].latestObservationId").value(RECORD_ID))
                .andExpect(jsonPath("$.data[0].latestValues.surveyYear").value("2026"));
    }

    @Test
    void savesOneOfficialProductionObservationAndReplaysTheSameIdempotentResult() throws Exception {
        String request = """
                {
                  "domain":"PRODUCTION",
                  "samplePointId":"%s",
                  "productCode":"CORN",
                  "observedAt":"2026-08-28T10:15:00+08:00",
                  "payload":{
                    "productCode":"CORN","objectTypeCode":"FARMER","regionCode":"230202",
                    "surveyDate":"2026-08-01","surveyYear":2026,"surveyMonth":8,
                    "cultivatedAreaMu":"125","yieldPerMuKilograms":"510",
                    "quality":{"MOISTURE":"13.5"},"costs":{"LAND_RENT":"80"},
                    "insurance":{"INSURANCE_AMOUNT":"15"},"subsidies":{"SUBSIDY_AMOUNT":"20"},
                    "submissionMetadata":{
                      "PROD_REPORTER_NAME":"伪造填报员","PROD_SURVEYOR_NAME":"王雷",
                      "PROD_SURVEYOR_PHONE":"13800000000","PROD_SAMPLE_NAME":"伪造样本",
                      "PROD_SAMPLE_CONTACT":"19900000000","PROD_SAMPLE_LATITUDE":"1",
                      "PROD_SAMPLE_LONGITUDE":"2"
                    },"evidencePhotoIds":[]
                  }
                }
                """.formatted(SAMPLE_POINT_ID);

        String first = mvc.perform(post("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR).header("Idempotency-Key", "production-observation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.samplePointId").value(SAMPLE_POINT_ID.toString()))
                .andExpect(jsonPath("$.data.domain").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.productCode").value("CORN"))
                .andExpect(jsonPath("$.data.observedAt").value("2026-08-28T02:15:00Z"))
                .andExpect(jsonPath("$.data.synchronizedModules[0]").value("OVERVIEW"))
                .andReturn().getResponse().getContentAsString();

        String replay = mvc.perform(post("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR).header("Idempotency-Key", "production-observation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mvc.perform(post("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR).header("Idempotency-Key", "production-observation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"125\"", "\"126\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("FORMAL_SAMPLE_OBSERVATION_IDEMPOTENCY_CONFLICT"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE sample_point_id=:samplePointId AND product_code='CORN'
                  AND status_code='APPROVED' AND survey_period_governance_state='CONFIRMED'
                """).param("samplePointId", SAMPLE_POINT_ID).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT cultivated_area_mu::text FROM production.production_record
                WHERE sample_point_id=:samplePointId AND record_id<>:legacyId
                """).param("samplePointId", SAMPLE_POINT_ID).param("legacyId", RECORD_ID)
                .query(String.class).single()).isEqualTo("125.0000");
        assertThat(jdbc.sql("""
                SELECT value FROM production.production_record_submission_metadata
                WHERE record_id<>:legacyId AND field_code='PROD_SAMPLE_NAME'
                  AND record_id IN (SELECT record_id FROM production.production_record
                    WHERE sample_point_id=:samplePointId)
                """).param("legacyId", RECORD_ID).param("samplePointId", SAMPLE_POINT_ID)
                .query(String.class).single()).isEqualTo("龙江县既有正式样本");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_OBSERVATION'
                  AND action_code='FORMAL_SAMPLE_OBSERVATION_SAVED'
                """).query(Long.class).single()).isEqualTo(1);

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230221")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:16:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].latestValues.MOISTURE").value("13.5000"))
                .andExpect(jsonPath("$.data[0].latestValues.LAND_RENT").value("80.0000"))
                .andExpect(jsonPath("$.data[0].latestValues.INSURANCE_AMOUNT").value("15.0000"))
                .andExpect(jsonPath("$.data[0].latestValues.SUBSIDY_AMOUNT").value("20.0000"));

        mvc.perform(get("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("samplePointId", SAMPLE_POINT_ID.toString())
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].observationId").exists())
                .andExpect(jsonPath("$.data.items[0].observedAt").value("2026-08-28T02:15:00Z"))
                .andExpect(jsonPath("$.data.items[0].officialSavedAt").exists())
                .andExpect(jsonPath("$.data.items[0].actorDisplayName").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].actorDisplayName").value("产情测试员"))
                .andExpect(jsonPath("$.data.items[0].latest").value(true))
                .andExpect(jsonPath("$.data.items[0].values.cultivatedAreaMu").value("125.0000"))
                .andExpect(jsonPath("$.data.items[0].synchronizedModules[0]").value("OVERVIEW"))
                .andExpect(jsonPath("$.data.items[1].observationId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].actorDisplayName").value("产情测试员"))
                .andExpect(jsonPath("$.data.items[1].latest").value(false))
                .andExpect(jsonPath("$.data.items[1].values.cultivatedAreaMu").value("100.0000"));

        mvc.perform(get("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("samplePointId", SAMPLE_POINT_ID.toString())
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("pageNumber", "1")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(get("/api/v1/formal-sample-observations/observations")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("samplePointId", SAMPLE_POINT_ID.toString())
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("pageNumber", "2147483647")
                        .queryParam("pageSize", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_OBSERVATION_QUERY"));

        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production.metrics[?(@.code == 'CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("125.0000")))
                .andExpect(jsonPath("$.data.production.metrics[?(@.code == 'CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
        mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("125")))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\","
                                + "\"periodCode\":\"2026-08-28\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '核定数据条数')].value")
                        .value(org.hamcrest.Matchers.hasItem("1")));
    }

    @Test
    void savesMarketAndLogisticsObservationsDirectlyAsOfficialRecords() throws Exception {
        jdbc.sql("UPDATE market.market_record SET object_type_code='FEED_MILL' WHERE record_id=:id")
                .param("id", MARKET_RECORD_ID).update();
        String market = """
                {"domain":"MARKET","samplePointId":"%s","productCode":"CORN",
                 "observedAt":"2026-08-28T10:15:00+08:00","payload":{"productCode":"CORN",
                 "coreValues":{"MKT_OBJECT_TYPE":"TRADER","MKT_REGION":"230202",
                 "MKT_TRADE_DATE":"2026-08-01","MKT_PURCHASE_BASE_PRICE":"2310",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36",
                 "MKT_PACKAGING_AMOUNT":"12","MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_SAMPLE_NAME":"伪造样本","MKT_SAMPLE_CONTACT":"19900000000",
                 "MKT_SAMPLE_LATITUDE":"1","MKT_SAMPLE_LONGITUDE":"2"},
                 "facts":{"PURCHASE_VOLUME":"12"},"evidencePhotoIds":[]}}
                """.formatted(SAMPLE_POINT_ID);
        mvc.perform(post("/api/v1/formal-sample-observations/observations").principal(() -> ACTOR)
                        .header("Idempotency-Key", "market-observation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(market))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.domain").value("MARKET"))
                .andExpect(jsonPath("$.data.values.MKT_OBJECT_TYPE").value("FEED_MILL"))
                .andExpect(jsonPath("$.data.values.MKT_SALE_BASE_PRICE").doesNotExist())
                .andExpect(jsonPath("$.data.synchronizedModules[1]").value("MARKET_ANALYSIS"));

        mvc.perform(post("/api/v1/formal-sample-observations/observations").principal(() -> ACTOR)
                        .header("Idempotency-Key", "market-observation-injected-sale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(market.replace("\"MKT_PURCHASE_BASE_PRICE\":\"2310\"",
                                "\"MKT_PURCHASE_BASE_PRICE\":\"2310\",\"MKT_SALE_BASE_PRICE\":\"2390\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));

        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> ACTOR)
                        .queryParam("domain", "MARKET")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230221")
                        .queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:16:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].latestValues.PURCHASE_VOLUME").value("12.0000"));
        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2310.0000")))
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'AVERAGE_SALE_PRICE')]").isEmpty());
        mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230221")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2310")));

        String logistics = """
                {"domain":"LOGISTICS","samplePointId":"%s","productCode":"CORN",
                 "observedAt":"2026-08-28T10:15:00+08:00","payload":{"productCode":"CORN","values":{
                 "surveyYear":"2026","surveyMonth":"8","LOG_SAMPLE_NAME":"伪造样本",
                 "LOG_REGION":"230202","LOG_REPORTER":"伪造填报员","LOG_SAMPLE_CONTACT":"19900000000",
                 "LOG_SAMPLE_LATITUDE":"1","LOG_SAMPLE_LONGITUDE":"2","LOG_TRANSPORT_MODE":"ROAD",
                 "LOG_DIRECTION":"INFLOW","LOG_ROUTE_VOLUME":"12.5","LOG_FREIGHT_RATE":"80",
                 "LOG_BOARD_PRICE":"2650"}}}
                """.formatted(SAMPLE_POINT_ID);
        mvc.perform(post("/api/v1/formal-sample-observations/observations").principal(() -> ACTOR)
                        .header("Idempotency-Key", "logistics-observation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(logistics))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.domain").value("LOGISTICS"))
                .andExpect(jsonPath("$.data.synchronizedModules[1]").value("LOGISTICS_ANALYSIS"));

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record WHERE sample_point_id=:id AND status_code='APPROVED'")
                .param("id", SAMPLE_POINT_ID).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event WHERE sample_point_id=:id AND status_code='APPROVED'")
                .param("id", SAMPLE_POINT_ID).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void preventsCrossRegionSampleEnumerationAndWrites() throws Exception {
        mvc.perform(get("/api/v1/formal-sample-observations/eligible-samples")
                        .principal(() -> RESTRICTED_ACTOR).queryParam("domain", "PRODUCTION")
                        .queryParam("productCode", "CORN").queryParam("year", "2026")
                        .queryParam("observedAt", "2026-08-28T10:15:00+08:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        String request = """
                {"domain":"PRODUCTION","samplePointId":"%s","productCode":"CORN",
                 "observedAt":"2026-08-28T10:15:00+08:00","payload":{"productCode":"CORN",
                 "objectTypeCode":"FARMER","regionCode":"230202","surveyDate":"2026-08-28",
                 "surveyYear":2026,"surveyMonth":8,"cultivatedAreaMu":"1","yieldPerMuKilograms":"1",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},"submissionMetadata":{},
                 "evidencePhotoIds":[]}}
                """.formatted(SAMPLE_POINT_ID);
        mvc.perform(post("/api/v1/formal-sample-observations/observations")
                        .principal(() -> RESTRICTED_ACTOR).header("Idempotency-Key", "restricted-write-1")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FORMAL_SAMPLE_NOT_AVAILABLE"));
        mvc.perform(get("/api/v1/formal-sample-observations/observations")
                        .principal(() -> RESTRICTED_ACTOR)
                        .queryParam("domain", "PRODUCTION")
                        .queryParam("samplePointId", SAMPLE_POINT_ID.toString())
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM platform.formal_sample_observation WHERE actor_subject_id=:actor")
                .param("actor", RESTRICTED_ACTOR).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsStructurallyIncompleteDomainPayloadsAsClientErrors() throws Exception {
        for (String[] request : new String[][] {
                {"PRODUCTION", "{}", "invalid-production-payload"},
                {"MARKET", "{\"productCode\":\"CORN\",\"coreValues\":null,\"facts\":null,\"evidencePhotoIds\":null}",
                        "invalid-market-payload"},
                {"LOGISTICS", "{\"productCode\":\"CORN\",\"values\":null}", "invalid-logistics-payload"}
        }) {
            mvc.perform(post("/api/v1/formal-sample-observations/observations")
                            .principal(() -> ACTOR).header("Idempotency-Key", request[2])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"domain\":\"%s\",\"samplePointId\":\"%s\",\"productCode\":\"CORN\","
                                    .formatted(request[0], SAMPLE_POINT_ID)
                                    + "\"observedAt\":\"2026-08-28T10:15:00+08:00\",\"payload\":" + request[1] + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    private void clearFixture() {
        if (jdbc == null) return;
        jdbc.sql("DELETE FROM production.production_record WHERE record_id=:recordId")
                .param("recordId", FUTURE_PRODUCTION_RECORD_ID).update();
        jdbc.sql("DELETE FROM market.market_record WHERE record_id=:recordId")
                .param("recordId", SHADOWING_MARKET_RECORD_ID).update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id=:recordId")
                .param("recordId", SHADOWING_PRODUCTION_RECORD_ID).update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id=:samplePointId")
                .param("samplePointId", SHADOWED_SAMPLE_POINT_ID).update();
        jdbc.sql("DELETE FROM platform.formal_sample_observation WHERE sample_point_id=:samplePointId")
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("DELETE FROM production.production_record WHERE sample_point_id=:samplePointId AND record_id<>:recordId")
                .param("samplePointId", SAMPLE_POINT_ID).param("recordId", RECORD_ID).update();
        jdbc.sql("DELETE FROM market.market_record WHERE sample_point_id=:samplePointId")
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("DELETE FROM logistics.route_event WHERE sample_point_id=:samplePointId")
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id=:recordId")
                .param("recordId", RECORD_ID).update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id=:samplePointId")
                .param("samplePointId", SAMPLE_POINT_ID).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", RESTRICTED_ACTOR).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", RESTRICTED_ACTOR).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject", RESTRICTED_ACTOR).update();
    }

    private Set<UUID> samplePointIds(String response, String pointer) throws Exception {
        JsonNode samples = objectMapper.readTree(response).at(pointer);
        return java.util.stream.StreamSupport.stream(samples.spliterator(), false)
                .map(sample -> UUID.fromString(sample.get("samplePointId").asText()))
                .collect(java.util.stream.Collectors.toSet());
    }
}
