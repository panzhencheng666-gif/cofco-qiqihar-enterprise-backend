package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class, properties = "qiqihar.regional-public-data.enabled=false")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ActivityReportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void activityFixture() {
        user("activity-personal", "周期报告个人", true, "ACTIVE", "ACTIVE");
        user("activity-colleague", "周期报告同事", true, "ACTIVE", "ACTIVE");
        user("activity-disabled", "停用账号", false, "SUSPENDED", "ACTIVE");
        user("activity-departed", "离职账号", false, "REVOKED", "TERMINATED");
        event("10000000-0000-0000-0000-000000000001", "FORMAL_SAMPLE_POINT",
                "point-personal", "FORMAL_SAMPLE_POINT_CREATED", "activity-personal");
        event("10000000-0000-0000-0000-000000000002", "FORMAL_SAMPLE_POINT",
                "point-colleague", "FORMAL_SAMPLE_POINT_DELETED", "activity-colleague");
        event("10000000-0000-0000-0000-000000000003", "PRODUCTION_RECORD",
                "record-disabled", "PRODUCTION_RECORD_UPDATED", "activity-disabled");
        event("10000000-0000-0000-0000-000000000004", "MARKET_RECORD",
                "record-departed", "MARKET_RECORD_CREATED", "activity-departed");
    }

    @Test
    void keepsPersonalActivityPrivateAndSystemScopeLimitedToEffectiveUsers() throws Exception {
        mvc.perform(get("/api/v1/activity-reports/personal")
                        .principal(() -> "activity-personal").queryParam("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data.subject.subjectId").value("activity-personal"))
                .andExpect(jsonPath("$.data.totalEvents").value(1))
                .andExpect(jsonPath("$.data.samplePointsCreated").value(1))
                .andExpect(jsonPath("$.data.samplePointsDeleted").value(0));

        mvc.perform(get("/api/v1/activity-reports/system")
                        .principal(() -> "production-tester").queryParam("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("SYSTEM"))
                .andExpect(jsonPath("$.data.effectiveUserCount").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.samplePointsCreated").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.samplePointsDeleted").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void validatesPeriodAndExportsAStoredDocxWithTraceableMetadata() throws Exception {
        mvc.perform(get("/api/v1/activity-reports/personal")
                        .principal(() -> "activity-personal").queryParam("days", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ACTIVITY_REPORT_REQUEST"));

        String response = mvc.perform(post("/api/v1/activity-reports/system/exports")
                        .principal(() -> "production-tester").queryParam("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sha256").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String exportId = json.readTree(response).path("data").path("id").asText();

        mvc.perform(get("/api/v1/activity-reports/system/exports/{id}/content", exportId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".docx")))
                .andExpect(content().bytes(jdbc.sql("""
                        SELECT content_bytes FROM reporting.activity_report_export
                        WHERE export_id=CAST(:id AS uuid)
                        """).param("id", exportId).query(byte[].class).single()));
    }

    private void user(String subject, String name, boolean enabled,
            String accountStatus, String employmentStatus) {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled,
                  account_status,employment_status)
                VALUES (:subject,:name,'TEST',:enabled,:accountStatus,:employmentStatus)
                ON CONFLICT (subject_id) DO NOTHING
                """).param("subject", subject).param("name", name).param("enabled", enabled)
                .param("accountStatus", accountStatus).param("employmentStatus", employmentStatus).update();
    }

    private void event(String id, String aggregateType, String aggregateId,
            String action, String actor) {
        jdbc.sql("""
                INSERT INTO platform.business_audit_event(event_id,aggregate_type,aggregate_id,
                  action_code,actor_subject_id,work_unit_code,occurred_at,detail)
                VALUES (CAST(:id AS uuid),:aggregateType,:aggregateId,:action,:actor,'TEST',:occurredAt,'{}')
                ON CONFLICT (event_id) DO NOTHING
                """).param("id", id).param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId).param("action", action).param("actor", actor)
                .param("occurredAt", Timestamp.from(Instant.now().minusSeconds(60))).update();
    }
}
