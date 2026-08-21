package com.cofco.qiqihar.graintrade.samplepoint.identity.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityGovernanceWorkbook;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityGovernanceWorkbook.Row;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class SampleIdentityMergeRestIntegrationTest {
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final UUID POINT_A = UUID.fromString("95500000-0000-0000-0000-000000000001");
    private static final UUID POINT_B = UUID.fromString("95500000-0000-0000-0000-000000000002");
    private static final String RECORD_A = "95500000-0000-0000-0000-000000000101";
    private static final String RECORD_B = "95500000-0000-0000-0000-000000000102";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;
    private JdbcClient jdbc;

    @AfterEach
    void removeIdentityMergeFixtures() {
        jdbc.sql("""
                TRUNCATE registry.sample_subject_resolution_audit,
                  registry.sample_subject_resolution_revision,registry.sample_subject_resolution_item,
                  registry.sample_subject_resolution_batch,platform.business_audit_event,
                  production.production_record,market.market_record,registry.sample_point
                  RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                DELETE FROM platform.security_user_role
                WHERE subject_id='wang-yang' AND role_code='ACCOUNT_OWNER'
                """).update();
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE registry.sample_subject_resolution_audit,
                  registry.sample_subject_resolution_revision,registry.sample_subject_resolution_item,
                  registry.sample_subject_resolution_batch,platform.business_audit_event,
                  production.production_record,market.market_record RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:a,:b)")
                .param("a", POINT_A).param("b", POINT_B).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230208',ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)),
                  '身份归并测试边界','urn:identity-merge-boundary','identity-merge-1','内部测试数据',
                  '230208',DATE '2026-08-20',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)))),'hex'))
                ON CONFLICT(region_code) DO NOTHING
                """).update();
        seedPoint(POINT_A, "2024-01-01");
        seedPoint(POINT_B, "2025-01-01");
        seedRecord(RECORD_A, POINT_A, 100);
        seedRecord(RECORD_B, POINT_B, 200);
        seedUniqueOwner();
    }

    @Test
    void exportsUploadsReviewsAndAppliesAnAppendOnlyHistoricalMergeWithoutChangingFacts()
            throws Exception {
        var export = mvc.perform(get("/api/v1/sample-point-identities/merge-export")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists())
                .andReturn().getResponse();
        UUID batchId = UUID.fromString(export.getHeader("X-Export-Batch-Id"));
        var parsed = SampleIdentityGovernanceWorkbook.read(export.getContentAsByteArray());
        assertThat(parsed.batchId()).isEqualTo(batchId);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows()).extracting(Row::targetSamplePointId)
                .containsOnly(POINT_A);

        List<Row> reviewedRows = parsed.rows().stream().map(row ->
                row.currentSamplePointId().equals(POINT_B)
                        ? new Row(row.sourceRecordId(), row.sourceVersion(), row.sourceDomain(),
                                row.productCode(), row.surveyPeriod(), row.currentSamplePointId(),
                                row.sampleName(), row.sampleContact(), row.regionCode(), row.regionName(),
                                row.longitude(), row.latitude(), row.approvedRecordCount(),
                                row.duplicateIdentityGroup(), row.rowBinding(),
                                SampleIdentityGovernanceWorkbook.MERGE, POINT_A,
                                "姓名、联系方式、地区和坐标均一致，确认为同一真实样本点", "")
                        : row).toList();
        byte[] upload = SampleIdentityGovernanceWorkbook.create(batchId, reviewedRows);
        String jobBody = mvc.perform(multipart("/api/v1/sample-point-identities/merge-jobs")
                        .file(new MockMultipartFile("file", "历史身份归并.xlsx", XLSX, upload))
                        .header("Idempotency-Key", "identity-merge-job-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pendingRequests").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode job = objectMapper.readTree(jobBody).get("data");
        assertThat(job.get("acceptedRows").asInt()).isEqualTo(1);

        String queueBody = mvc.perform(get("/api/v1/sample-point-identities/merge-requests")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(queueBody).get("data").get(0)
                .get("requestId").asText();

        mvc.perform(post("/api/v1/sample-point-identities/merge-requests/{requestId}/review", requestId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"独立复核原记录与样本点主数据一致"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("APPLIED"));
        mvc.perform(post("/api/v1/sample-point-identities/merge-requests/{requestId}/review", requestId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"独立复核原记录与样本点主数据一致"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("APPLIED"));

        assertThat(jdbc.sql("""
                SELECT target_sample_point_id FROM registry.current_sample_subject_resolution
                WHERE source_domain='PRODUCTION' AND source_record_id=:record
                """).param("record", RECORD_B).query(UUID.class).single()).isEqualTo(POINT_A);
        assertThat(jdbc.sql("""
                SELECT cultivated_area_mu FROM production.production_record WHERE record_id=:record
                """).param("record", RECORD_B).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("200");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE record_id IN (:a,:b)
                """).param("a", RECORD_A).param("b", RECORD_B)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_subject_resolution_revision
                WHERE source_record_id=:record
                """).param("record", RECORD_B).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='SAMPLE_IDENTITY_MERGE_REQUEST'
                  AND aggregate_id=:request
                  AND action_code='SAMPLE_IDENTITY_MERGE_APPROVAL_AUTHORIZED'
                  AND actor_subject_id='market-tester'
                """).param("request", requestId).query(Long.class).single()).isOne();
    }

    @Test
    void forbidsOrdinarySelfReviewButAllowsTheAuditedUniqueOwnerException() throws Exception {
        UUID ordinaryRequest = submitMerge(
                "production-tester", "market-tester", "identity-merge-ordinary-self");
        mvc.perform(post("/api/v1/sample-point-identities/merge-requests/{requestId}/review",
                        ordinaryRequest)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"提交人尝试审核本人申请"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("SAMPLE_IDENTITY_MERGE_SELF_REVIEW_FORBIDDEN"));

        setUp();
        UUID ownerRequest = submitMerge("wang-yang", "wang-yang", "identity-merge-owner-self");
        mvc.perform(post("/api/v1/sample-point-identities/merge-requests/{requestId}/review",
                        ownerRequest)
                        .principal(() -> "wang-yang")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"平台唯一所有者复核身份依据并特权自审"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("APPLIED"))
                .andExpect(jsonPath("$.data.privilegedSelfReview").value(true));
    }

    @Test
    void rejectsAStaleExportAndKeepsTheOriginalIdentityResolutionEmpty() throws Exception {
        UUID requestId = submitMerge(
                "production-tester", "market-tester", "identity-merge-stale");
        jdbc.sql("UPDATE production.production_record SET version=version+1 WHERE record_id=:record")
                .param("record", RECORD_B).update();

        mvc.perform(post("/api/v1/sample-point-identities/merge-requests/{requestId}/review",
                        requestId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"复核时记录版本已经变化"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_IDENTITY_MERGE_STALE"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.current_sample_subject_resolution
                WHERE source_domain='PRODUCTION' AND source_record_id=:record
                """).param("record", RECORD_B).query(Long.class).single()).isZero();
    }

    private UUID submitMerge(String submitter, String queueReader, String idempotencyKey)
            throws Exception {
        var export = mvc.perform(get("/api/v1/sample-point-identities/merge-export")
                        .principal(() -> submitter))
                .andExpect(status().isOk()).andReturn().getResponse();
        var parsed = SampleIdentityGovernanceWorkbook.read(export.getContentAsByteArray());
        List<Row> reviewedRows = parsed.rows().stream().map(row ->
                row.currentSamplePointId().equals(POINT_B)
                        ? new Row(row.sourceRecordId(), row.sourceVersion(), row.sourceDomain(),
                                row.productCode(), row.surveyPeriod(), row.currentSamplePointId(),
                                row.sampleName(), row.sampleContact(), row.regionCode(), row.regionName(),
                                row.longitude(), row.latitude(), row.approvedRecordCount(),
                                row.duplicateIdentityGroup(), row.rowBinding(),
                                SampleIdentityGovernanceWorkbook.MERGE, POINT_A,
                                "姓名、联系方式、地区和坐标均一致，确认为同一真实样本点", "")
                        : row).toList();
        byte[] upload = SampleIdentityGovernanceWorkbook.create(parsed.batchId(), reviewedRows);
        mvc.perform(multipart("/api/v1/sample-point-identities/merge-jobs")
                        .file(new MockMultipartFile("file", "历史身份归并.xlsx", XLSX, upload))
                        .header("Idempotency-Key", idempotencyKey)
                        .principal(() -> submitter))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pendingRequests").value(1));
        String queueBody = mvc.perform(get("/api/v1/sample-point-identities/merge-requests")
                        .principal(() -> queueReader))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(queueBody).get("data").get(0)
                .get("requestId").asText());
    }

    private void seedPoint(UUID id, String effectiveFrom) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE','历史重复身份样本','230208','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.800000,47.550000),4326),CAST(:effective AS date),0,
                  'production-tester','production-tester')
                """).param("id", id).param("effective", effectiveFrom).update();
    }

    private void seedRecord(String id, UUID point, int area) {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,sample_point_id,last_modified_by)
                VALUES(:id,'CORN','FARMER','230208',DATE '2026-08-01',now(),
                  :area,500,'APPROVED',:point,'production-tester')
                """).param("id", id).param("area", area).param("point", point).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_NAME','历史重复身份样本'),
                      (:id,'PROD_SAMPLE_CONTACT','13900000000')
                """).param("id", id).update();
    }

    private void seedUniqueOwner() {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('wang-yang','吴雨桐','TEST')
                ON CONFLICT(subject_id) DO UPDATE SET display_name=excluded.display_name,
                  work_unit_code=excluded.work_unit_code,enabled=true
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES('wang-yang','SYSTEM_ADMIN'),('wang-yang','ACCOUNT_OWNER')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                SELECT 'wang-yang',region_code FROM platform.work_unit_region_scope
                WHERE work_unit_code='TEST'
                ON CONFLICT DO NOTHING
                """).update();
    }
}
