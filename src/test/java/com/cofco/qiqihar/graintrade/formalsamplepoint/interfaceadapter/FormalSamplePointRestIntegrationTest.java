package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class FormalSamplePointRestIntegrationTest {
    private static final String ADMIN = "production-tester";
    private static final String RESTRICTED = "formal-sample-delete-restricted";
    private static final UUID POINT_ID =
            UUID.fromString("fa110000-0000-0000-0000-000000000001");
    private static final String RECORD_ID = "fa110000-0000-0000-0000-000000000002";
    private static final UUID RESOLUTION_BATCH_ID =
            UUID.fromString("fa110000-0000-0000-0000-000000000003");
    private static final UUID RESOLUTION_REVISION_ID =
            UUID.fromString("fa110000-0000-0000-0000-000000000004");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private long retiredRevisionCountBefore;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.business_event_outbox,platform.business_audit_event,
                  registry.sample_network_year,registry.sample_subject_resolution_batch,
                  registry.sample_point,
                  production.production_record CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'正式样本受限用户','TEST',true)
                ON CONFLICT(subject_id) DO UPDATE SET enabled=true
                """).param("subject", RESTRICTED).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'BUSINESS_OPERATOR') ON CONFLICT DO NOTHING
                """).param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:subject,'230202') ON CONFLICT DO NOTHING
                """).param("subject", RESTRICTED).update();
        insertPointAndRecord();
        insertLegacyIdentityAndResolutionReferences();
        retiredRevisionCountBefore = retiredRevisionCount();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_formal_sample_retirement_for_test "
                + "ON registry.sample_point").update();
        jdbc.sql("DROP FUNCTION IF EXISTS registry.reject_formal_sample_retirement_for_test()")
                .update();
    }

    @Test
    void readsAuthorizedFormalSamplesAndPhysicallyDeletesReferencedDataWithAuditAndOutbox()
            throws Exception {
        mvc.perform(get("/api/v1/formal-sample-points").principal(() -> ADMIN)
                        .queryParam("regionCode", "230202")
                        .queryParam("keyword", "龙沙")
                        .queryParam("page", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(POINT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].annualObservationCount").value(1))
                .andExpect(jsonPath("$.data.items[0].networkMembershipCount").value(0));
        mvc.perform(get("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kindCode").value("SURVEY_SITE"))
                .andExpect(jsonPath("$.data.version").value(0));

        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FORMAL_SAMPLE_POINT_NOT_FOUND"));
        mvc.perform(get("/api/v1/formal-sample-points").principal(() -> ADMIN)
                        .queryParam("regionCode", "230202")
                        .queryParam("page", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(directReferenceCount()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id
                """).param("id", POINT_ID).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_DELETED'
                  AND detail->>'deletionMode'='PHYSICAL'
                """).param("id", POINT_ID.toString()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                  AND action_code='FORMAL_SAMPLE_POINT_DELETED'
                  AND region_codes=ARRAY['230202']::varchar[]
                """).param("id", POINT_ID.toString()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point_subject_identity
                WHERE sample_point_id=:id
                """).param("id", POINT_ID).query(Long.class).single()).isZero();
        assertThat(retiredRevisionCount()).isEqualTo(retiredRevisionCountBefore);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_subject_resolution_item
                WHERE batch_id=:batchId AND target_sample_point_id=:id
                """).param("batchId", RESOLUTION_BATCH_ID).param("id", POINT_ID)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_subject_resolution_revision
                WHERE resolution_revision_id=:revisionId AND target_sample_point_id=:id
                """).param("revisionId", RESOLUTION_REVISION_ID).param("id", POINT_ID)
                .query(Long.class).single()).isZero();
    }

    @Test
    void deniesMissingPermissionAndStaleDeletionButPhysicallyDeletesNetworkReferencedSamples()
            throws Exception {
        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> RESTRICTED).queryParam("expectedVersion", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'SYSTEM_ADMIN')
                """).param("subject", RESTRICTED).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", RESTRICTED).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:subject,'230203')
                """).param("subject", RESTRICTED).update();
        mvc.perform(get("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> RESTRICTED))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> RESTRICTED).queryParam("expectedVersion", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT registry.delete_formal_sample_point(:id,NULL,'230202',:actor)
                """).param("id", POINT_ID).param("actor", ADMIN)
                .query(String.class).single())
                .isEqualTo("VERSION_CONFLICT");
        assertThat(jdbc.sql("""
                SELECT registry.delete_formal_sample_point(:id,0,'230203',:actor)
                """).param("id", POINT_ID).param("actor", ADMIN)
                .query(String.class).single())
                .isEqualTo("REGION_CONFLICT");

        jdbc.sql("UPDATE registry.sample_point SET version=1 WHERE sample_point_id=:id")
                .param("id", POINT_ID).update();
        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FORMAL_SAMPLE_POINT_VERSION_CONFLICT"));
        jdbc.sql("UPDATE registry.sample_point SET version=0 WHERE sample_point_id=:id")
                .param("id", POINT_ID).update();

        jdbc.sql("""
                INSERT INTO registry.sample_network_year(network_year,created_by)
                VALUES(2026,:actor)
                """).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,
                  source_code,created_by)
                VALUES(2026,:id,NULL,'CANDIDATE','MANUAL',:actor)
                """).param("id", POINT_ID).param("actor", ADMIN).update();
        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership
                WHERE sample_point_id=:id
                """).param("id", POINT_ID).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", POINT_ID).query(Long.class).single()).isZero();
    }

    @Test
    void physicallyDeletesBusinessFactsWithoutLeavingImportHistoryInBusinessQueries()
            throws Exception {
        UUID importJobId = UUID.fromString("fa110000-0000-0000-0000-000000000020");
        jdbc.sql("""
                INSERT INTO platform.import_job(
                  import_job_id,domain_code,idempotency_key,content_sha256,source_content,
                  requested_by,work_unit_code,status_code,created_at,completed_at)
                VALUES(:id,'PRODUCTION','formal-delete-history',repeat('a',64),'retained source',
                  :actor,'TEST','COMPLETED',now(),now())
                """).param("id", importJobId).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.import_row_result(
                  import_job_id,row_number,outcome_code,business_record_id,row_data)
                VALUES(:id,2,'IMPORTED',:recordId,'{}'::jsonb)
                """).param("id", importJobId).param("recordId", RECORD_ID).update();
        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result result
                JOIN production.production_record record
                  ON record.record_id=result.business_record_id
                JOIN registry.sample_point point USING(sample_point_id)
                WHERE result.import_job_id=:jobId AND point.sample_point_id=:pointId
                """).param("jobId", importJobId).param("pointId", POINT_ID)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record WHERE record_id=:id")
                .param("id", RECORD_ID).query(Long.class).single()).isZero();
    }

    @Test
    void unexpectedPhysicalDeletionFailureRollsBackPointReferencesAndAudit() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION registry.reject_formal_sample_retirement_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN RAISE EXCEPTION 'forced formal sample retirement failure'; END
                $function$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_formal_sample_retirement_for_test
                BEFORE DELETE ON registry.sample_point
                FOR EACH ROW
                EXECUTE FUNCTION registry.reject_formal_sample_retirement_for_test()
                """).update();

        mvc.perform(delete("/api/v1/formal-sample-points/{id}", POINT_ID)
                        .principal(() -> ADMIN).queryParam("expectedVersion", "0"))
                .andExpect(status().is5xxServerError());

        assertThat(jdbc.sql("SELECT count(*) FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", POINT_ID).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record WHERE record_id=:id")
                .param("id", RECORD_ID).query(Long.class).single()).isOne();
        assertThat(directReferenceCount()).isEqualTo(4);
        assertThat(retiredRevisionCount()).isEqualTo(retiredRevisionCountBefore);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                """).param("id", POINT_ID.toString()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='FORMAL_SAMPLE_POINT' AND aggregate_id=:id
                """).param("id", POINT_ID.toString()).query(Long.class).single()).isZero();
    }

    private void insertPointAndRecord() {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                SELECT :id,'SURVEY_SITE','龙沙区正式样本','230202','APPROVED','VALID',
                  ST_PointOnSurface(geometry),DATE '2026-01-01',:actor,:actor
                FROM overview.administrative_boundary WHERE region_code='230202'
                """).param("id", POINT_ID).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:recordId,'CORN','FARMER','230202',DATE '2026-08-20',
                  TIMESTAMPTZ '2026-08-20 09:30:00+08',100,500,'APPROVED',:actor,
                  2026,'YEAR','CONFIRMED',:pointId)
                """).param("recordId", RECORD_ID).param("actor", ADMIN)
                .param("pointId", POINT_ID).update();
    }

    private void insertLegacyIdentityAndResolutionReferences() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL session_replication_role=replica");
            statement.execute("""
                    INSERT INTO registry.sample_point_subject_identity(
                      business_domain,subject_id,sample_point_id,created_at,created_by)
                    VALUES('PRODUCTION','formal-delete-legacy-subject','%s',now(),'%s')
                    """.formatted(POINT_ID, ADMIN));
            connection.commit();
        }
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_batch(
                  batch_id,idempotency_key,input_digest,expected_item_count,status_code,
                  created_at,created_by,applied_at,applied_by)
                VALUES(:batchId,'formal-delete-resolution',repeat('a',64),1,'APPLIED',
                  now(),:actor,now(),:actor)
                """).param("batchId", RESOLUTION_BATCH_ID).param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_item(
                  batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                  resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code,
                  before_snapshot,after_snapshot,before_sha256,after_sha256,
                  applied_source_version,applied_resolution_revision_id,applied_at,applied_by)
                VALUES(:batchId,1,'PRODUCTION',:recordId,0,'LINK',
                  'formal-delete-legacy-subject',:pointId,'TEST_REFERENCE','APPLIED',
                  '{}'::jsonb,'{}'::jsonb,repeat('b',64),repeat('c',64),0,
                  :revisionId,now(),:actor)
                """).param("batchId", RESOLUTION_BATCH_ID).param("recordId", RECORD_ID)
                .param("pointId", POINT_ID).param("revisionId", RESOLUTION_REVISION_ID)
                .param("actor", ADMIN).update();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_revision(
                  resolution_revision_id,source_domain,source_record_id,resolution_sequence,
                  resolution_action,stable_subject_id,target_sample_point_id,source_version,
                  predecessor_revision_id,batch_id,item_sequence,before_sha256,after_sha256,
                  occurred_at,actor)
                VALUES(:revisionId,'PRODUCTION',:recordId,1,'LINK',
                  'formal-delete-legacy-subject',:pointId,0,NULL,:batchId,1,
                  repeat('b',64),repeat('c',64),now(),:actor)
                """).param("revisionId", RESOLUTION_REVISION_ID).param("recordId", RECORD_ID)
                .param("pointId", POINT_ID).param("batchId", RESOLUTION_BATCH_ID)
                .param("actor", ADMIN).update();
    }

    private long directReferenceCount() {
        return jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM platform.formal_sample_observation WHERE sample_point_id=:id)
                + (SELECT count(*) FROM logistics.logistics_node WHERE sample_point_id=:id)
                + (SELECT count(*) FROM market.market_inventory_governance WHERE sample_point_id=:id)
                + (SELECT count(*) FROM market.market_record WHERE sample_point_id=:id)
                + (SELECT count(*) FROM production.production_record WHERE sample_point_id=:id)
                + (SELECT count(*) FROM logistics.route_event WHERE sample_point_id=:id)
                + (SELECT count(*) FROM registry.sample_network_membership WHERE sample_point_id=:id)
                + (SELECT count(*) FROM market.sample_point_inventory_contract WHERE sample_point_id=:id)
                + (SELECT count(*) FROM registry.sample_point_subject_identity WHERE sample_point_id=:id)
                + (SELECT count(*) FROM registry.sample_subject_resolution_item WHERE target_sample_point_id=:id)
                + (SELECT count(*) FROM registry.sample_subject_resolution_revision WHERE target_sample_point_id=:id)
                """).param("id", POINT_ID).query(Long.class).single();
    }

    private long retiredRevisionCount() {
        return jdbc.sql("""
                SELECT count(*) FROM platform.master_data_revision
                WHERE entity_type='SUBJECT'
                  AND entity_key='PRODUCTION:formal-delete-legacy-subject'
                  AND operation_code='DELETE' AND governance_state='RETIRED'
                  AND reviewed_by='FORMAL_SAMPLE_DELETE_REVIEWER'
                """).query(Long.class).single();
    }
}
