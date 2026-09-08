package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class FormalSamplePointWriteRestIntegrationTest {
    private static final String ADMIN = "production-tester";
    private static final String RESTRICTED = "formal-sample-manage-restricted";
    private static final UUID OCCUPIED_POINT_ID =
            UUID.fromString("fa120000-0000-0000-0000-000000000001");
    private static final UUID LOGISTICS_POINT_ID =
            UUID.fromString("fa120000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired BusinessNotificationRepository notifications;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.business_event_outbox,platform.business_audit_event,
                  registry.sample_network_year,registry.sample_point CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'正式样本维护受限用户','TEST',true)
                ON CONFLICT(subject_id) DO UPDATE SET enabled=true
                """).param("subject", RESTRICTED).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'BUSINESS_OPERATOR')
                """).param("subject", RESTRICTED).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:subject,'230202')
                """).param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE','坐标占用样本','230202','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.93,47.30),4326),DATE '2026-01-01',:actor,:actor)
                """).param("id", OCCUPIED_POINT_ID).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:id,'LOGISTICS_NODE','历史物流正式样本','230202','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.95,47.32),4326),DATE '2026-01-01',:actor,:actor)
                """).param("id", LOGISTICS_POINT_ID).param("actor", ADMIN).update();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_formal_sample_audit_for_test "
                + "ON platform.business_audit_event").update();
        jdbc.sql("DROP FUNCTION IF EXISTS platform.reject_formal_sample_audit_for_test()")
                .update();
    }

    @Test
    void previewsTheAuthoritativeCollectionAndRetiresItOnce() throws Exception {
        batchSource();
        MvcResult preview = mvc.perform(post("/api/v1/formal-sample-points/retirement-previews")
                        .principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1)).andReturn();
        String previewId = json.readTree(preview.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String body = "{\"reason\":\"停止经营\"}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", previewId)
                            .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.retiredCount").value(1));
        }
        mvc.perform(get("/api/v1/formal-sample-points/retirement-previews/{id}", previewId)
                        .principal(() -> ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retiredCount").value(1));
        mvc.perform(get("/api/v1/formal-sample-points/{id}", OCCUPIED_POINT_ID).principal(() -> ADMIN))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", LOGISTICS_POINT_ID).principal(() -> ADMIN))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point point
                JOIN platform.business_audit_event event ON event.aggregate_id=point.sample_point_id::text
                WHERE point.sample_point_id=:id AND event.action_code='FORMAL_SAMPLE_POINT_RETIRED'
                  AND (event.detail->>'retirementYear')::integer=
                    EXTRACT(YEAR FROM point.retired_at AT TIME ZONE 'Asia/Shanghai')::integer
                  AND point.effective_to=(point.retired_at AT TIME ZONE 'Asia/Shanghai')::date
                """).param("id", OCCUPIED_POINT_ID).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record WHERE record_id='batch-source'")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_event_outbox WHERE action_code='FORMAL_SAMPLE_POINT_RETIRED'")
                .query(Long.class).single()).isOne();
    }

    private void batchSource() {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES('batch-source','CORN','FARMER','230202',CURRENT_DATE,CURRENT_TIMESTAMP,
                  320,500,'APPROVED',:actor,2026,'YEAR','CONFIRMED',:id)
                """).param("actor", ADMIN).param("id", OCCUPIED_POINT_ID).update();
    }

    private String batchPreview() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/formal-sample-points/retirement-previews")
                        .principal(() -> ADMIN)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("data").path("id").asText();
    }

    @Test
    void includesMoreThanOnePageAndDeduplicatesProductsBySampleIdentity() throws Exception {
        batchSource();
        jdbc.sql("""
                INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,
                  region_code,approval_state,location_state,governed_point,effective_from,created_by,updated_by)
                SELECT md5('batch-page-'||n)::uuid,'SURVEY_SITE','批量样本'||n,
                  '230202','APPROVED','VALID',ST_SetSRID(ST_MakePoint(123.94+n*0.0001,47.31),4326),
                  DATE '2026-01-01',:actor,:actor FROM generate_series(1,25) n
                """).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                SELECT 'batch-page-'||n, 'SOYBEAN','FARMER','230202',CURRENT_DATE,CURRENT_TIMESTAMP,
                  320,500,'APPROVED',:actor,2026,'YEAR','CONFIRMED',md5('batch-page-'||n)::uuid
                FROM generate_series(1,25) n
                UNION ALL SELECT 'batch-second-product','SOYBEAN','FARMER','230202',CURRENT_DATE,CURRENT_TIMESTAMP,
                  320,500,'APPROVED',:actor,2026,'YEAR','CONFIRMED',:id
                """).param("actor", ADMIN).param("id", OCCUPIED_POINT_ID).update();
        String preview = batchPreview();
        mvc.perform(get("/api/v1/formal-sample-points/retirement-previews/{id}", preview).principal(() -> ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateCount").value(26));
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retiredCount").value(26));
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_event_outbox WHERE action_code='FORMAL_SAMPLE_POINT_RETIRED'")
                .query(Long.class).single()).isEqualTo(26);
    }

    @Test
    void concurrentConfirmationReturnsTheSameDurableReceipt() throws Exception {
        batchSource();
        String preview = batchPreview();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.countDown();
                start.await();
                return mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                                .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"停止经营\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(notifications.findVisible(new AuthorizedReadScope(ADMIN, Set.of("230202")), ADMIN, 100))
                .filteredOn(event -> event.actionCode().equals("FORMAL_SAMPLE_POINT_RETIRED"))
                .hasSize(1);
        assertThat(notifications.findVisible(new AuthorizedReadScope(RESTRICTED, Set.of("230203")), RESTRICTED, 100))
                .noneMatch(event -> event.actionCode().equals("FORMAL_SAMPLE_POINT_RETIRED"));
    }

    @Test
    void rejectsChangedVersionsAndExpiredPreviewsWithoutRetiringAnything() throws Exception {
        batchSource();
        String preview = batchPreview();
        jdbc.sql("UPDATE registry.sample_point SET version=version+1 WHERE sample_point_id=:id")
                .param("id", OCCUPIED_POINT_ID).update();
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RETIREMENT_PREVIEW_STALE"));
        String expired = batchPreview();
        jdbc.sql("""
                UPDATE registry.formal_sample_retirement_batch
                SET snapshot=jsonb_set(snapshot,'{expiresAt}','"2020-01-01T00:00:00Z"'::jsonb)
                WHERE batch_id=:id
                """).param("id", UUID.fromString(expired)).update();
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", expired)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", OCCUPIED_POINT_ID).principal(() -> ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsChangedAuthoritativeMembershipAndDoesNotUseTheMasterTable() throws Exception {
        batchSource();
        String preview = batchPreview();
        jdbc.sql("UPDATE production.production_record SET status_code='DRAFT' WHERE record_id='batch-source'").update();
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", OCCUPIED_POINT_ID).principal(() -> ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void requiresPermissionAndKeepsPreviewsPrivateToTheirActor() throws Exception {
        batchSource();
        String preview = batchPreview();
        mvc.perform(post("/api/v1/formal-sample-points/retirement-previews").principal(() -> RESTRICTED))
                .andExpect(status().isForbidden());
        jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:actor,'SYSTEM_ADMIN')")
                .param("actor", RESTRICTED).update();
        mvc.perform(get("/api/v1/formal-sample-points/retirement-previews/{id}", preview)
                        .principal(() -> RESTRICTED)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollsBackEveryRetirementAndReceiptWhenTheSecondAuditFails() throws Exception {
        batchSource();
        jdbc.sql("UPDATE registry.sample_point SET kind_code='SURVEY_SITE' WHERE sample_point_id=:id")
                .param("id", LOGISTICS_POINT_ID).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES('batch-second','CORN','FARMER','230202',CURRENT_DATE,CURRENT_TIMESTAMP,
                  320,500,'APPROVED',:actor,2026,'YEAR','CONFIRMED',:id)
                """).param("actor", ADMIN).param("id", LOGISTICS_POINT_ID).update();
        String preview = batchPreview();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION platform.reject_formal_sample_audit_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                  IF NEW.action_code='FORMAL_SAMPLE_POINT_RETIRED'
                    AND NEW.aggregate_id='fa120000-0000-0000-0000-000000000002' THEN
                    RAISE EXCEPTION 'test rejects second retirement audit';
                  END IF;
                  RETURN NEW;
                END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_formal_sample_audit_for_test BEFORE INSERT
                ON platform.business_audit_event FOR EACH ROW
                EXECUTE FUNCTION platform.reject_formal_sample_audit_for_test()
                """).update();
        mvc.perform(put("/api/v1/formal-sample-points/retirement-previews/{id}/execution", preview)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停止经营\"}"))
                .andExpect(status().is5xxServerError());
        for (UUID id : java.util.List.of(OCCUPIED_POINT_ID, LOGISTICS_POINT_ID)) {
            mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/formal-sample-points/retirement-previews/{id}", preview).principal(() -> ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retiredCount").doesNotExist());
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_event_outbox WHERE action_code='FORMAL_SAMPLE_POINT_RETIRED'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void persistsAndReassignsAnActiveMaintainerFromTheEmployeeDirectory() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("维护人样本", "230202", "龙沙区维护人地址",
                                "123.94", "47.31", "FARMER", null, RESTRICTED)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maintainerSubjectId").value(RESTRICTED))
                .andExpect(jsonPath("$.data.maintainerDisplayName").value("正式样本维护受限用户"))
                .andReturn();
        UUID id = responseId(created);

        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("维护人样本", "230202", "龙沙区维护人地址",
                                "123.94", "47.31", "FARMER", 0L, ADMIN,
                                "原维护人岗位调整")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maintainerSubjectId").value(ADMIN))
                .andExpect(jsonPath("$.data.maintainerDisplayName").isNotEmpty());

        assertThat(jdbc.sql("""
                SELECT action_code FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                ORDER BY occurred_at
                """).param("id", id.toString()).query(String.class).list())
                .containsExactly("FORMAL_SAMPLE_POINT_CREATED",
                        "FORMAL_SAMPLE_POINT_MAINTAINER_REASSIGNED");
        assertThat(jdbc.sql("""
                SELECT detail->>'previousMaintainerSubjectId',
                       detail->>'maintainerSubjectId', detail->>'maintainerChangeReason'
                FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_MAINTAINER_REASSIGNED'
                """).param("id", id.toString()).query((row, index) ->
                        java.util.List.of(row.getString(1), row.getString(2), row.getString(3))).single())
                .containsExactly(RESTRICTED, ADMIN, "原维护人岗位调整");
    }

    @Test
    void assignsAnActiveMaintainerToAHistoricalLogisticsFormalSample() throws Exception {
        mvc.perform(put("/api/v1/formal-sample-points/{id}/maintainer", LOGISTICS_POINT_ID)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maintainerSubjectId":"formal-sample-manage-restricted",
                                  "maintainerChangeReason":"明确物流样本后续维护责任",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kindCode").value("LOGISTICS_NODE"))
                .andExpect(jsonPath("$.data.maintainerSubjectId").value(RESTRICTED))
                .andExpect(jsonPath("$.data.maintainerDisplayName").value("正式样本维护受限用户"))
                .andExpect(jsonPath("$.data.version").value(1));

        assertThat(jdbc.sql("""
                SELECT action_code FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT'
                  AND aggregate_id=:id ORDER BY occurred_at DESC LIMIT 1
                """).param("id", LOGISTICS_POINT_ID.toString()).query(String.class).single())
                .isEqualTo("FORMAL_SAMPLE_POINT_MAINTAINER_ASSIGNED");
    }

    @Test
    void physicallyDeletesAReferencedLogisticsSampleAndDetachesItsNode() throws Exception {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(
                  node_code,node_name,node_type_code,region_code,sample_point_id)
                VALUES('formal-delete-logistics-node','历史物流正式样本',
                  'ROAD_NODE','230202',:id)
                """).param("id", LOGISTICS_POINT_ID).update();

        mvc.perform(delete("/api/v1/formal-sample-points/{id}", LOGISTICS_POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", LOGISTICS_POINT_ID)
                        .principal(() -> ADMIN))
                .andExpect(status().isNotFound());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM logistics.logistics_node
                WHERE node_code='formal-delete-logistics-node' AND sample_point_id IS NULL
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", LOGISTICS_POINT_ID).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsMaintainerAssignmentWithoutManagePermissionWithoutWriting() throws Exception {
        mvc.perform(put("/api/v1/formal-sample-points/{id}/maintainer", LOGISTICS_POINT_ID)
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maintainerSubjectId":"production-tester",
                                  "maintainerChangeReason":"无权调整物流样本维护人",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE sample_point_id=:id AND maintainer_subject_id IS NULL
                  AND version=0 AND updated_by=:actor
                """).param("id", LOGISTICS_POINT_ID).param("actor", ADMIN)
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                """).param("id", LOGISTICS_POINT_ID.toString())
                .query(Long.class).single()).isZero();
    }

    @Test
    void requiresAReasonWhenTheMaintainerChanges() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("重派原因样本", "230202", "龙沙区重派原因地址",
                                "123.94", "47.31", "FARMER", null, RESTRICTED)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = responseId(created);

        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("重派原因样本", "230202", "龙沙区重派原因地址",
                                "123.94", "47.31", "FARMER", 0L, ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_POINT"));

        mvc.perform(get("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maintainerSubjectId").value(RESTRICTED))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void rejectsMissingInactiveOrOutOfScopeMaintainersWithoutWriting() throws Exception {
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("无效维护人样本", "230202", "龙沙区地址",
                                "123.94", "47.31", "FARMER", null, "missing-maintainer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_MAINTAINER"));

        jdbc.sql("""
                UPDATE platform.security_user SET account_status='SUSPENDED'
                WHERE subject_id=:subject
                """).param("subject", RESTRICTED).update();
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("停用维护人样本", "230202", "龙沙区地址",
                                "123.94", "47.31", "FARMER", null, RESTRICTED)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_MAINTAINER"));

        jdbc.sql("""
                UPDATE platform.security_user SET account_status='ACTIVE'
                WHERE subject_id=:subject
                """).param("subject", RESTRICTED).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("越权维护人样本", "230202", "龙沙区地址",
                                "123.94", "47.31", "FARMER", null, RESTRICTED)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FORMAL_SAMPLE_MAINTAINER"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE canonical_name IN ('无效维护人样本','停用维护人样本','越权维护人样本')
                """).query(Long.class).single()).isZero();
    }

    @Test
    void createsUpdatesRequeriesAndDeletesStableMasterDataWithDurableEvents()
            throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("龙沙区农户样本", "230202", "龙沙区新立街 1 号",
                                "123.94", "47.31", "FARMER", null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern(
                                "/api/v1/formal-sample-points/[0-9a-f-]+")))
                .andExpect(jsonPath("$.data.canonicalName").value("龙沙区农户样本"))
                .andExpect(jsonPath("$.data.objectTypeCode").value("FARMER"))
                .andExpect(jsonPath("$.data.objectTypeName").value("农户"))
                .andExpect(jsonPath("$.data.businessDomain").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.address").value("龙沙区新立街 1 号"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());

        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("龙沙区新立街 1 号"))
                .andExpect(jsonPath("$.data.networkMembershipCount").value(0));
        assertThat(jdbc.sql("""
                SELECT canonical_name FROM registry.sample_point WHERE sample_point_id=:id
                """).param("id", id).query(String.class).single())
                .isEqualTo("龙沙区农户样本");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership WHERE sample_point_id=:id
                """).param("id", id).query(Long.class).single()).isZero();

        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("龙沙区贸易商样本", "230202", "龙沙区新立街 2 号",
                                "123.941", "47.311", "TRADER", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectTypeCode").value("TRADER"))
                .andExpect(jsonPath("$.data.address").value("龙沙区新立街 2 号"))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canonicalName").value("龙沙区贸易商样本"))
                .andExpect(jsonPath("$.data.objectTypeCode").value("TRADER"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.formal_sample_point_profile
                WHERE sample_point_id=:id AND object_type_code='TRADER'
                """).param("id", id).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.formal_sample_point_profile
                WHERE sample_point_id=:id AND object_type_code='FARMER'
                """).param("id", id).query(Long.class).single()).isZero();

        assertThat(jdbc.sql("""
                SELECT action_code FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                ORDER BY occurred_at
                """).param("id", id.toString()).query(String.class).list())
                .containsExactly("FORMAL_SAMPLE_POINT_CREATED", "FORMAL_SAMPLE_POINT_UPDATED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND region_codes=ARRAY['230202']::varchar[]
                """).param("id", id.toString()).query(Long.class).single()).isEqualTo(2);
        assertThat(notifications.findVisible(
                        new AuthorizedReadScope(ADMIN, Set.of("230202")), ADMIN, 20))
                .anyMatch(event -> event.aggregateId().equals(id.toString())
                        && event.actionCode().equals("FORMAL_SAMPLE_POINT_UPDATED"));

        mvc.perform(delete("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.formal_sample_point_profile WHERE sample_point_id=:id
                """).param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id
                """).param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_DELETED'
                  AND detail->>'deletionMode'='PHYSICAL'
                """).param("id", id.toString()).query(Long.class).single()).isOne();
    }

    @Test
    void physicallyDeletesAReferencedSampleAndItsBusinessFacts()
            throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("有历史正式样本", "230202", "龙沙区历史样本地址",
                                "123.94", "47.31", "FARMER", null)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = responseId(created);
        String recordId = "formal-sample-retire-record";
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:recordId,'CORN','FARMER','230202',DATE '2026-09-03',
                  TIMESTAMPTZ '2026-09-03 09:30:00+08',320,500,'APPROVED',:actor,
                  2026,'YEAR','CONFIRMED',:pointId)
                """).param("recordId", recordId).param("actor", ADMIN)
                .param("pointId", id).update();

        mvc.perform(delete("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isNotFound());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id
                """).param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE record_id=:recordId AND sample_point_id=:id
                """).param("recordId", recordId).param("id", id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_DELETED'
                  AND detail->>'deletionMode'='PHYSICAL'
                """).param("id", id.toString()).query(Long.class).single()).isOne();
    }

    @Test
    void retiresAnExistingSampleWhilePreservingItsLastBusinessDataAndPublishingTheChange()
            throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("待淘汰正式样本", "230202", "龙沙区历史样本地址",
                                "123.94", "47.31", "FARMER", null)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = responseId(created);
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES('formal-sample-history-record','CORN','FARMER','230202',
                  DATE '2026-09-03',TIMESTAMPTZ '2026-09-03 09:30:00+08',320,500,
                  'APPROVED',:actor,2026,'YEAR','CONFIRMED',:pointId)
                """).param("actor", ADMIN).param("pointId", id).update();

        mvc.perform(put("/api/v1/formal-sample-points/{id}/retirement", id)
                        .principal(() -> ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":0,
                                  "reason":"该样本点已停止经营"
                                }
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE sample_point_id=:id AND deletion_state='RETIRED'
                  AND retired_by=:actor AND retired_reason='该样本点已停止经营'
                """).param("id", id).param("actor", ADMIN).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE record_id='formal-sample-history-record' AND sample_point_id=:id
                """).param("id", id).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_RETIRED'
                  AND detail->>'retirementReason'='该样本点已停止经营'
                """).param("id", id.toString()).query(Long.class).single()).isOne();
    }

    @Test
    void requiresManagePermissionAndBothOldAndNewRegionScopes() throws Exception {
        mvc.perform(put("/api/v1/formal-sample-points/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("未认证更新", "230202", "龙沙区地址", "123.94", "47.31",
                                "FARMER", 0L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("无权新建", "230202", "龙沙区地址", "123.94", "47.31",
                                "FARMER", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'SYSTEM_ADMIN')
                """).param("subject", RESTRICTED).update();
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("跨区新建", "230203", "建华区地址", "124.00", "47.40",
                                "FARMER", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("区内样本", "230202", "龙沙区地址", "123.94", "47.31",
                                "FARMER", null)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = responseId(created);
        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> RESTRICTED).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("跨区更新", "230203", "建华区地址", "124.00", "47.40",
                                "FARMER", 0L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void preservesOutsideCoordinatesAndPlacesCreateAndUpdateInsideSelectedRegion() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("区内示意样本", "230202", "填报地址", "124.00", "47.40",
                                "FARMER", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.longitude").value(124.00))
                .andExpect(jsonPath("$.data.latitude").value(47.40)).andReturn();
        UUID id = responseId(created);
        assertSchematicLocation(id, 124.00, 47.40);
        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("区内示意样本", "230202", "修改地址", "125.00", "48.40",
                                "FARMER", 0L)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", id).principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.longitude").value(125.00))
                .andExpect(jsonPath("$.data.latitude").value(48.40));
        assertSchematicLocation(id, 125.00, 48.40);
    }

    private void assertSchematicLocation(UUID id, double longitude, double latitude) {
        assertThat(jdbc.sql("""
                SELECT point.region_code='230202'
                  AND ST_X(point.governed_point)=:longitude
                  AND ST_Y(point.governed_point)=:latitude
                  AND point.location_mode='REGION_SCHEMATIC'
                  AND ST_Covers(ST_GeomFromGeoJSON(boundary.geo_json),point.display_point)
                FROM registry.sample_point point
                JOIN overview.administrative_boundary_render boundary
                  ON boundary.region_code=point.region_code
                WHERE point.sample_point_id=:id
                """).param("id", id).param("longitude", longitude).param("latitude", latitude)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void rejectsOccupiedCoordinatesAndStaleUpdates() throws Exception {
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("占位样本", "230202", "占位地址", "123.93", "47.30",
                                "FARMER", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_COORDINATE_OCCUPIED"));

        jdbc.sql("""
                UPDATE registry.sample_point
                SET approval_state='RETURNED',effective_to=DATE '2099-12-31'
                WHERE sample_point_id=:id
                """).param("id", OCCUPIED_POINT_ID).update();
        mvc.perform(put("/api/v1/formal-sample-points/{id}", OCCUPIED_POINT_ID)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("既有正式样本", "230202", "既有样本地址",
                                "123.931", "47.301", "FARMER", 0L, ADMIN,
                                "补录历史样本维护人")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectTypeCode").value("FARMER"))
                .andExpect(jsonPath("$.data.approvalState").value("RETURNED"))
                .andExpect(jsonPath("$.data.effectiveTo").value("2099-12-31"))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.formal_sample_point_profile
                WHERE sample_point_id=:id AND address='既有样本地址'
                """).param("id", OCCUPIED_POINT_ID).query(Long.class).single()).isOne();

        MvcResult created = mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("版本样本", "230202", "版本地址", "123.94", "47.31",
                                "FARMER", null)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = responseId(created);
        mvc.perform(put("/api/v1/formal-sample-points/{id}", id)
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("过期更新", "230202", "版本地址", "123.941", "47.311",
                                "FARMER", 1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("FORMAL_SAMPLE_POINT_VERSION_CONFLICT"));
    }

    @Test
    void auditFailureRollsBackPointProfileAndOutbox() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION platform.reject_formal_sample_audit_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN
                  RAISE EXCEPTION USING ERRCODE='23514',
                    MESSAGE='forced formal sample audit integrity failure';
                END
                $function$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_formal_sample_audit_for_test
                BEFORE INSERT ON platform.business_audit_event
                FOR EACH ROW WHEN (NEW.aggregate_type='FORMAL_SAMPLE_POINT')
                EXECUTE FUNCTION platform.reject_formal_sample_audit_for_test()
                """).update();

        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("回滚样本", "230202", "回滚地址", "123.94", "47.31",
                                "FARMER", null)))
                .andExpect(status().is5xxServerError());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point WHERE canonical_name='回滚样本'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM registry.formal_sample_point_profile")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='FORMAL_SAMPLE_POINT'
                """).query(Long.class).single()).isZero();
    }

    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    private static String draft(
            String canonicalName, String regionCode, String address,
            String longitude, String latitude, String objectTypeCode, Long expectedVersion) {
        return draft(canonicalName, regionCode, address, longitude, latitude,
                objectTypeCode, expectedVersion, ADMIN);
    }

    private static String draft(
            String canonicalName, String regionCode, String address,
            String longitude, String latitude, String objectTypeCode, Long expectedVersion,
            String maintainerSubjectId) {
        return draft(canonicalName, regionCode, address, longitude, latitude,
                objectTypeCode, expectedVersion, maintainerSubjectId, null);
    }

    private static String draft(
            String canonicalName, String regionCode, String address,
            String longitude, String latitude, String objectTypeCode, Long expectedVersion,
            String maintainerSubjectId, String maintainerChangeReason) {
        return """
                {
                  "canonicalName":"%s",
                  "regionCode":"%s",
                  "address":"%s",
                  "longitude":%s,
                  "latitude":%s,
                  "objectTypeCode":"%s",
                  "maintainerSubjectId":"%s"%s%s
                }
                """.formatted(canonicalName, regionCode, address, longitude, latitude,
                objectTypeCode, maintainerSubjectId, expectedVersion == null
                        ? "" : ",\n  \"expectedVersion\":" + expectedVersion,
                maintainerChangeReason == null ? ""
                        : ",\n  \"maintainerChangeReason\":\"" + maintainerChangeReason + "\"");
    }
}
