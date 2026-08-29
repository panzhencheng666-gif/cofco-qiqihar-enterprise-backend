package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.identity.application.AccessReviewRepository;
import com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationTokenCodec;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import java.util.UUID;

@SpringBootTest(classes=GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class IdentityGovernanceRestIntegrationTest {
    private static final String WORK_UNIT="QIQIHAR_BUSINESS";
    private static final String FUTURE_PERMISSION="IDENTITY_GOV_FUTURE_PERMISSION";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired AccessReviewRepository accessReviewRepository;
    @Autowired IdentityInvitationTokenCodec invitationTokens;
    private JdbcClient jdbc;
    private String employee;
    private UUID reviewId;
    private static final String UNIT_ADMIN="identity-governance-unit-admin";
    private static final String UNIT_SYSTEM_ADMIN="identity-governance-unit-system-admin";
    private static final String UNIT_ACCOUNT_OWNER="identity-governance-unit-account-owner";
    private static final String TOWNSHIP="230202997";
    private static final String VILLAGE="230202997001";
    private static final String OUTSIDE_TOWNSHIP="230208997";

    @BeforeEach
    void prepare() {
        jdbc=JdbcClient.create(dataSource);
        deleteFuturePermission();
        employee="identity-governance-"+UUID.randomUUID();
        deleteAssignments("identity-governance-employee");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-employee'").update();
        deleteIdentityLifecycle("identity-governance-outside-reviewer");
        deleteAssignments("identity-governance-outside-reviewer");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-outside-reviewer'").update();
        deleteAssignments(UNIT_ADMIN);
        deleteAssignments(UNIT_SYSTEM_ADMIN);
        deleteAssignments(UNIT_ACCOUNT_OWNER);
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",UNIT_SYSTEM_ADMIN).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",UNIT_ACCOUNT_OWNER).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('QIQIHAR_BUSINESS','身份治理自动化测试单位',9981)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('IDENTITY_GOV_OUTSIDE','身份治理外单位测试',9982)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES('identity-governance-outside-reviewer','外单位复核员','IDENTITY_GOV_OUTSIDE',true)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES('identity-governance-outside-reviewer','ACCESS_REVIEWER')
                """).update();
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "身份治理测试乡镇", "230202", "TOWNSHIP", 9997);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE, "身份治理测试行政村", TOWNSHIP, "VILLAGE", 9998);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_TOWNSHIP, "身份治理外单位乡镇", "230208", "TOWNSHIP", 9999);
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('QIQIHAR_BUSINESS','230202'),
                      ('QIQIHAR_BUSINESS',:township),
                      ('QIQIHAR_BUSINESS',:village)
                ON CONFLICT DO NOTHING
                """).param("township",TOWNSHIP).param("village",VILLAGE).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('IDENTITY_GOV_OUTSIDE',:township)
                ON CONFLICT DO NOTHING
                """).param("township",OUTSIDE_TOWNSHIP).update();
        jdbc.sql("""
                INSERT INTO platform.position(code,name,sort_order)
                VALUES('GOVERNANCE_REPORTER','区域填报专员',9980)
                ON CONFLICT(code) DO NOTHING
                """).update();
    }

    @AfterEach
    void cleanup() {
        if(jdbc==null)return;
        deleteFuturePermission();
        if(reviewId!=null)jdbc.sql("DELETE FROM platform.access_review_campaign WHERE review_id=:review")
                .param("review",reviewId).update();
        if(employee!=null){
            deleteIdentityLifecycle(employee);
            deleteAssignments(employee);
            long immutableAudit=jdbc.sql("""
                    SELECT count(*) FROM platform.business_audit_event WHERE actor_subject_id=:subject
                    """).param("subject",employee).query(Long.class).single();
            if(immutableAudit==0)jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:employee")
                    .param("employee",employee).update();
        }
        deleteIdentityLifecycle("identity-governance-outside-reviewer");
        deleteAssignments("identity-governance-outside-reviewer");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-outside-reviewer'").update();
        deleteAssignments(UNIT_ADMIN);
        deleteAssignments(UNIT_SYSTEM_ADMIN);
        deleteAssignments(UNIT_ACCOUNT_OWNER);
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",UNIT_SYSTEM_ADMIN).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",UNIT_ACCOUNT_OWNER).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code='IDENTITY_GOV_OUTSIDE'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='IDENTITY_GOV_OUTSIDE'").update();
        jdbc.sql("DELETE FROM platform.position WHERE code='GOVERNANCE_REPORTER'").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                .param("unit",WORK_UNIT).update();
        GovernedMasterDataFixtures.deleteRegions(
                jdbc, java.util.List.of(VILLAGE, TOWNSHIP, OUTSIDE_TOWNSHIP));
        // Immutable audit events retain their governed work-unit foreign key. Keep the stable
        // test fixtures (including UNIT_ADMIN after its first invitation audit) and recreate
        // their mutable assignments in prepare() instead of deleting history.
    }

    @Test
    void explicitIdentityIsolationMatrixIsFailClosedAndDurable() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:admin,'矩阵单位管理员',:unit,true),
                      (:systemAdmin,'矩阵系统管理员',:unit,true),
                      (:owner,'矩阵平台所有者',:unit,true)
                ON CONFLICT(subject_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    work_unit_code=EXCLUDED.work_unit_code,enabled=EXCLUDED.enabled
                """).param("admin",UNIT_ADMIN).param("systemAdmin",UNIT_SYSTEM_ADMIN)
                .param("owner",UNIT_ACCOUNT_OWNER).param("unit",WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:admin,'IDENTITY_ADMIN'),(:systemAdmin,'SYSTEM_ADMIN'),(:owner,'ACCOUNT_OWNER')
                """).param("admin",UNIT_ADMIN).param("systemAdmin",UNIT_SYSTEM_ADMIN)
                .param("owner",UNIT_ACCOUNT_OWNER).update();

        org.assertj.core.api.Assertions.assertThat(java.util.Set.of(
                UNIT_ADMIN,employee,"identity-governance-outside-reviewer",
                UNIT_SYSTEM_ADMIN,UNIT_ACCOUNT_OWNER)).hasSize(5);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.work_unit_region_scope a
                JOIN platform.work_unit_region_scope b ON a.region_code=b.region_code
                WHERE a.work_unit_code=:inside AND b.work_unit_code=:outside
                  AND a.region_code IN (:insideRegion,:outsideRegion)
                """).param("inside",WORK_UNIT).param("outside","IDENTITY_GOV_OUTSIDE")
                .param("insideRegion",TOWNSHIP).param("outsideRegion",OUTSIDE_TOWNSHIP)
                .query(Long.class).single()).isZero();

        mvc.perform(inviteRequest().principal(() -> UNIT_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"subjectId":"%s","displayName":"矩阵单位员工",
                                 "workUnitCode":"%s","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,WORK_UNIT,TOWNSHIP)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contractVersion").value("2026-08-30"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"));
        mvc.perform(get("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnitCode").value(WORK_UNIT))
                .andExpect(jsonPath("$.data.regionCodes[0]").value(TOWNSHIP));
        mvc.perform(get("/api/v1/identity/employees/{subjectId}/invitation",employee)
                        .principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.token").doesNotExist());

        mvc.perform(get("/api/v1/identity/employees/{subjectId}",
                        "identity-governance-outside-reviewer").principal(() -> UNIT_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SUBJECT_NOT_FOUND"));
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",
                        "identity-governance-outside-reviewer").principal(() -> UNIT_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":0,"displayName":"跨单位探测","workUnitCode":"IDENTITY_GOV_OUTSIDE",
                                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE","positionCodes":[],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(OUTSIDE_TOWNSHIP)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SUBJECT_NOT_FOUND"));
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":0,"displayName":"矩阵单位员工","workUnitCode":"%s",
                                 "accountStatus":"INVITED","employmentStatus":"ACTIVE",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(WORK_UNIT,OUTSIDE_TOWNSHIP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"));

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":0,"displayName":"职责分离探测","workUnitCode":"%s",
                                 "accountStatus":"INVITED","employmentStatus":"ACTIVE",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR","BUSINESS_REVIEWER"],"regionCodes":["%s"]}
                                """.formatted(WORK_UNIT,TOWNSHIP)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_ROLE_ASSIGNMENT_DENIED"));
        for(String protectedSubject : java.util.List.of(UNIT_SYSTEM_ADMIN,UNIT_ACCOUNT_OWNER)) {
            mvc.perform(put("/api/v1/identity/employees/{subjectId}",protectedSubject)
                            .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                                    {"version":0,"displayName":"保护角色探测","workUnitCode":"%s",
                                     "accountStatus":"ACTIVE","employmentStatus":"ACTIVE","positionCodes":[],
                                     "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                    """.formatted(WORK_UNIT,TOWNSHIP)))
                    .andExpect(status().isForbidden());
        }

        activateInvitedEmployee();
        String sessionId=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO platform.http_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                VALUES(:id,:id,:now,:now,1800,:expiry,:subject)
                """).param("id",sessionId).param("now",now).param("expiry",now+1_800_000)
                .param("subject",employee).update();
        jdbc.sql("""
                INSERT INTO platform.oidc_session_registry(
                    session_id,security_subject_id,issuer_uri,provider_subject,audience,
                    identity_version,expires_at)
                VALUES(:id,:subject,'https://idp.example.test/realms/cofco','matrix-provider',
                       ARRAY['enterprise'],0,now()+interval '30 minutes')
                """).param("id",sessionId).param("subject",employee).update();
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":1,"displayName":"矩阵员工已停用","workUnitCode":"%s",
                                 "accountStatus":"SUSPENDED","employmentStatus":"ACTIVE",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(WORK_UNIT,TOWNSHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("矩阵员工已停用"))
                .andExpect(jsonPath("$.data.accountStatus").value("SUSPENDED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "SELECT count(*) FROM platform.http_session WHERE session_id=:id")
                .param("id",sessionId).query(Long.class).single()).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.oidc_session_registry
                WHERE session_id=:id AND revoked_at IS NOT NULL
                  AND revocation_reason='IDENTITY_CHANGED'
                """).param("id",sessionId).query(Long.class).single()).isOne();

        UUID auditEvent=jdbc.sql("""
                SELECT event_id FROM platform.business_audit_event
                WHERE aggregate_type='SECURITY_USER' AND aggregate_id=:subject
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("subject",employee).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE platform.business_audit_event SET action_code='MATRIX_TAMPER'
                WHERE event_id=:event
                """).param("event",auditEvent).update())
                .hasMessageContaining("business audit events are immutable");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE event_id=:event AND action_code<>'MATRIX_TAMPER'
                """).param("event",auditEvent).query(Long.class).single()).isOne();
    }

    @Test
    void employeeAdministrationCannotRemoveTheBootstrapOnlyAccountOwnerRole() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'受控平台所有者','QIQIHAR_BUSINESS',true)
                """).param("subject",UNIT_ACCOUNT_OWNER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'ACCOUNT_OWNER')
                """).param("subject",UNIT_ACCOUNT_OWNER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'单位权限管理员','QIQIHAR_BUSINESS',true)
                ON CONFLICT(subject_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    work_unit_code=EXCLUDED.work_unit_code,enabled=EXCLUDED.enabled
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'IDENTITY_ADMIN')
                """).param("subject",UNIT_ADMIN).update();

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",UNIT_ACCOUNT_OWNER)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"displayName":"受控平台所有者",
                                 "workUnitCode":"QIQIHAR_BUSINESS","accountStatus":"ACTIVE",
                                 "employmentStatus":"ACTIVE","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(TOWNSHIP)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_OWNER_ADMINISTRATION_DENIED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.security_user_role
                WHERE subject_id=:subject AND role_code='ACCOUNT_OWNER'
                  AND CURRENT_TIMESTAMP>=valid_from
                  AND (valid_until IS NULL OR CURRENT_TIMESTAMP<valid_until)
                """).param("subject",UNIT_ACCOUNT_OWNER).query(Long.class).single()).isOne();
    }

    @Test
    void unitAdministratorsCannotReadOtherUnitsOrGrantSystemAdministrator() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'单位权限管理员','QIQIHAR_BUSINESS',true)
                ON CONFLICT(subject_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    work_unit_code=EXCLUDED.work_unit_code,enabled=EXCLUDED.enabled
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'IDENTITY_ADMIN')
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'同单位系统管理员','QIQIHAR_BUSINESS',true)
                """).param("subject",UNIT_SYSTEM_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'SYSTEM_ADMIN')
                """).param("subject",UNIT_SYSTEM_ADMIN).update();

        mvc.perform(inviteRequest()
                        .principal(() -> "identity-governance-outside-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"无权邀请探测",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        mvc.perform(get("/api/v1/identity/employees").principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.subjectId == 'identity-governance-unit-admin')]").exists())
                .andExpect(jsonPath("$.data[?(@.subjectId == 'identity-governance-outside-reviewer')]").doesNotExist());

        mvc.perform(get("/api/v1/identity/employees/{subjectId}","identity-governance-outside-reviewer")
                        .principal(() -> UNIT_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SUBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("员工账号不存在"));

        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnits.length()").value(1))
                .andExpect(jsonPath("$.data.workUnits[0].code").value(WORK_UNIT))
                .andExpect(jsonPath("$.data.roles[?(@.code == 'SYSTEM_ADMIN')]").doesNotExist());

        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode","NEHE_DEPOT").principal(() -> UNIT_ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_WORK_UNIT_DENIED"));

        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"越权测试员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["SYSTEM_ADMIN"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_ROLE_ASSIGNMENT_DENIED"));

        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"跨单位测试员工",
                                 "workUnitCode":"NEHE_DEPOT","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_WORK_UNIT_DENIED"))
                .andExpect(jsonPath("$.error.message").value("无权访问其他工作单位"));

        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"identity-governance-outside-reviewer","displayName":"越权探测",
                                 "workUnitCode":"NEHE_DEPOT","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_WORK_UNIT_DENIED"))
                .andExpect(jsonPath("$.error.message").value("无权访问其他工作单位"));

        String invalidAssignment = """
                {"subjectId":"%s","displayName":"无效授权探测",
                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["INVALID_REGION"]}
                """;
        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAssignment.formatted("identity-governance-outside-reviewer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"))
                .andExpect(jsonPath("$.error.message").value("员工账号或授权信息不完整"));

        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"本单位受邀员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountStatus").value("INVITED"))
                .andExpect(jsonPath("$.data.workUnitCode").value("QIQIHAR_BUSINESS"));
        mvc.perform(inviteRequest()
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAssignment.formatted(employee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"))
                .andExpect(jsonPath("$.error.message").value("员工账号或授权信息不完整"));

        mvc.perform(put("/api/v1/identity/employees/{subjectId}","identity-governance-outside-reviewer")
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"displayName":"外单位复核员",
                                 "workUnitCode":"NEHE_DEPOT","accountStatus":"ACTIVE",
                                 "employmentStatus":"ACTIVE","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["ACCESS_REVIEWER"],"regionCodes":["230202"]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SUBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("员工账号不存在"));

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",UNIT_ADMIN)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"displayName":"单位权限管理员",
                                 "workUnitCode":"QIQIHAR_BUSINESS","accountStatus":"ACTIVE",
                                 "employmentStatus":"ACTIVE","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["SYSTEM_ADMIN"],"regionCodes":["230202"]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SELF_ADMINISTRATION_DENIED"))
                .andExpect(jsonPath("$.error.message").value("不能修改本人的账号或授权"));

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",UNIT_SYSTEM_ADMIN)
                        .principal(() -> UNIT_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"displayName":"同单位系统管理员",
                                 "workUnitCode":"QIQIHAR_BUSINESS","accountStatus":"ACTIVE",
                                 "employmentStatus":"ACTIVE","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_ROLE_ASSIGNMENT_DENIED"))
                .andExpect(jsonPath("$.error.message").value("当前账号不能授予系统管理员角色"));

        mvc.perform(get("/api/v1/identity/employees/{subjectId}","identity-governance-outside-reviewer")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnitCode").value("IDENTITY_GOV_OUTSIDE"));

    }

    @Test
    void invitationRevocationDoesNotRevealCrossUnitExistenceThroughDifferent404Errors() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'单位权限管理员','QIQIHAR_BUSINESS',true)
                ON CONFLICT(subject_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    work_unit_code=EXCLUDED.work_unit_code,enabled=EXCLUDED.enabled
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subject,'IDENTITY_ADMIN')
                """).param("subject",UNIT_ADMIN).update();
        UUID invitationId=UUID.randomUUID();
        String token="cross-unit-invitation-token-"+UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.identity_invitation(
                    invitation_id,security_subject_id,token_hash,encrypted_delivery_payload,
                    delivery_address_sha256,expires_at,created_by,idempotency_key,request_fingerprint)
                VALUES(:id,'identity-governance-outside-reviewer',:tokenHash,:payload,
                    :addressHash,now()+interval '1 day','production-tester',:key,:fingerprint)
                """).param("id",invitationId)
                .param("tokenHash",invitationTokens.sha256(token))
                .param("payload",invitationTokens.encryptDeliveryPayload("outside@example.test",token))
                .param("addressHash",invitationTokens.sha256("outside@example.test"))
                .param("key","cross-unit-"+invitationId)
                .param("fingerprint",invitationTokens.sha256(invitationId.toString())).update();

        for(UUID probed : java.util.List.of(invitationId,UUID.randomUUID())) {
            mvc.perform(post("/api/v1/identity/invitations/{invitationId}/revoke",probed)
                            .principal(() -> UNIT_ADMIN))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("邀请不存在"));
        }
    }

    @Test
    void newlyRegisteredPermissionsAreAlwaysGrantedToSystemAdministrators() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.access_permission(code,name,active,sort_order)
                VALUES(:code,'未来权限自动授权测试',true,9998)
                """).param("code",FUTURE_PERMISSION).update();

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.access_role_permission
                WHERE role_code='SYSTEM_ADMIN' AND permission_code=:code
                """).param("code",FUTURE_PERMISSION).query(Long.class).single()).isOne();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.access_role_permission
                WHERE role_code='IDENTITY_ADMIN' AND permission_code=:code
                """).param("code",FUTURE_PERMISSION).query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/session/me").principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[?(@ == '%s')]".formatted(FUTURE_PERMISSION)).exists());
    }

    private void deleteFuturePermission() {
        jdbc.sql("DELETE FROM platform.access_role_permission WHERE permission_code=:code")
                .param("code",FUTURE_PERMISSION).update();
        jdbc.sql("DELETE FROM platform.access_permission WHERE code=:code")
                .param("code",FUTURE_PERMISSION).update();
    }

    private void deleteAssignments(String subjectId) {
        jdbc.sql("DELETE FROM platform.security_user_position WHERE subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject",subjectId).update();
    }

    private void deleteIdentityLifecycle(String subjectId) {
        jdbc.sql("DELETE FROM platform.oidc_session_registry WHERE security_subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.identity_delivery_outbox WHERE security_subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.identity_invitation WHERE security_subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.identity_provider_binding WHERE security_subject_id=:subject")
                .param("subject",subjectId).update();
    }

    @Test
    void systemAdministratorRoleIncludesEveryActivePermission() {
        long missing=jdbc.sql("""
                SELECT count(*)
                FROM platform.access_permission permission
                WHERE permission.active
                  AND NOT EXISTS (
                    SELECT 1 FROM platform.access_role_permission assignment
                    WHERE assignment.role_code='SYSTEM_ADMIN'
                      AND assignment.permission_code=permission.code)
                """).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(missing).isZero();
    }

    @Test
    void privilegedBootstrapRolesAreHiddenAndCannotBeAssignedThroughIdentityGovernance() throws Exception {
        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[?(@.code == 'ACCOUNT_OWNER')]").doesNotExist())
                .andExpect(jsonPath("$.data.roles[?(@.code == 'SYSTEM_ADMIN')]").doesNotExist());

        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"所有者角色越权探测",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["ACCOUNT_OWNER"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_OWNER_ASSIGNMENT_DENIED"));

        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"系统管理员角色越权探测",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["SYSTEM_ADMIN"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_ROLE_ASSIGNMENT_DENIED"));
    }

    @Test
    void administratorsInviteActivateGovernAndTerminateEmployeesWithAuditAndCas() throws Exception {
        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'QIQIHAR_BUSINESS')]").exists())
                .andExpect(jsonPath("$.data.roles[?(@.code == 'BUSINESS_OPERATOR')]").exists())
                .andExpect(jsonPath("$.data.positions").isEmpty())
                .andExpect(jsonPath("$.data.regionCodes[0]").value(TOWNSHIP));

        String invitation="""
                {"subjectId":"%s","displayName":"张敏",
                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                """.formatted(employee,TOWNSHIP);
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(invitation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subjectId").value(employee))
                .andExpect(jsonPath("$.data.accountStatus").value("INVITED"))
                .andExpect(jsonPath("$.data.roles[0].code").value("BUSINESS_OPERATOR"))
                .andExpect(jsonPath("$.data.positions[0].code").value("GOVERNANCE_REPORTER"))
                .andExpect(jsonPath("$.data.regionCodes[0]").value(TOWNSHIP))
                .andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isForbidden());

        String activation="""
                {"version":0,"displayName":"张敏","workUnitCode":"QIQIHAR_BUSINESS",
                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE",
                 "positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                """.formatted(TOWNSHIP);
        activateInvitedEmployee();
        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'BUSINESS_CREATE')]").exists());
        mvc.perform(get("/api/v1/identity/employees").principal(() -> employee))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(activation))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_VERSION_CONFLICT"));

        String terminated=activation.replace("\"version\":0","\"version\":1")
                .replace("\"employmentStatus\":\"ACTIVE\"","\"employmentStatus\":\"TERMINATED\"");
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(terminated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employmentStatus").value("TERMINATED"));
        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='SECURITY_USER' AND aggregate_id=:employee
                """).param("employee",employee).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void exposesExactlyReporterAndAdministratorAndAdministratorCanFillAndReview() throws Exception {
        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.roles[0].code").value("BUSINESS_OPERATOR"))
                .andExpect(jsonPath("$.data.roles[0].name").value("填报员"))
                .andExpect(jsonPath("$.data.roles[1].code").value("BUSINESS_REVIEWER"))
                .andExpect(jsonPath("$.data.roles[1].name").value("管理员"));

        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"业务管理员",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_REVIEWER"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0].code").value("BUSINESS_REVIEWER"));
        activateInvitedEmployee();

        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCodes.length()").value(1))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("BUSINESS_REVIEWER"))
                .andExpect(jsonPath("$.data.permissions[?(@ == 'BUSINESS_CREATE')]").exists())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'BUSINESS_APPROVE')]").exists())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'BUSINESS_SELF_APPROVE')]").doesNotExist());

        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"displayName":"业务管理员","workUnitCode":"QIQIHAR_BUSINESS",
                                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR","BUSINESS_REVIEWER"],"regionCodes":["%s"]}
                                """.formatted(TOWNSHIP)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_ROLE_ASSIGNMENT_DENIED"));
    }

    @Test
    void rejectsRegionAssignmentsOutsideTheSelectedWorkUnit() throws Exception {
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"张敏",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["not-a-region"]}
                                """.formatted(employee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"));
    }

    @Test
    void ordinaryEmployeesCanOnlyBeAnchoredAtTownshipLevel() throws Exception {
        for(String forbiddenRegion : java.util.List.of("230202",VILLAGE)) {
            mvc.perform(inviteRequest()
                            .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subjectId":"%s-%s","displayName":"层级越权探测",
                                     "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                     "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                    """.formatted(employee,forbiddenRegion,forbiddenRegion)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"));
        }
    }

    @Test
    void leafFormalBusinessCountyIsAssignableButOrdinaryCountyAndVillageRemainDenied() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,'232700')
                ON CONFLICT DO NOTHING
                """).param("unit", WORK_UNIT).update();

        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode", WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '232761')]").exists())
                .andExpect(jsonPath("$.data.regions[?(@.code == '232761' && @.administrativeLevel == 'COUNTY')]")
                        .exists());

        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"加格达奇责任员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["232761"]}
                                """.formatted(employee)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCodes[0]").value("232761"));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.security_user_region_scope
                WHERE subject_id=:subject AND region_code='232761' AND valid_until IS NULL
                """).param("subject", employee).query(Long.class).single()).isOne();
    }

    @Test
    void invitationReturnsLifecycleContractAndAdministratorsCannotBypassOidcActivation() throws Exception {
        String response = mvc.perform(inviteRequest()
                        .header("Idempotency-Key", "invite-contract-001")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"可信激活员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee, TOWNSHIP)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invitationId").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.activationUrl").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(inviteRequest()
                        .header("Idempotency-Key", "invite-contract-001")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"可信激活员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee, TOWNSHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        String activation = """
                {"version":0,"displayName":"可信激活员工","workUnitCode":"QIQIHAR_BUSINESS",
                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE",
                 "positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                """.formatted(TOWNSHIP);
        mvc.perform(put("/api/v1/identity/employees/{subjectId}", employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(activation))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_OIDC_ACTIVATION_REQUIRED"));

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("tokenHash");
    }

    @Test
    void invitationActivationBootstrapReturnsOnlyThePublicContractAndCsrfReadiness() throws Exception {
        mvc.perform(get("/api/v1/identity/invitations/activation-bootstrap")
                        .principal(() -> UNIT_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value("2026-08-30"))
                .andExpect(jsonPath("$.data.csrfReady").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.activationUrl").doesNotExist());
    }

    @Test
    void assignmentOptionsExpandWorkUnitRootsToAssignableTownshipAnchors() throws Exception {
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                .param("unit",WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,'230202')
                """).param("unit",WORK_UNIT).update();

        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCodes.length()").value(1))
                .andExpect(jsonPath("$.data.regionCodes[0]").value(TOWNSHIP));

        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"乡镇责任员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCodes[0]").value(TOWNSHIP));

        activateInvitedEmployee();

        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionScopes.length()").value(1))
                .andExpect(jsonPath("$.data.regionScopes[0].code").value(TOWNSHIP))
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '%s')]".formatted(TOWNSHIP)).exists())
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '%s')]".formatted(VILLAGE)).exists());
    }

    @Test
    void townshipAssignmentAutomaticallyCoversVillagesAndReturnsBusinessNamePath() throws Exception {
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"乡镇填报员",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated());
        activateInvitedEmployee();

        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionScopes.length()").value(1))
                .andExpect(jsonPath("$.data.regionScopes[0].code").value(TOWNSHIP))
                .andExpect(jsonPath("$.data.regionScopes[0].administrativeLevel").value("TOWNSHIP"))
                .andExpect(jsonPath("$.data.regionScopes[0].namePath")
                        .value("齐齐哈尔市 / 龙沙区 / 身份治理测试乡镇"))
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '%s')]".formatted(TOWNSHIP)).exists())
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '%s')]".formatted(VILLAGE)).exists());
    }

    @Test
    void authorizedAdministratorsCanTraceImmutableUnitAuditEventsButOperatorsCannot() throws Exception {
        String governedUnit=WORK_UNIT;
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"审计权限测试员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated());
        activateInvitedEmployee();

        long beforeAuditReads = auditReadCount();
        mvc.perform(get("/api/v1/audit-events")
                        .param("workUnitCode",governedUnit).principal(() -> employee))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
        org.assertj.core.api.Assertions.assertThat(auditReadCount()).isEqualTo(beforeAuditReads);

        mvc.perform(get("/api/v1/audit-events")
                        .param("workUnitCode",governedUnit)
                        .param("aggregateType","SECURITY_USER")
                        .param("page","0").param("pageSize","20")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.aggregateId == '%s')].actionCode"
                        .formatted(employee)).isArray())
                .andExpect(jsonPath("$.data.items[0].actorDisplayName").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].workUnitName").value("身份治理自动化测试单位"))
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(20));
        org.assertj.core.api.Assertions.assertThat(auditReadCount()).isEqualTo(beforeAuditReads + 1);
    }

    private long auditReadCount() {
        return jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE action_code='AUDIT_EVENTS_READ'
                """).query(Long.class).single();
    }

    @Test
    void reviewersCertifyEffectiveAccessAndRevocationsTakeEffectImmediately() throws Exception {
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"张敏","workUnitCode":"QIQIHAR_BUSINESS",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated());

        String created=mvc.perform(post("/api/v1/identity/access-reviews")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"区域权限季度复核","workUnitCode":"QIQIHAR_BUSINESS",
                                 "dueAt":"2026-12-31T16:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("OPEN"))
                .andExpect(jsonPath("$.data.items[?(@.subjectId == '%s' && @.grantType == 'ROLE' && @.grantKey == 'BUSINESS_OPERATOR')]"
                        .formatted(employee)).exists())
                .andReturn().getResponse().getContentAsString();
        reviewId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(created,"$.data.reviewId"));

        mvc.perform(get("/api/v1/identity/access-reviews/{reviewId}",reviewId)
                        .principal(() -> "identity-governance-outside-reviewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_WORK_UNIT_DENIED"));

        mvc.perform(get("/api/v1/identity/access-reviews")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.data[0].name").value("区域权限季度复核"))
                .andExpect(jsonPath("$.data[0].items.length()").value(3));

        mvc.perform(post("/api/v1/identity/access-reviews/{reviewId}/decisions",reviewId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decisions":[
                                  {"subjectId":"%s","grantType":"ROLE","grantKey":"BUSINESS_OPERATOR",
                                   "decisionCode":"REVOKE","reason":"岗位职责已调整"},
                                  {"subjectId":"%s","grantType":"POSITION","grantKey":"GOVERNANCE_REPORTER",
                                   "decisionCode":"RETAIN","reason":"岗位仍有效"},
                                  {"subjectId":"%s","grantType":"REGION","grantKey":"%s",
                                   "decisionCode":"RETAIN","reason":"责任区域仍有效"}
                                ]}
                                """.formatted(employee,employee,employee,TOWNSHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[?(@.grantType == 'ROLE')].decisionCode").value("REVOKE"));

        mvc.perform(get("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(0))
                .andExpect(jsonPath("$.data.positions.length()").value(1))
                .andExpect(jsonPath("$.data.regionCodes.length()").value(1));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.security_user_region_scope
                WHERE subject_id=:subject AND region_code=:region
                  AND last_reviewed_at IS NOT NULL AND review_due_at>now()
                """).param("subject",employee).param("region",TOWNSHIP).query(Long.class).single()).isOne();
    }

    @Test
    void accessReviewSnapshotExcludesTheReviewersOwnAssignments() throws Exception {
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"待复核员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated());
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                VALUES(:subject,'单位复核负责人',:unit,true)
                ON CONFLICT(subject_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    work_unit_code=EXCLUDED.work_unit_code,enabled=EXCLUDED.enabled
                """).param("subject",UNIT_ADMIN).param("unit",WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(
                    subject_id,role_code,valid_from,granted_by,granted_at,review_due_at)
                VALUES(:subject,'ACCESS_REVIEWER',now(),'production-tester',now(),now()+interval '1 year')
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_position(
                    subject_id,position_code,valid_from,primary_position,assigned_by,assigned_at)
                VALUES(:subject,'GOVERNANCE_REPORTER',now(),true,'production-tester',now())
                """).param("subject",UNIT_ADMIN).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(
                    subject_id,region_code,valid_from,granted_by,granted_at,review_due_at)
                VALUES(:subject,:region,now(),'production-tester',now(),now()+interval '1 year')
                """).param("subject",UNIT_ADMIN).param("region",TOWNSHIP).update();

        reviewId=UUID.randomUUID();
        Instant snapshotAt=Instant.now().plusSeconds(5);
        var campaign=accessReviewRepository.create(
                reviewId,"单位权限复核",WORK_UNIT,snapshotAt.plusSeconds(3600),
                UNIT_ADMIN,snapshotAt);

        org.assertj.core.api.Assertions.assertThat(campaign.items())
                .isNotEmpty()
                .allMatch(item -> item.subjectId().equals(employee));
    }

    @Test
    void overdueRoleAndRegionGrantsFailClosedUntilTheyAreReviewed() throws Exception {
        mvc.perform(inviteRequest()
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"过期授权员工",
                                 "workUnitCode":"QIQIHAR_BUSINESS","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(employee,TOWNSHIP)))
                .andExpect(status().isCreated());
        activateInvitedEmployee();

        jdbc.sql("UPDATE platform.security_user_role SET review_due_at=now()-interval '1 second' WHERE subject_id=:subject")
                .param("subject",employee).update();
        jdbc.sql("UPDATE platform.security_user_region_scope SET review_due_at=now()-interval '1 second' WHERE subject_id=:subject")
                .param("subject",employee).update();

        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCodes.length()").value(0))
                .andExpect(jsonPath("$.data.permissions.length()").value(0))
                .andExpect(jsonPath("$.data.regionCodes.length()").value(0));
        mvc.perform(get("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(0))
                .andExpect(jsonPath("$.data.regionCodes.length()").value(0));
    }

    /** Supplies the complete lifecycle contract to legacy authorization scenarios. */
    private static MockHttpServletRequestBuilder inviteRequest() {
        return post("/api/v1/identity/employees").with(request -> {
            if (request.getHeader("Idempotency-Key") == null) {
                request.addHeader("Idempotency-Key", "identity-test-" + UUID.randomUUID());
            }
            String json = new String(request.getContentAsByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!json.contains("\"deliveryAddress\"")) {
                request.setContent(("{\"deliveryAddress\":\"identity-fixture@example.test\"," +
                        json.substring(1)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return request;
        });
    }

    private void activateInvitedEmployee() throws Exception {
        String encrypted=jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE security_subject_id=:subject AND state='PENDING'
                """).param("subject",employee).query(String.class).single();
        String token=invitationTokens.decryptDeliveryPayload(encrypted).token();
        mvc.perform(post("/api/v1/identity/invitations/activate")
                        .with(oidcLogin().idToken(idToken -> idToken
                                .claim(IdTokenClaimNames.ISS,"https://idp.example.test/realms/cofco")
                                .claim(IdTokenClaimNames.SUB,"provider-"+employee)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\""+token+"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectId").value(employee))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"));
    }
}
