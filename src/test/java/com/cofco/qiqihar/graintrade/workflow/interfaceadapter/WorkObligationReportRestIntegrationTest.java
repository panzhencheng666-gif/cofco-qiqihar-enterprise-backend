package com.cofco.qiqihar.graintrade.workflow.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class WorkObligationReportRestIntegrationTest {
    private static final String UNIT = "OBLIGATION_REPORT_UNIT";
    private static final String OPERATOR = "obligation-report-operator";
    private static final String COLLEAGUE = "obligation-report-colleague";
    private static final String REVIEWER = "obligation-report-reviewer";
    private static final List<String> SOURCE_IDS = List.of(
            "obligation-on-time", "obligation-overdue", "obligation-colleague");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void setUp() {
        cleanMutableFixtures();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                SELECT :unit,'履职周报测试单位',COALESCE(max(sort_order),0)+1 FROM platform.work_unit
                ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name
                """).param("unit", UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(
                    subject_id,display_name,work_unit_code,enabled,account_status,employment_status)
                VALUES (:operator,'填报员工甲',:unit,true,'ACTIVE','ACTIVE'),
                       (:colleague,'填报员工乙',:unit,true,'ACTIVE','ACTIVE'),
                       (:reviewer,'业务主管',:unit,true,'ACTIVE','ACTIVE')
                ON CONFLICT(subject_id) DO UPDATE SET
                    display_name=EXCLUDED.display_name,work_unit_code=EXCLUDED.work_unit_code,
                    enabled=true,account_status='ACTIVE',employment_status='ACTIVE'
                """).param("operator", OPERATOR).param("colleague", COLLEAGUE)
                .param("reviewer", REVIEWER).param("unit", UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code,valid_from)
                VALUES (:operator,'BUSINESS_OPERATOR','2026-01-01T00:00:00+08:00'),
                       (:colleague,'BUSINESS_OPERATOR','2026-01-01T00:00:00+08:00'),
                       (:reviewer,'BUSINESS_REVIEWER','2026-01-01T00:00:00+08:00')
                ON CONFLICT DO NOTHING
                """).param("operator", OPERATOR).param("colleague", COLLEAGUE)
                .param("reviewer", REVIEWER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from)
                VALUES (:operator,'230202','2026-01-01T00:00:00+08:00'),
                       (:colleague,'230202','2026-01-01T00:00:00+08:00'),
                       (:reviewer,'230202','2026-01-01T00:00:00+08:00')
                ON CONFLICT DO NOTHING
                """).param("operator", OPERATOR).param("colleague", COLLEAGUE)
                .param("reviewer", REVIEWER).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES (:unit,'230202') ON CONFLICT DO NOTHING
                """).param("unit", UNIT).update();
        jdbc.sql("""
                INSERT INTO workflow.workflow_node(code,label)
                VALUES ('OBLIGATION_REPORT_FILL','填报'),('OBLIGATION_REPORT_COMPLETE','已完成')
                ON CONFLICT(code) DO UPDATE SET label=EXCLUDED.label
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.responsible_party(party_type,external_code,display_name)
                VALUES ('USER',:operator,'填报员工甲'),('USER',:colleague,'填报员工乙')
                ON CONFLICT(party_type,external_code) DO UPDATE SET display_name=EXCLUDED.display_name
                """).param("operator", OPERATOR).param("colleague", COLLEAGUE).update();
        insertItem("obligation-on-time", OPERATOR, "2026-08-09T18:00:00+08:00",
                "2026-08-08T18:00:00+08:00", null);
        insertItem("obligation-overdue", OPERATOR, "2026-08-07T18:00:00+08:00",
                null, "TO_FILL");
        insertItem("obligation-colleague", COLLEAGUE, "2026-08-08T18:00:00+08:00",
                null, "TO_FILL");
    }

    @AfterEach
    void tearDown() {
        cleanMutableFixtures();
    }

    @Test
    void enforcesEmployeeAndUnitScopeAndExportsAnAuditedBusinessOnlyWorkbook() throws Exception {
        mockMvc.perform(get("/api/v1/work-obligation-reports/weekly")
                        .queryParam("weekStart", "2026-08-03")
                        .principal(() -> OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeLabel").value("填报员工甲"))
                .andExpect(jsonPath("$.data.summary.total").value(2))
                .andExpect(jsonPath("$.data.summary.onTime").value(1))
                .andExpect(jsonPath("$.data.summary.overdueOutstanding").value(1))
                .andExpect(jsonPath("$.data.rows[0].employeeName").value("填报员工甲"));

        mockMvc.perform(get("/api/v1/work-obligation-reports/weekly")
                        .queryParam("weekStart", "2026-08-03")
                        .queryParam("subjectId", COLLEAGUE)
                        .principal(() -> OPERATOR))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/work-obligation-reports/weekly")
                        .queryParam("weekStart", "2026-08-03")
                        .queryParam("subjectId", OPERATOR)
                        .principal(() -> REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.total").value(2));

        String response = mockMvc.perform(post("/api/v1/work-obligation-reports/weekly/exports")
                        .contentType("application/json")
                        .content("""
                                {"weekStart":"2026-08-03","subjectId":"%s"}
                                """.formatted(OPERATOR))
                        .principal(() -> REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value(
                        "填报履职周报-2026-08-03-填报员工甲.xlsx"))
                .andExpect(jsonPath("$.data.checksum").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String exportId = json.readTree(response).path("data").path("id").asText();

        byte[] workbook = mockMvc.perform(get(
                                "/api/v1/work-obligation-reports/exports/{id}/content", exportId)
                        .principal(() -> REVIEWER))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        String workbookText = workbookText(workbook);
        assertThat(workbookText).contains("填报履职周报", "填报员工甲", "按时完成", "已逾期未完成");
        assertThat(workbookText).doesNotContain(
                "AI", "人工智能", "模型", "提示词", "API", "数据库", "本地模式", "版本号");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM workflow.obligation_report_export
                WHERE export_id=CAST(:id AS uuid) AND generated_by=:reviewer
                """).param("id", exportId).param("reviewer", REVIEWER).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='WORK_OBLIGATION_REPORT' AND aggregate_id=:id
                  AND action_code IN ('WORK_OBLIGATION_REPORT_EXPORTED','WORK_OBLIGATION_REPORT_DOWNLOADED')
                """).param("id", exportId).query(Long.class).single()).isEqualTo(2L);
    }

    private void insertItem(
            String sourceId, String owner, String dueAt, String completedAt, String statusCode) {
        jdbc.sql("""
                INSERT INTO workflow.work_item(
                    task_name,business_domain,region_code,product_code,business_period_code,due_at,
                    workflow_node_id,status_code,responsible_party_id,completed_at,source_type,source_id,
                    owner_subject_id,owner_work_unit_code)
                SELECT '履职周报测试单据','PRODUCTION','230202','CORN',period.code,:dueAt,
                    (SELECT node_id FROM workflow.workflow_node
                     WHERE code=CASE WHEN CAST(:completedAt AS timestamptz) IS NULL
                         THEN 'OBLIGATION_REPORT_FILL' ELSE 'OBLIGATION_REPORT_COMPLETE' END),
                    :statusCode,
                    (SELECT responsible_party_id FROM workflow.responsible_party
                     WHERE party_type='USER' AND external_code=:owner),
                    :completedAt,'PRODUCTION',:sourceId,:owner,:unit
                FROM platform.business_period period
                WHERE period.starts_on<=DATE '2026-08-08' AND period.ends_on>=DATE '2026-08-08'
                ORDER BY period.sort_order DESC LIMIT 1
                """)
                .param("sourceId", sourceId).param("owner", owner).param("unit", UNIT)
                .param("dueAt", OffsetDateTime.parse(dueAt))
                .param("completedAt", completedAt == null ? null : OffsetDateTime.parse(completedAt),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("statusCode", statusCode, java.sql.Types.VARCHAR)
                .update();
    }

    private void cleanMutableFixtures() {
        jdbc.sql("DELETE FROM workflow.obligation_report_export WHERE generated_by=:reviewer")
                .param("reviewer", REVIEWER).update();
        jdbc.sql("DELETE FROM workflow.work_item WHERE source_type='PRODUCTION' AND source_id IN (:ids)")
                .param("ids", SOURCE_IDS).update();
    }

    private static String workbookText(byte[] workbook) throws Exception {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                text.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }
}
