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
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_formal_sample_audit_for_test "
                + "ON platform.business_audit_event").update();
        jdbc.sql("DROP FUNCTION IF EXISTS platform.reject_formal_sample_audit_for_test()")
                .update();
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
    void rejectsOutsideOrOccupiedCoordinatesAndStaleUpdates() throws Exception {
        mvc.perform(post("/api/v1/formal-sample-points")
                        .principal(() -> ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("越界样本", "230202", "越界地址", "124.00", "47.40",
                                "FARMER", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COORDINATE_OUTSIDE_REGION"));
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
