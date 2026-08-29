package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.hamcrest.Matchers.hasItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.AdministrativeBoundarySnapshot;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionRecordRestIntegrationTest {
    private static final String EVIDENCE_PHOTO_ID = "00000000-0000-0000-0000-000000000001";
    private static final Map<String, String> QUALITY_FACT = Map.of(
            "CORN", "MOISTURE", "SOYBEAN", "PROTEIN", "RICE", "MILLING_YIELD");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;
    private AdministrativeBoundarySnapshot boundarySnapshot;

    @BeforeEach
    void stageEvidencePhoto() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        boundarySnapshot = AdministrativeBoundarySnapshot.capture(jdbc, "230202");
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(CAST(:id AS uuid),'STAGED','fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,'测试水印','production-tester',now())
                """).param("id", EVIDENCE_PHOTO_ID).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230202',ST_Multi(ST_MakeEnvelope(122,46,125,49,4326)),
                  'production entry validation fixture','urn:test:production-sample-point','test-v1',
                  'Test fixture',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,source_url=excluded.source_url
                """).update();
    }

    @AfterEach
    void removeTestRecords() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        List<UUID> publicContractPoints = jdbc.sql("""
                SELECT DISTINCT record.sample_point_id
                FROM production.production_record record
                WHERE record.sample_point_id IS NOT NULL
                  AND (
                    record.last_modified_by='production-tester'
                    OR record.record_id::text IN (
                      SELECT aggregate_id FROM platform.business_audit_event
                      WHERE aggregate_type='PRODUCTION_RECORD'
                        AND actor_subject_id IN ('production-tester','market-tester')
                    )
                  )
                """).query(UUID.class).list();
        jdbc.sql("""
                DELETE FROM production.production_record
                WHERE record_id::text IN (
                    SELECT aggregate_id FROM platform.business_audit_event
                    WHERE aggregate_type = 'PRODUCTION_RECORD'
                      AND actor_subject_id IN ('production-tester', 'market-tester'))
                """).update();
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
        jdbc.sql(
                "DELETE FROM production.production_record WHERE last_modified_by = 'production-tester'").update();
        List<UUID> governedPoints = jdbc.sql("""
                SELECT sample_point_id FROM registry.sample_point_subject_identity
                WHERE business_domain='PRODUCTION' AND subject_id LIKE 'fixture-production-%'
                """).query(UUID.class).list();
        jdbc.sql("""
                SELECT platform.govern_master_data_change(
                  'SUBJECT',identity.business_domain || ':' || identity.subject_id,'DELETE',
                  to_jsonb(identity),clock_timestamp(),'production-tester','market-tester',
                  '自动化测试清理稳定主体映射')
                FROM registry.sample_point_subject_identity identity
                WHERE identity.business_domain='PRODUCTION'
                  AND identity.subject_id LIKE 'fixture-production-%'
                """).query(Long.class).list();
        if (!governedPoints.isEmpty()) {
            jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:ids)")
                    .param("ids", governedPoints).update();
        }
        if (!publicContractPoints.isEmpty()) {
            jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:ids)")
                    .param("ids", publicContractPoints).update();
        }
        jdbc.sql("""
                TRUNCATE platform.business_import_draft_evidence,
                  platform.import_job_photo,evidence.evidence_photo
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary "
                        + "WHERE source_url='urn:test:production-sample-point'")
                .update();
        boundarySnapshot.restore(jdbc);
    }

    @Test
    void usesTheSameSurveyDetailFieldsForDefinitionCreateDetailAndLedgerList() throws Exception {
        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", "CORN").queryParam("objectTypeCode", "FARMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value("production-survey-fields-v4"))
                .andExpect(jsonPath("$.data.contractDigest").value(
                        "sha256:07806fbda70354ee29b243020cd5508db52271f8d7c88ac540379a7c1c3297fe"))
                .andExpect(jsonPath("$.data.fields[0].code").value("objectTypeCode"))
                .andExpect(jsonPath("$.data.fields[1].code").value("regionCode"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'PROD_CULTIVAR_NAME')]").isEmpty())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'PROD_SAMPLE_SUBJECT_CODE')]").isEmpty())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'PROD_SAMPLE_NAME')].label").value("样本点名称"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'cultivatedAreaMu')].label").value("播种面积"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'yieldPerMuKilograms')].label").value("预计单产"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'estimatedOutputKilograms')].label").value("预计总产"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'yearOnYear')].label").value("与上年相比"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'PROD_OPENING_INVENTORY')].displayed").value(true))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'PROD_ENDING_INVENTORY')].label").value("期末余粮"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'TOXIN')].scale").value(4))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LAND_RENT')].scale").value(4))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'sample_point_id')]").isEmpty())
                .andExpect(jsonPath("$.data.groups[0].category").value("DETAIL"))
                .andExpect(jsonPath("$.data.groups[0].fields[*].code").value(hasItem("PROD_SAMPLE_NAME")))
                .andExpect(jsonPath("$.data.groups[0].fields[*].code").value(hasItem("PROD_ENDING_INVENTORY")));

        String body = withSampleName(fullDraftBody("CORN", "FARMER", "MOISTURE", null),
                        "龙江县第一调查户")
                .replace("\"PROD_SAMPLE_LONGITUDE\":\"123.9182\"",
                        "\"PROD_SAMPLE_LONGITUDE\":\"123.9182\","
                                + "\"PROD_HARVEST_AREA_MU\":\"96.5\","
                                + "\"PROD_GROWTH_STATUS\":\"长势良好\","
                                + "\"PROD_GROWTH_STAGE\":\"灌浆期\","
                                + "\"PROD_OPENING_INVENTORY\":\"12\","
                                + "\"PROD_SALES_VOLUME\":\"3\","
                                + "\"PROD_SELF_USE\":\"1\","
                                + "\"PROD_ENDING_INVENTORY\":\"8\","
                                + "\"PROD_INTENDED_AREA_MU\":\"110\","
                                + "\"PROD_INTENTION_REASON\":\"订单增加\"");
        String id = create(body);

        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_NAME").value("龙江县第一调查户"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_ENDING_INVENTORY").value("8"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SURPLUS_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SURPLUS_CUTOFF_DATE").doesNotExist());
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_NAME").value("龙江县第一调查户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_HARVEST_AREA_MU").value("96.5"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_GROWTH_STAGE").value("灌浆期"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].values.PROD_SURPLUS_SUBJECT_CODE").doesNotExist());
    }

    @Test
    void rejectsInternalSubjectAndInventoryGovernanceKeysFromBusinessRequests() throws Exception {
        for (String internalCode : List.of(
                "PROD_SAMPLE_SUBJECT_CODE", "PROD_SURPLUS_SUBJECT_CODE", "PROD_SURPLUS_CUTOFF_DATE")) {
            String body = fullDraftBody("CORN", "FARMER", "MOISTURE", null)
                    .replace("\"PROD_SAMPLE_LONGITUDE\":\"123.9182\"",
                            "\"PROD_SAMPLE_LONGITUDE\":\"123.9182\",\"" + internalCode + "\":\"internal-only\"");
            mockMvc.perform(post("/api/v1/production-records")
                            .principal(() -> "production-tester")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
        }
    }

    @Test
    void acceptsCurrentInventoryFieldsWithoutExposingInternalGovernanceInputs() throws Exception {
        String body = fullDraftBody("CORN", "FARMER", "MOISTURE", null)
                .replace("\"PROD_SAMPLE_LONGITUDE\":\"123.9182\"",
                        "\"PROD_SAMPLE_LONGITUDE\":\"123.9182\","
                                + "\"PROD_OPENING_INVENTORY\":\"12\","
                                + "\"PROD_ENDING_INVENTORY\":\"8\"");
        String id = create(body);
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_OPENING_INVENTORY").value("12"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_ENDING_INVENTORY").value("8"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SURPLUS_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SURPLUS_CUTOFF_DATE").doesNotExist());
    }

    @Test
    void approvalLinksPublicInventoryToASystemGovernedSamplePointWithoutPrivateSubjectInputs() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230202',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(122.345678,46.456789),4326),0.01)),
                  'production public inventory fixture','urn:test:production-sample-point','test-v1',
                  'Test fixture',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,source_url=excluded.source_url
                """).update();
        String body = fullDraftBody("CORN", "FARMER", "MOISTURE", null)
                .replace("\"47.3543\"", "\"46.456789\"")
                .replace("\"PROD_SAMPLE_LONGITUDE\":\"123.9182\"",
                        "\"PROD_SAMPLE_LONGITUDE\":\"122.345678\","
                                + "\"PROD_SAMPLE_NAME\":\"DEF-155系统治理样本点\","
                                + "\"PROD_OPENING_INVENTORY\":\"12\","
                                + "\"PROD_ENDING_INVENTORY\":\"8\"");
        String id = create(body);
        approve(id);

        assertThat(jdbc.sql("""
                SELECT sample_point_id IS NOT NULL FROM production.production_record WHERE record_id=:id
                """).param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record_submission_metadata
                WHERE record_id=:id AND field_code IN (
                  'PROD_SAMPLE_SUBJECT_CODE','PROD_SURPLUS_SUBJECT_CODE','PROD_SURPLUS_CUTOFF_DATE')
                """).param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsAProductionDraftWhoseCoordinatesFallOutsideTheDeclaredRegion() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230200',ST_Multi(ST_MakeEnvelope(124,48,125,49,4326)),
                  'coordinate governed region fixture','urn:test:production-sample-point','test-v1',
                  'Test fixture',repeat('9',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,source_url=excluded.source_url
                """).update();
        String body = withSampleName(validDraftBody(), "坐标权威地区新样本点")
                .replace("\"PROD_SAMPLE_CONTACT\":\"13900000000\"",
                        "\"PROD_SAMPLE_CONTACT\":\"13600000000\"")
                .replace("\"47.3543\"", "\"60\"")
                .replace("\"123.9182\"", "\"150\"");
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_COORDINATE_REGION_MISMATCH"));
    }

    @Test
    void rejectsUnknownQueryParametersAndRequiresAnAuthenticatedPrincipalForWrites() throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("unrecognized", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));

        mockMvc.perform(post("/api/v1/production-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void createsAndResubmitsProductionRecordsThroughAtomicSubmissionEndpoints() throws Exception {
        String body = fullDraftBody("CORN", "FARMER", "MOISTURE", null);
        String id = mockMvc.perform(post("/api/v1/production-records/submit")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT status_code FROM production.production_record WHERE record_id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:id
                  AND action_code IN ('PRODUCTION_RECORD_CREATED','PRODUCTION_RECORD_SUBMITTED')
                """).param("id", id).query(Long.class).single()).isEqualTo(2L);

        mockMvc.perform(post("/api/v1/production-records/{id}/return", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充现场依据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(put("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullDraftBody("CORN", "FARMER", "MOISTURE", 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @ParameterizedTest(name = "{0} / {2} round-trips all four confirmed fact categories")
    @MethodSource("farmerAndVillageContexts")
    void farmerAndVillageRoundTripAllConfirmedFactsAndTransitions(
            String product, String qualityCode, String objectType) throws Exception {
        expectDefinitionFacts(product, objectType, qualityCode, true);
        String id = create(fullDraftBody(product, objectType, qualityCode, null));

        expectFullFactDetail(id, product, objectType, qualityCode);
        expectFullFactList(product, objectType, qualityCode);

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));
        mockMvc.perform(post("/api/v1/production-records/{id}/return", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(put("/api/v1/production-records/{id}", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(fullDraftBody(product, objectType, qualityCode, 2L)
                                .replace("测试填报员", "修改填报员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.returnReason").value("补充依据"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("产情测试员"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_LATITUDE").value("47.3543"));
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'PRODUCTION_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(4L);
    }

    @ParameterizedTest(name = "{0} / {2} approval preserves all four formal fact categories")
    @MethodSource("farmerAndVillageContexts")
    void farmerAndVillageApprovePreservesAllFormalFacts(
            String product, String qualityCode, String objectType) throws Exception {
        expectDefinitionFacts(product, objectType, qualityCode, true);
        String id = create(fullDraftBody(product, objectType, qualityCode, null));

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));

        expectApprovedFullFactDetail(id, product, objectType, qualityCode);
        expectApprovedFullFactList(product, objectType, qualityCode);
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'PRODUCTION_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void forbidsTheSubmittingEmployeeFromApprovingTheSameRecord() throws Exception {
        String id = create(validDraftBody());
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_APPROVAL_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/production-records/{id}/return", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_RETURN_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

    }

    @Test
    void approvalDoesNotRepeatRegionValidationAfterSubmissionAcceptedTheRecord() throws Exception {
        JdbcClient.create(dataSource).sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230202',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),0.01)),
                  'cross-period approval fixture','urn:test:production-sample-point','test-v1',
                  'Test fixture',repeat('8',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,source_url=excluded.source_url
                """).update();
        String firstBody = withSampleName(validDraftBody(), "跨期审核决定样本点");
        String firstId = create(firstBody);
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", firstId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", firstId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());

        UUID secondPhotoId = UUID.randomUUID();
        JdbcClient.create(dataSource).sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','production-period-fixture.png','image/png',decode('00','hex'),
                  decode('01','hex'),1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,
                  123.9182,'产情跨期测试水印','production-tester',now())
                """).param("id", secondPhotoId).update();
        String body = firstBody
                .replace("2026-08-01", "2026-07-01")
                .replace(EVIDENCE_PHOTO_ID.toString(), secondPhotoId.toString());
        String id = create(body);

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(firstId));

        UUID samplePointId = JdbcClient.create(dataSource).sql("""
                SELECT sample_point_id FROM production.production_record WHERE record_id=:id
                """).param("id", firstId).query(UUID.class).single();
        JdbcClient.create(dataSource).sql("""
                DELETE FROM production.production_record WHERE record_id IN (:ids)
                """).param("ids", List.of(firstId, id)).update();
        JdbcClient.create(dataSource).sql("""
                DELETE FROM registry.sample_point WHERE sample_point_id=:samplePointId
                """).param("samplePointId", samplePointId).update();
    }

    @Test
    void rejectsCoordinateChangeForAnExistingStableIdentityAtEntry() throws Exception {
        String firstBody = withSampleName(validDraftBody(), "稳定位置样本点");
        String firstId = create(firstBody);
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", firstId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", firstId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());

        UUID secondPhotoId = UUID.randomUUID();
        JdbcClient.create(dataSource).sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','production-coordinate-change.png','image/png',decode('00','hex'),
                  decode('01','hex'),1,encode(sha256(decode('00','hex')),'hex'),now(),47.3544,
                  123.9183,'产情坐标变更测试水印','production-tester',now())
                """).param("id", secondPhotoId).update();
        String changedCoordinateBody = firstBody
                .replace("2026-08-01", "2026-07-01")
                .replace("\"PROD_SAMPLE_LATITUDE\":\"47.3543\"",
                        "\"PROD_SAMPLE_LATITUDE\":\"47.3544\"")
                .replace("\"PROD_SAMPLE_LONGITUDE\":\"123.9182\"",
                        "\"PROD_SAMPLE_LONGITUDE\":\"123.9183\"")
                .replace(EVIDENCE_PHOTO_ID.toString(), secondPhotoId.toString());

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(changedCoordinateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_IDENTITY_COORDINATE_MISMATCH"));
    }

    @Test
    void removesAnInvalidatedSampleFromTheCurrentCollectionListButKeepsItsHistory() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230202',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),0.01)),
                  'current collection fixture','urn:test:production-sample-point','test-v1',
                  'Test fixture',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,
                  source_name=excluded.source_name,source_url=excluded.source_url
                """).update();
        String id = create(withSampleName(validDraftBody(), "当前列表有效样本点"));
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        UUID samplePointId = jdbc.sql(
                        "SELECT sample_point_id FROM production.production_record WHERE record_id=:id")
                .param("id", id).query(UUID.class).single();

        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));

        jdbc.sql("""
                UPDATE overview.administrative_boundary
                SET source_revision='test-v2' WHERE region_code='230202'
                """).update();
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        jdbc.sql("""
                UPDATE overview.administrative_boundary
                SET source_revision='test-v1' WHERE region_code='230202'
                """).update();

        jdbc.sql("""
                UPDATE registry.sample_point
                SET location_state='OUTSIDE_REGION',governed_point=NULL,
                    containment_boundary_sha256=NULL,containment_boundary_revision=NULL
                WHERE sample_point_id=:id
                """).param("id", samplePointId).update();

        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void voidsADraftThroughHttpAndPersistsATerminalAuditedState() throws Exception {
        String id = create(withSampleName(validDraftBody(), "正式筛选样本点"));

        mockMvc.perform(post("/api/v1/production-records/{id}/void", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));

        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT status_code FROM production.production_record WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("VOIDED");
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:id
                  AND action_code='PRODUCTION_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:id
                  AND action_code='PRODUCTION_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_TRANSITION"));
    }

    @ParameterizedTest(name = "{0} / AGRICULTURAL_TECH_STATION round-trips quality only")
    @MethodSource("productQualityContexts")
    void agriculturalTechStationRoundTripsOnlyItsConfirmedQualityFact(
            String product, String qualityCode) throws Exception {
        String objectType = "AGRICULTURAL_TECH_STATION";
        expectDefinitionFacts(product, objectType, qualityCode, false);
        String id = create(qualityOnlyDraftBody(product, objectType, qualityCode));

        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs").isEmpty())
                .andExpect(jsonPath("$.data.insurance").isEmpty())
                .andExpect(jsonPath("$.data.subsidies").isEmpty());
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @ParameterizedTest(name = "{0} tech station rejects {2}")
    @MethodSource("agriculturalTechStationRejectedFacts")
    void agriculturalTechStationRejectsEachUnsupportedFactCategoryWithoutPartialWrites(
            String product, String qualityCode, String category, String factCode) throws Exception {
        long before = actorBusinessRowCount();

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsupportedTechStationDraftBody(product, qualityCode, category, factCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_FACT"));

        org.assertj.core.api.Assertions.assertThat(actorBusinessRowCount()).isEqualTo(before);
    }

    @Test
    void exposesFourOrderedChineseGroupsAndObjectApplicabilityForAllProducts() throws Exception {
        for (String product : QUALITY_FACT.keySet()) {
            for (String objectType : new String[] {
                    "FARMER", "VILLAGE_COMMITTEE", "AGRICULTURAL_TECH_STATION"}) {
                var result = mockMvc.perform(get("/api/v1/production-record-definitions")
                                .queryParam("productCode", product)
                                .queryParam("objectTypeCode", objectType))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.productCode").value(product))
                        .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                        .andExpect(jsonPath("$.data.groups.length()").value(5))
                        .andExpect(jsonPath("$.data.groups[0].category").value("DETAIL"))
                        .andExpect(jsonPath("$.data.groups[0].label").value("业务调查明细"))
                        .andExpect(jsonPath("$.data.groups[0].fields.length()").isNotEmpty())
                        .andExpect(jsonPath("$.data.groups[1].category").value("QUALITY"))
                        .andExpect(jsonPath("$.data.groups[1].label").value("质量指标"))
                        .andExpect(jsonPath("$.data.groups[1].sortOrder").value(10))
                        .andExpect(jsonPath("$.data.groups[1].fields.length()").isNotEmpty())
                        .andExpect(jsonPath("$.data.groups[2].category").value("COST"))
                        .andExpect(jsonPath("$.data.groups[2].label").value("生产成本"))
                        .andExpect(jsonPath("$.data.groups[3].category").value("INSURANCE"))
                        .andExpect(jsonPath("$.data.groups[3].label").value("农业保险"))
                        .andExpect(jsonPath("$.data.groups[4].category").value("SUBSIDY"))
                        .andExpect(jsonPath("$.data.groups[4].label").value("农业补贴"));
                if (objectType.equals("AGRICULTURAL_TECH_STATION")) {
                    result.andExpect(jsonPath("$.data.groups[2].fields").isEmpty())
                            .andExpect(jsonPath("$.data.groups[3].fields").isEmpty())
                            .andExpect(jsonPath("$.data.groups[4].fields").isEmpty());
                } else {
                    result.andExpect(jsonPath("$.data.groups[2].fields").isNotEmpty())
                            .andExpect(jsonPath("$.data.groups[3].fields").isNotEmpty())
                            .andExpect(jsonPath("$.data.groups[4].fields").isNotEmpty());
                }
            }
        }
    }

    @Test
    void obtainsObjectTypeAndCultivarApplicabilityFromMasterData() throws Exception {
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"FARMER\"", "\"TRADER\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_OBJECT_TYPE"));

        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"surveyDate\"", "\"cultivarCode\":\"HEINONG_84\",\"surveyDate\"")
                                .replace("\"SOYBEAN\"", "\"CORN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_CULTIVAR"));
    }

    @Test
    void rejectsStrictInvalidFiltersFutureDatesAndIllegalTransitions() throws Exception {
        for (String query : new String[] {
                "filter.status=NOT_A_STATUS", "filter.objectTypeCode=TRADER", "filter.surveyDate=2026-99-99"}) {
            mockMvc.perform(get("/api/v1/production-records?productCode=SOYBEAN&pageKind=MONITORING&pageNumber=0&pageSize=20&" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));
        }
        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("2026-08-01", "2099-08-01")))
                .andExpect(status().isBadRequest());

        String id = create(validDraftBody());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_TRANSITION"));
    }

    @Test
    void listsDatabaseLabelsAndApplicationComputedActions() throws Exception {
        String id = create(fullDraftBody("SOYBEAN", "FARMER", "PROTEIN", null));
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_REGION").value("龙沙区"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_OBJECT_TYPE").value("农户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_STATUS").value("已审核"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_REPORTER_NAME").value("产情测试员"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_LONGITUDE").value("123.9182"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"))
                ;
    }

    @Test
    void acceptsUsefulCoordinatePrecisionAndRejectsValuesOutsideGlobalBoundsWithoutWriting() throws Exception {
        String valid = validDraftBody();
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("\"47.3543\"", "\"47.3543000000001\"")
                                .replace("\"123.9182\"", "\"123.9182000000001\"")))
                .andExpect(status().isCreated());

        long before = actorBusinessRowCount();
        for (String body : List.of(
                valid.replace("\"47.3543\"", "\"90.0000000000000001\""),
                valid.replace("\"123.9182\"", "\"-180.0000001\""))) {
            mockMvc.perform(post("/api/v1/production-records")
                            .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
        }
        assertThat(actorBusinessRowCount()).isEqualTo(before);
    }

    @Test
    void filtersByExplicitSurveyPeriodAndRealDraftOrSubmissionTime() throws Exception {
        String id = create(withSampleName(validDraftBody(), "填报时间筛选正式样本点"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                UPDATE production.production_record
                SET created_at=TIMESTAMPTZ '2026-08-05 09:00:00+08',
                    reported_at=TIMESTAMPTZ '2030-01-01 09:00:00+08'
                WHERE record_id=:id
                """).param("id", id).update();

        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026").queryParam("filter.surveyMonth", "8")
                        .queryParam("filter.fillingDateFrom", "2026-08-05")
                        .queryParam("filter.fillingDateTo", "2026-08-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        jdbc.sql("""
                UPDATE production.production_record
                SET survey_month=NULL,survey_period_precision='YEAR',
                    survey_period_governance_state='CONFIRMED'
                WHERE record_id=:id
                """).param("id", id).update();
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026").queryParam("filter.surveyMonth", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT submitted_at IS NOT NULL FROM production.production_record WHERE record_id=:id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        jdbc.sql("""
                UPDATE production.production_record
                SET submitted_at=TIMESTAMPTZ '2026-08-06 10:30:00+08'
                WHERE record_id=:id
                """).param("id", id).update();
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.fillingDateFrom", "2026-08-06")
                        .queryParam("filter.fillingDateTo", "2026-08-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].values.PROD_FILLING_TIME_BASIS").value("SUBMITTED_AT"));
    }

    @Test
    void savesYearOnlyDataTimeAndKeepsSystemFillingDateImmutableAcrossDetailAndEdit() throws Exception {
        String body = validDraftBody()
                .replace("\"surveyDate\":\"2026-08-01\"",
                        "\"surveyYear\":2026,\"surveyMonth\":null,"
                                + "\"reportedAt\":\"1999-01-01T00:00:00Z\"");
        String id = mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.surveyYear").value(2026))
                .andExpect(jsonPath("$.data.surveyMonth").doesNotExist())
                .andExpect(jsonPath("$.data.fillingDate").isNotEmpty())
                .andExpect(jsonPath("$.data.reportedAt").value(org.hamcrest.Matchers.not(
                        "1999-01-01T00:00:00Z")))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        JdbcClient jdbc = JdbcClient.create(dataSource);
        Map<String, Object> stored = jdbc.sql("""
                SELECT survey_year,survey_month,survey_period_precision,survey_date
                FROM production.production_record WHERE record_id=:id
                """).param("id", id).query().singleRow();
        assertThat(stored.get("survey_year")).isEqualTo(2026);
        assertThat(stored.get("survey_month")).isNull();
        assertThat(stored.get("survey_period_precision")).isEqualTo("YEAR");
        assertThat(stored.get("survey_date").toString()).isEqualTo("2026-01-01");

        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.surveyYear").value(2026))
                .andExpect(jsonPath("$.data.surveyMonth").doesNotExist())
                .andExpect(jsonPath("$.data.fillingDate").isNotEmpty());

        mockMvc.perform(put("/api/v1/production-records/{id}", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"surveyMonth\":null", "\"surveyMonth\":7")
                                .replace("\"reportedAt\":\"1999-01-01T00:00:00Z\"",
                                        "\"reportedAt\":\"1998-01-01T00:00:00Z\"")
                                .replace("\"subsidies\":{}", "\"subsidies\":{},\"version\":0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.surveyYear").value(2026))
                .andExpect(jsonPath("$.data.surveyMonth").value(7))
                .andExpect(jsonPath("$.data.reportedAt").value(org.hamcrest.Matchers.not(
                        "1998-01-01T00:00:00Z")));
    }

    private String create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.reportedAt").exists())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void approve(String id) throws Exception {
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
    }

    private void expectDefinitionFacts(String product, String objectType, String qualityCode,
            boolean supportsAllCategories) throws Exception {
        var result = mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", product).queryParam("objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.contractVersion").value("production-survey-fields-v4"))
                .andExpect(jsonPath("$.data.contractDigest").value(
                        "sha256:07806fbda70354ee29b243020cd5508db52271f8d7c88ac540379a7c1c3297fe"))
                .andExpect(jsonPath("$.data.groups[0].category").value("DETAIL"))
                .andExpect(jsonPath("$.data.groups[0].fields[*].code").value(hasItem("PROD_SAMPLE_NAME")))
                .andExpect(jsonPath("$.data.groups[1].fields[*].code").value(hasItem(qualityCode)));
        if (supportsAllCategories) {
            result.andExpect(jsonPath("$.data.groups[2].fields[*].code").value(hasItem("LAND_RENT")))
                    .andExpect(jsonPath("$.data.groups[3].fields[*].code").value(hasItem("INSURANCE_AMOUNT")))
                    .andExpect(jsonPath("$.data.groups[4].fields[*].code").value(hasItem("SUBSIDY_AMOUNT")));
        } else {
            result.andExpect(jsonPath("$.data.groups[2].fields").isEmpty())
                    .andExpect(jsonPath("$.data.groups[3].fields").isEmpty())
                    .andExpect(jsonPath("$.data.groups[4].fields").isEmpty());
        }
    }

    private void expectFullFactDetail(String id, String product, String objectType, String qualityCode)
            throws Exception {
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.cultivatedAreaMu").value("1.2345"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SURVEYOR_PHONE").value("13800000000"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private void expectFullFactList(String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private void expectApprovedFullFactDetail(
            String id, String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private void expectApprovedFullFactList(
            String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].values.PROD_STATUS").value("已审核"))
                .andExpect(jsonPath("$.data.items[0].allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"))
                .andExpect(jsonPath("$.data.items[0].values." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].values.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.items[0].values.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private long actorBusinessRowCount() {
        return JdbcClient.create(dataSource).sql("""
                SELECT
                    (SELECT count(*) FROM production.production_record
                     WHERE last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_quality fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_cost fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_insurance fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_subsidy fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                """).query(Long.class).single();
    }

    private static String validDraftBody() {
        return """
                {"productCode":"SOYBEAN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"100","yieldPerMuKilograms":"180",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},%s}
                """.formatted(submissionMetadataProperty());
    }

    private static String fullDraftBody(String product, String objectType, String qualityCode, Long version) {
        String versionProperty = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","objectTypeCode":"%s","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1.2345","yieldPerMuKilograms":"2.3456",
                 "quality":{"%s":"3"},"costs":{"LAND_RENT":"4"},
                 "insurance":{"INSURANCE_AMOUNT":"5"},"subsidies":{"SUBSIDY_AMOUNT":"6"},%s%s}
                """.formatted(product, objectType, qualityCode,
                        withSampleName(submissionMetadataProperty(), product + "-" + objectType + "正式样本点"),
                        versionProperty);
    }

    private static String qualityOnlyDraftBody(String product, String objectType, String qualityCode) {
        return """
                {"productCode":"%s","objectTypeCode":"%s","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"2",
                 "quality":{"%s":"3"},"costs":{},"insurance":{},"subsidies":{},%s}
                """.formatted(product, objectType, qualityCode, submissionMetadataProperty());
    }

    private static String unsupportedTechStationDraftBody(
            String product, String qualityCode, String category, String factCode) {
        String costs = category.equals("COST") ? "{\"%s\":\"4\"}".formatted(factCode) : "{}";
        String insurance = category.equals("INSURANCE") ? "{\"%s\":\"5\"}".formatted(factCode) : "{}";
        String subsidies = category.equals("SUBSIDY") ? "{\"%s\":\"6\"}".formatted(factCode) : "{}";
        return """
                {"productCode":"%s","objectTypeCode":"AGRICULTURAL_TECH_STATION","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"2",
                 "quality":{"%s":"3"},"costs":%s,"insurance":%s,"subsidies":%s,%s}
                """.formatted(product, qualityCode, costs, insurance, subsidies, submissionMetadataProperty());
    }

    private static String submissionMetadataProperty() {
        return """
                 "submissionMetadata":{"PROD_REPORTER_NAME":"测试填报员","PROD_SURVEYOR_NAME":"王雷",
                   "PROD_SURVEYOR_PHONE":"13800000000",
                 "PROD_SAMPLE_CONTACT":"13900000000","PROD_SAMPLE_LATITUDE":"47.3543",
                 "PROD_SAMPLE_LONGITUDE":"123.9182"},"evidencePhotoIds":["%s"]
                """.formatted(EVIDENCE_PHOTO_ID).strip();
    }

    private static String withSampleName(String body, String sampleName) {
        if (body.contains("\"PROD_SAMPLE_NAME\"")) {
            return body.replaceFirst("\"PROD_SAMPLE_NAME\":\"[^\"]*\"",
                    "\"PROD_SAMPLE_NAME\":\"" + sampleName + "\"");
        }
        return body.replace("\"PROD_SAMPLE_CONTACT\"",
                "\"PROD_SAMPLE_NAME\":\"" + sampleName + "\",\"PROD_SAMPLE_CONTACT\"");
    }

    private static List<Arguments> farmerAndVillageContexts() {
        return productQualityContexts().stream().flatMap(context -> List.of(
                Arguments.of(context.get()[0], context.get()[1], "FARMER"),
                Arguments.of(context.get()[0], context.get()[1], "VILLAGE_COMMITTEE")).stream()).toList();
    }

    private static List<Arguments> productQualityContexts() {
        return List.of(
                Arguments.of("CORN", "MOISTURE"),
                Arguments.of("SOYBEAN", "PROTEIN"),
                Arguments.of("RICE", "MILLING_YIELD"));
    }

    private static List<Arguments> agriculturalTechStationRejectedFacts() {
        return productQualityContexts().stream().flatMap(context -> List.of(
                Arguments.of(context.get()[0], context.get()[1], "COST", "LAND_RENT"),
                Arguments.of(context.get()[0], context.get()[1], "INSURANCE", "INSURANCE_AMOUNT"),
                Arguments.of(context.get()[0], context.get()[1], "SUBSIDY", "SUBSIDY_AMOUNT")).stream()).toList();
    }
}
