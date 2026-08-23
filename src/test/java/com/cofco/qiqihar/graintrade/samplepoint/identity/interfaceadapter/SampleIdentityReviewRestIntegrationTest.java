package com.cofco.qiqihar.graintrade.samplepoint.identity.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
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

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class SampleIdentityReviewRestIntegrationTest {
    private static final UUID TARGET = UUID.fromString("95300000-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private UUID draftId;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        clearIdentityFixtures();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230208',ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)),
                  '身份治理测试边界','urn:identity-review-boundary','identity-review-1','内部测试数据',
                  '230208',DATE '2026-08-20',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)))),'hex'))
                ON CONFLICT(region_code) DO NOTHING
                """).update();
        seedCandidate();
        seedAccountOwner();
        draftId = seedPendingDraft("production-tester");
    }

    @AfterEach
    void tearDown() {
        clearIdentityFixtures();
    }

    private void clearIdentityFixtures() {
        jdbc.sql("""
                TRUNCATE platform.business_import_draft_evidence,platform.import_row_result,
                  platform.business_import_draft,platform.import_job_photo,platform.import_job,
                  platform.business_audit_event,production.production_record,market.market_record,
                  logistics.route_event,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE sample_point_id=:point
                   OR (region_code='230208'
                       AND canonical_name IN ('身份核验样本','同址另一经营主体','同址另一市场主体')
                       AND created_by IN ('production-tester','market-tester'))
                """).param("point", TARGET).update();
    }

    @Test
    void listsCandidatesAndAnIndependentReviewerLinksThenPromotesTheSameDraft() throws Exception {
        mvc.perform(get("/api/v1/sample-point-identities/reviews")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].draftId").value(draftId.toString()))
                .andExpect(jsonPath("$.data[0].candidates[0].samplePointId").value(TARGET.toString()));

        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"LINK_EXISTING","targetSamplePointId":"%s",
                                 "expectedVersion":0,"reason":"联系方式变更，位置及主体证据一致"}
                                """.formatted(TARGET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("LINK_EXISTING"))
                .andExpect(jsonPath("$.data.stateCode").value("PROMOTED"))
                .andExpect(jsonPath("$.data.canonicalRecordId").isNotEmpty());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE import_draft_id=:id AND state_code='PROMOTED' AND version=1
                """).param("id", draftId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE status_code='PENDING_REVIEW'
                """).query(Long.class).single()).isOne();
        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft
                WHERE import_draft_id=:id
                """).param("id", draftId).query(String.class).single();

        mvc.perform(post("/api/v1/production-records/{id}/approve", recordId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT sample_point_id FROM production.production_record WHERE record_id=:record
                """).param("record", recordId).query(UUID.class).single()).isEqualTo(TARGET);
    }

    @Test
    void linkOrDistinctIdentityDecisionCannotCreateASecondCurrentRecordForTheSamePeriod()
            throws Exception {
        UUID distinctDraft = seedPendingDraft("production-tester");
        String existing = "95300000-0000-0000-0000-000000000202";
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:record,'CORN','FARMER','230208',DATE '2026-08-01',now(),
                  100,500,'PENDING_REVIEW','production-tester',2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("record", existing).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_NAME','身份核验样本'),
                      (:record,'PROD_SAMPLE_CONTACT','13900000000')
                """).param("record", existing).update();

        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"LINK_EXISTING","targetSamplePointId":"%s",
                                 "expectedVersion":0,"reason":"不得绕过同期间记录守卫"}
                                """.formatted(TARGET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_PERIOD_RECORD_CONFLICT"));
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", distinctDraft)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":0,
                                 "reason":"身份结论不能形成同业务键第三条记录"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_PERIOD_RECORD_CONFLICT"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE survey_year=2026 AND survey_month=8
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE import_draft_id IN (:linkDraft,:distinctDraft) AND state_code='DRAFT'
                """).param("linkDraft", draftId).param("distinctDraft", distinctDraft)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsOrdinarySelfReview() throws Exception {
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":0,
                                 "reason":"确认是同名但不同对象"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_IDENTITY_SELF_REVIEW_FORBIDDEN"));

        assertThat(jdbc.sql("""
                SELECT state_code FROM platform.business_import_draft WHERE import_draft_id=:id
                """).param("id", draftId).query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void accountOwnerMayReviewTheirOwnPendingIdentityWithAnAuditFlag() throws Exception {
        UUID ownedDraft = seedPendingDraft("wang-yang");

        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", ownedDraft)
                        .principal(() -> "wang-yang")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"LINK_EXISTING","targetSamplePointId":"%s",
                                 "expectedVersion":0,"reason":"平台唯一所有者核对原始材料后确认关联"}
                                """.formatted(TARGET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateCode").value("PROMOTED"))
                .andExpect(jsonPath("$.data.privilegedSelfReview").value(true));
    }

    @Test
    void returnDecisionIsIdempotentAndLeavesTheDraftUnpromoted() throws Exception {
        String request = """
                {"decision":"RETURN_FOR_CORRECTION","expectedVersion":0,
                 "reason":"联系方式证明不足，请补充后重新提交"}
                """;
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateCode").value("DRAFT"));
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("RETURN_FOR_CORRECTION"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='SAMPLE_IDENTITY_REVIEW'
                  AND aggregate_id=:id
                  AND action_code='SAMPLE_IDENTITY_RETURN_FOR_CORRECTION'
                """).param("id", draftId.toString()).query(Long.class).single()).isOne();
    }

    @Test
    void rejectsStaleVersionAndMissingReasonWithoutWritingADecision() throws Exception {
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":9,
                                 "reason":"已核验"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_IDENTITY_REVIEW_STALE"));
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", draftId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":0,"reason":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SAMPLE_IDENTITY_DECISION"));
    }

    @Test
    void reviewedMarketLinkAlsoReusesTheSelectedStableSamplePointAtFinalApproval() throws Exception {
        UUID marketDraft = seedPendingMarketDraft("market-tester");
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", marketDraft)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"LINK_EXISTING","targetSamplePointId":"%s",
                                 "expectedVersion":0,"reason":"跨期市场记录与既有样本位置一致"}
                                """.formatted(TARGET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateCode").value("PROMOTED"));
        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft
                WHERE import_draft_id=:id
                """).param("id", marketDraft).query(String.class).single();

        mvc.perform(post("/api/v1/market-records/{id}/approve", recordId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT sample_point_id FROM market.market_record WHERE record_id=:record
                """).param("record", recordId).query(UUID.class).single()).isEqualTo(TARGET);
    }

    @Test
    void reviewedDistinctIdentityMayShareTheBoundCoordinateAndMarksEveryOccupantVerified()
            throws Exception {
        UUID sharedDraft = seedPendingDraft("production-tester", "同址另一经营主体");

        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", sharedDraft)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":0,
                                 "reason":"营业主体、联系电话和现场门牌证据均不同，确认合法共址"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateCode").value("PROMOTED"));

        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft
                WHERE import_draft_id=:id
                """).param("id", sharedDraft).query(String.class).single();
        assertThat(jdbc.sql("""
                SELECT coalesce((detail->>'coordinateShared')::boolean,false)
                FROM platform.business_audit_event
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:record
                  AND action_code='SAMPLE_IDENTITY_CONFIRM_DISTINCT'
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("record", recordId).query(Boolean.class).single()).isTrue();

        mvc.perform(post("/api/v1/production-records/{id}/approve", recordId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());

        UUID createdPoint = jdbc.sql("""
                SELECT sample_point_id FROM production.production_record WHERE record_id=:record
                """).param("record", recordId).query(UUID.class).single();
        assertThat(createdPoint).isNotEqualTo(TARGET);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE sample_point_id IN (:existing,:created)
                  AND coordinate_shared_verified=true
                  AND ST_Equals(governed_point,ST_SetSRID(ST_MakePoint(123.8,47.55),4326))
                """).param("existing", TARGET).param("created", createdPoint)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT version FROM registry.sample_point WHERE sample_point_id=:existing
                """).param("existing", TARGET).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void reviewedDistinctMarketIdentityUsesTheSameBoundColocationRule() throws Exception {
        UUID sharedDraft = seedPendingMarketDraft("market-tester", "同址另一市场主体");
        mvc.perform(post("/api/v1/sample-point-identities/reviews/{draftId}/decisions", sharedDraft)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRM_DISTINCT","expectedVersion":0,
                                 "reason":"市场主体证照和联系电话不同，现场核验确认合法共址"}
                                """))
                .andExpect(status().isOk());
        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft
                WHERE import_draft_id=:id
                """).param("id", sharedDraft).query(String.class).single();

        mvc.perform(post("/api/v1/market-records/{id}/approve", recordId)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());

        UUID createdPoint = jdbc.sql("""
                SELECT sample_point_id FROM market.market_record WHERE record_id=:record
                """).param("record", recordId).query(UUID.class).single();
        assertThat(createdPoint).isNotEqualTo(TARGET);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE sample_point_id IN (:existing,:created)
                  AND coordinate_shared_verified=true
                """).param("existing", TARGET).param("created", createdPoint)
                .query(Long.class).single()).isEqualTo(2);
    }

    private void seedCandidate() {
        String recordId = "95300000-0000-0000-0000-000000000101";
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:point,'SURVEY_SITE','身份核验样本','230208','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.800000,47.550000),4326),DATE '2024-01-01',0,
                  'production-tester','production-tester')
                """).param("point", TARGET).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,sample_point_id,last_modified_by)
                VALUES(:record,'CORN','FARMER','230208',DATE '2024-08-01',now(),
                  10,20,'APPROVED',:point,'production-tester')
                """).param("record", recordId).param("point", TARGET).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_NAME','身份核验样本'),
                      (:record,'PROD_SAMPLE_CONTACT','13800000000')
                """).param("record", recordId).update();
    }

    private UUID seedPendingDraft(String creator) {
        return seedPendingDraft(creator, "身份核验样本");
    }

    private UUID seedPendingDraft(String creator, String sampleName) {
        UUID jobId = UUID.randomUUID();
        UUID pendingDraft = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,completed_at)
                VALUES(:job,'PRODUCTION',:key,repeat('a',64),'identity-review-test',:creator,'TEST',
                  'COMPLETED',now(),now())
                """).param("job", jobId).param("key", "identity-review-" + jobId)
                .param("creator", creator).update();
        jdbc.sql("""
                INSERT INTO platform.business_import_draft(
                  import_draft_id,domain_code,product_code,object_type_code,sample_name,region_code,
                  survey_period,values_json,missing_fields_json,completeness_percent,state_code,
                  created_by,import_job_id,source_row_number,version,created_at,updated_at)
                VALUES(:draft,'PRODUCTION','CORN','FARMER',:sampleName,'230208','2026-08',
                  CAST(:values AS jsonb),'[]',100,'DRAFT',:creator,:job,2,0,now(),now())
                """).param("draft", pendingDraft).param("sampleName", sampleName)
                .param("creator", creator).param("job", jobId)
                .param("values", """
                        {"surveyYear":"2026","surveyMonth":"8","cultivatedAreaMu":"100",
                         "yieldPerMuKilograms":"500","PROD_SURVEYOR_NAME":"王雷",
                         "PROD_SURVEYOR_PHONE":"13800000000","PROD_SAMPLE_CONTACT":"13900000000",
                         "PROD_SAMPLE_LATITUDE":"47.550000","PROD_SAMPLE_LONGITUDE":"123.800000"}
                        """).update();
        jdbc.sql("""
                INSERT INTO platform.business_audit_event(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,occurred_at,detail)
                VALUES(gen_random_uuid(),'SAMPLE_IDENTITY_REVIEW',CAST(:draft AS text),
                  'SAMPLE_IDENTITY_REVIEW_SUBMITTED',:creator,'TEST',now(),
                  '{"reasonCode":"SAMPLE_IDENTITY_CONTACT_CONFLICT",
                    "reasonMessage":"联系方式变化，需核验"}')
                """).param("draft", pendingDraft).param("creator", creator).update();
        return pendingDraft;
    }

    private UUID seedPendingMarketDraft(String creator) {
        return seedPendingMarketDraft(creator, "身份核验样本");
    }

    private UUID seedPendingMarketDraft(String creator, String sampleName) {
        UUID jobId = UUID.randomUUID();
        UUID pendingDraft = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,completed_at)
                VALUES(:job,'MARKET',:key,repeat('b',64),'identity-review-market-test',:creator,'TEST',
                  'COMPLETED',now(),now())
                """).param("job", jobId).param("key", "identity-review-market-" + jobId)
                .param("creator", creator).update();
        jdbc.sql("""
                INSERT INTO platform.business_import_draft(
                  import_draft_id,domain_code,product_code,object_type_code,sample_name,region_code,
                  survey_period,values_json,missing_fields_json,completeness_percent,state_code,
                  created_by,import_job_id,source_row_number,version,created_at,updated_at)
                VALUES(:draft,'MARKET','CORN','TRADER',:sampleName,'230208','2026-08',
                  CAST(:values AS jsonb),'[]',100,'DRAFT',:creator,:job,2,0,now(),now())
                """).param("draft", pendingDraft).param("sampleName", sampleName)
                .param("creator", creator).param("job", jobId)
                .param("values", """
                        {"surveyYear":"2026","surveyMonth":"8","MKT_SURVEYOR_NAME":"王雷",
                         "MKT_SURVEYOR_PHONE":"13800000000","MKT_SAMPLE_CONTACT":"13900000000",
                         "MKT_SAMPLE_LATITUDE":"47.550000","MKT_SAMPLE_LONGITUDE":"123.800000",
                         "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2380",
                         "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_FREIGHT_AMOUNT":"72",
                         "MKT_PACKAGING_FORM":"BULK"}
                        """).update();
        jdbc.sql("""
                INSERT INTO platform.business_audit_event(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,occurred_at,detail)
                VALUES(gen_random_uuid(),'SAMPLE_IDENTITY_REVIEW',CAST(:draft AS text),
                  'SAMPLE_IDENTITY_REVIEW_SUBMITTED',:creator,'TEST',now(),
                  '{"reasonCode":"SAMPLE_IDENTITY_CONTACT_CONFLICT",
                    "reasonMessage":"联系方式变化，需核验"}')
                """).param("draft", pendingDraft).param("creator", creator).update();
        return pendingDraft;
    }

    private void seedAccountOwner() {
        jdbc.sql("""
                INSERT INTO platform.security_user(
                  subject_id,display_name,work_unit_code,enabled,employee_number)
                VALUES('wang-yang','吴雨桐','TEST',true,'ACCOUNT-OWNER-TEST')
                ON CONFLICT(subject_id) DO UPDATE SET
                  display_name=EXCLUDED.display_name,work_unit_code=EXCLUDED.work_unit_code,
                  enabled=true,employee_number=EXCLUDED.employee_number
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES('wang-yang','SYSTEM_ADMIN'),('wang-yang','ACCOUNT_OWNER')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('wang-yang','230208') ON CONFLICT DO NOTHING
                """).update();
    }
}
