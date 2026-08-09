package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
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
import java.util.UUID;

@SpringBootTest(classes=GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class IdentityGovernanceRestIntegrationTest {
    private static final String WORK_UNIT="IDENTITY_GOV_TEST";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private String employee;
    private UUID reviewId;

    @BeforeEach
    void prepare() {
        jdbc=JdbcClient.create(dataSource);
        employee="identity-governance-"+UUID.randomUUID();
        deleteAssignments("identity-governance-employee");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-employee'").update();
        deleteAssignments("identity-governance-outside-reviewer");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-outside-reviewer'").update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('IDENTITY_GOV_TEST','身份治理自动化测试单位',9981)
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
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('IDENTITY_GOV_TEST','230202') ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.position(code,name,sort_order)
                VALUES('GOVERNANCE_REPORTER','区域填报专员',9980)
                ON CONFLICT(code) DO NOTHING
                """).update();
    }

    @AfterEach
    void cleanup() {
        if(jdbc==null)return;
        if(reviewId!=null)jdbc.sql("DELETE FROM platform.access_review_campaign WHERE review_id=:review")
                .param("review",reviewId).update();
        if(employee!=null){
            deleteAssignments(employee);
            jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:employee")
                    .param("employee",employee).update();
        }
        deleteAssignments("identity-governance-outside-reviewer");
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id='identity-governance-outside-reviewer'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='IDENTITY_GOV_OUTSIDE'").update();
        jdbc.sql("DELETE FROM platform.position WHERE code='GOVERNANCE_REPORTER'").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                .param("unit",WORK_UNIT).update();
        // Immutable audit events retain their governed work-unit foreign key. Keep the stable
        // test fixture and recreate its mutable scope in prepare() instead of deleting history.
    }

    private void deleteAssignments(String subjectId) {
        jdbc.sql("DELETE FROM platform.security_user_position WHERE subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject",subjectId).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject",subjectId).update();
    }

    @Test
    void administratorsInviteActivateGovernAndTerminateEmployeesWithAuditAndCas() throws Exception {
        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode",WORK_UNIT).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'IDENTITY_GOV_TEST')]").exists())
                .andExpect(jsonPath("$.data.roles[?(@.code == 'BUSINESS_OPERATOR')]").exists())
                .andExpect(jsonPath("$.data.positions[?(@.code == 'GOVERNANCE_REPORTER')]").exists())
                .andExpect(jsonPath("$.data.regionCodes[0]").value("230202"));

        String invitation="""
                {"subjectId":"%s","displayName":"张敏",
                 "workUnitCode":"IDENTITY_GOV_TEST","positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                """.formatted(employee);
        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(invitation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subjectId").value(employee))
                .andExpect(jsonPath("$.data.accountStatus").value("INVITED"))
                .andExpect(jsonPath("$.data.roles[0].code").value("BUSINESS_OPERATOR"))
                .andExpect(jsonPath("$.data.positions[0].code").value("GOVERNANCE_REPORTER"))
                .andExpect(jsonPath("$.data.regionCodes[0]").value("230202"))
                .andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get("/api/v1/session/me").principal(() -> employee))
                .andExpect(status().isForbidden());

        String activation="""
                {"version":0,"displayName":"张敏","workUnitCode":"IDENTITY_GOV_TEST",
                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE",
                 "positionCodes":["GOVERNANCE_REPORTER"],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                """;
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(activation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(1));
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
    void rejectsRegionAssignmentsOutsideTheSelectedWorkUnit() throws Exception {
        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"张敏",
                                 "workUnitCode":"IDENTITY_GOV_TEST","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["not-a-region"]}
                                """.formatted(employee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDENTITY_ASSIGNMENT"));
    }

    @Test
    void authorizedAdministratorsCanTraceImmutableUnitAuditEventsButOperatorsCannot() throws Exception {
        String governedUnit=WORK_UNIT;
        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"审计权限测试员工",
                                 "workUnitCode":"IDENTITY_GOV_TEST","positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"displayName":"审计权限测试员工","workUnitCode":"IDENTITY_GOV_TEST",
                                 "accountStatus":"ACTIVE","employmentStatus":"ACTIVE",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit-events")
                        .param("workUnitCode",governedUnit).principal(() -> employee))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        mvc.perform(get("/api/v1/audit-events")
                        .param("workUnitCode",governedUnit)
                        .param("aggregateType","SECURITY_USER")
                        .param("page","0").param("pageSize","20")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.aggregateId == '%s')].actionCode"
                        .formatted(employee)).isArray())
                .andExpect(jsonPath("$.data.items[0].actorDisplayName").isNotEmpty())
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(20));
    }

    @Test
    void reviewersCertifyEffectiveAccessAndRevocationsTakeEffectImmediately() throws Exception {
        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","displayName":"张敏","workUnitCode":"IDENTITY_GOV_TEST",
                                 "positionCodes":["GOVERNANCE_REPORTER"],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230202"]}
                                """.formatted(employee)))
                .andExpect(status().isCreated());

        String created=mvc.perform(post("/api/v1/identity/access-reviews")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"区域权限季度复核","workUnitCode":"IDENTITY_GOV_TEST",
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
                                  {"subjectId":"%s","grantType":"REGION","grantKey":"230202",
                                   "decisionCode":"RETAIN","reason":"责任区域仍有效"}
                                ]}
                                """.formatted(employee,employee,employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[?(@.grantType == 'ROLE')].decisionCode").value("REVOKE"));

        mvc.perform(get("/api/v1/identity/employees/{subjectId}",employee)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(0))
                .andExpect(jsonPath("$.data.positions.length()").value(1))
                .andExpect(jsonPath("$.data.regionCodes.length()").value(1));
    }
}
