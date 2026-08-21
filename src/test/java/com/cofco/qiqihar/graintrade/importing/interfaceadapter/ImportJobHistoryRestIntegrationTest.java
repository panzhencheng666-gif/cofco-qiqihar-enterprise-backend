package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.Instant;
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
class ImportJobHistoryRestIntegrationTest {
    private static final String SUBJECT = "production-tester";
    private static final String OTHER_SUBJECT = "market-tester";
    private static final String OTHER_UNIT = "IMPORT_HISTORY_OTHER_UNIT";
    private static final String NO_PERMISSION_SUBJECT = "import-history-no-permission";
    private static final UUID NEWEST = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID MIDDLE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MIDDLE_RETRY = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID OLDEST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MARKET = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID LOGISTICS = UUID.fromString("20000000-0000-0000-0000-000000000005");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private String currentUnit;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job RESTART IDENTITY CASCADE").update();
        currentUnit = jdbc.sql("SELECT work_unit_code FROM platform.security_user WHERE subject_id=:subject")
                .param("subject", SUBJECT).query(String.class).single();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:code,'导入历史隔离测试单位',9988) ON CONFLICT(code) DO NOTHING
                """).param("code", OTHER_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:subject,'无导入权限测试账号',:workUnit)
                ON CONFLICT(subject_id) DO UPDATE SET work_unit_code=EXCLUDED.work_unit_code
                """).param("subject", NO_PERMISSION_SUBJECT).param("workUnit", currentUnit).update();

        insertCompleted(OLDEST, SUBJECT, currentUnit, "PRODUCTION", "ordinary-oldest",
                "GOVERNED-DRAFT-V1:oldest", Instant.parse("2026-08-20T00:00:01Z"));
        insertCompleted(MIDDLE, SUBJECT, currentUnit, "PRODUCTION", "ordinary-middle",
                "GOVERNED-DRAFT-V1:middle", Instant.parse("2026-08-20T00:00:02Z"));
        insertCompleted(NEWEST, SUBJECT, currentUnit, "PRODUCTION", "ordinary-newest",
                "GOVERNED-DRAFT-V1:eyJwcm9kdWN0Q29kZSI6IkNPUk4ifQ==",
                Instant.parse("2026-08-20T00:00:03Z"));
        insertRow(NEWEST, 2, "ERROR", "IMPORT_REGION_NOT_FOUND", "所在地区无法唯一识别", null,
                "{\"surveyYear\":\"2025\",\"surveyMonth\":\"9\"}");
        insertRow(MIDDLE, 2, "IMPORTED", null, null, "record-1");
        insertRow(MIDDLE, 3, "ERROR", "INVALID_IMPORT_ROW", "本行填写内容无效", null);
        insertCompleted(MIDDLE_RETRY, SUBJECT, currentUnit, "PRODUCTION", "ordinary-middle-retry",
                "GOVERNED-DRAFT-V1:middle-retry", Instant.parse("2026-08-20T00:00:04Z"), MIDDLE);
        insertRow(MIDDLE_RETRY, 3, "IMPORTED", null, null, "record-2");

        insertCompleted(UUID.fromString("20000000-0000-0000-0000-000000000001"), OTHER_SUBJECT,
                currentUnit, "PRODUCTION", "other-subject", "GOVERNED-DRAFT-V1:other-subject",
                Instant.parse("2026-08-20T00:00:10Z"));
        insertCompleted(UUID.fromString("20000000-0000-0000-0000-000000000002"), SUBJECT,
                OTHER_UNIT, "PRODUCTION", "other-unit", "GOVERNED-DRAFT-V1:other-unit",
                Instant.parse("2026-08-20T00:00:11Z"));
        insertCompleted(MARKET, SUBJECT,
                currentUnit, "MARKET", "other-domain", "GOVERNED-DRAFT-V1:other-domain",
                Instant.parse("2026-08-20T00:00:12Z"));
        insertCompleted(UUID.fromString("20000000-0000-0000-0000-000000000004"), SUBJECT,
                currentUnit, "PRODUCTION", "returned-correction",
                "PRODUCTION-RETURNED-CORRECTION-V1:fixture", Instant.parse("2026-08-20T00:00:13Z"));
        insertCompleted(LOGISTICS, SUBJECT, currentUnit, "LOGISTICS", "ordinary-logistics",
                "GOVERNED-DRAFT-V1:ordinary-logistics", Instant.parse("2026-08-20T00:00:14Z"));
        insertCompleted(UUID.fromString("20000000-0000-0000-0000-000000000006"), SUBJECT,
                currentUnit, "LOGISTICS", "returned-logistics-correction",
                "LOGISTICS-RETURNED-CORRECTION-V1:fixture", Instant.parse("2026-08-20T00:00:15Z"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject", NO_PERMISSION_SUBJECT).update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code=:code").param("code", OTHER_UNIT).update();
    }

    @Test
    void listsOnlyOrdinaryJobsOwnedByTheCurrentSubjectUnitAndDomainWithStablePagination() throws Exception {
        mvc.perform(get("/api/v1/imports/production")
                        .param("pageNumber", "0").param("pageSize", "2")
                        .principal(() -> SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(MIDDLE.toString()))
                .andExpect(jsonPath("$.data.items[0].actionJobId").value(MIDDLE_RETRY.toString()))
                .andExpect(jsonPath("$.data.items[0].importedRows").value(2))
                .andExpect(jsonPath("$.data.items[0].failedRows").value(0))
                .andExpect(jsonPath("$.data.items[1].id").value(NEWEST.toString()))
                .andExpect(jsonPath("$.data.items[1].productCodes[0]").value("CORN"))
                .andExpect(jsonPath("$.data.items[1].surveyPeriods[0]").value("2025-09"));

        mvc.perform(get("/api/v1/imports/production")
                        .param("pageNumber", "1").param("pageSize", "2")
                        .principal(() -> SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(OLDEST.toString()));
    }

    @Test
    void appliesTheSameHistoryContractToMarketAndLogistics() throws Exception {
        mvc.perform(get("/api/v1/imports/market").principal(() -> SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(MARKET.toString()))
                .andExpect(jsonPath("$.data.items[0].domainCode").value("MARKET"));

        mvc.perform(get("/api/v1/imports/logistics").principal(() -> SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(LOGISTICS.toString()))
                .andExpect(jsonPath("$.data.items[0].domainCode").value("LOGISTICS"));
    }

    @Test
    void failsClosedWithoutAuthenticationOrBusinessImportPermission() throws Exception {
        mvc.perform(get("/api/v1/imports/production"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mvc.perform(get("/api/v1/imports/production")
                        .principal(() -> NO_PERMISSION_SUBJECT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
    }

    @Test
    void rejectsPaginationOutsideTheBoundedContract() throws Exception {
        mvc.perform(get("/api/v1/imports/production")
                        .param("pageNumber", "-1").param("pageSize", "10")
                        .principal(() -> SUBJECT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IMPORT_JOB_HISTORY_QUERY"));

        mvc.perform(get("/api/v1/imports/production")
                        .param("pageNumber", "0").param("pageSize", "51")
                        .principal(() -> SUBJECT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IMPORT_JOB_HISTORY_QUERY"));
    }

    private void insertCompleted(UUID id, String subject, String workUnit, String domain,
            String idempotencyKey, String sourceContent, Instant createdAt) {
        insertCompleted(id, subject, workUnit, domain, idempotencyKey, sourceContent, createdAt, null);
    }

    private void insertCompleted(UUID id, String subject, String workUnit, String domain,
            String idempotencyKey, String sourceContent, Instant createdAt, UUID retryOf) {
        jdbc.sql("""
                INSERT INTO platform.import_job(
                  import_job_id,domain_code,idempotency_key,content_sha256,source_content,
                  requested_by,work_unit_code,retry_of_import_job_id,status_code,created_at,completed_at)
                VALUES(CAST(:id AS uuid),:domain,:key,repeat('a',64),:source,
                  :subject,:workUnit,CAST(:retryOf AS uuid),'COMPLETED',:createdAt,:completedAt)
                """).param("id", id.toString()).param("domain", domain).param("key", idempotencyKey)
                .param("source", sourceContent).param("subject", subject).param("workUnit", workUnit)
                .param("retryOf", retryOf == null ? null : retryOf.toString())
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .param("completedAt", java.sql.Timestamp.from(createdAt.plusSeconds(1))).update();
    }

    private void insertRow(UUID jobId, int rowNumber, String outcome, String errorCode,
            String errorMessage, String recordId) {
        insertRow(jobId, rowNumber, outcome, errorCode, errorMessage, recordId, "{}");
    }

    private void insertRow(UUID jobId, int rowNumber, String outcome, String errorCode,
            String errorMessage, String recordId, String rowData) {
        jdbc.sql("""
                INSERT INTO platform.import_row_result(
                  import_job_id,row_number,outcome_code,error_code,error_message,business_record_id,row_data)
                VALUES(CAST(:jobId AS uuid),:rowNumber,:outcome,:errorCode,:errorMessage,:recordId,CAST(:rowData AS jsonb))
                """).param("jobId", jobId.toString()).param("rowNumber", rowNumber)
                .param("outcome", outcome).param("errorCode", errorCode)
                .param("errorMessage", errorMessage).param("recordId", recordId)
                .param("rowData", rowData).update();
    }
}
