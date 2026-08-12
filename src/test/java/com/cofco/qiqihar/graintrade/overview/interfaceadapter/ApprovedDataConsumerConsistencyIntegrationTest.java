package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
class ApprovedDataConsumerConsistencyIntegrationTest {
    private static final String ACTOR = "production-tester";
    private static final String APPROVED = "99000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUpOneApprovedSnapshotAndOneExcludedDraft() {
        jdbc = JdbcClient.create(dataSource);
        clearSnapshot();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        jdbc.sql("""
                INSERT INTO platform.business_period(
                  code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_period_governance_state)
                VALUES(:approved,'CORN','FARMER','230208',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',10,20,'APPROVED',:actor,'CONFIRMED'),
                  ('99000000-0000-0000-0000-000000000002','CORN','FARMER','230208',
                    DATE '2026-08-09',TIMESTAMPTZ '2026-08-09 13:00:00+08',999,999,'DRAFT',:actor,'CONFIRMED')
                """).param("approved", APPROVED).param("actor", ACTOR).update();
    }

    @AfterEach
    void tearDownApprovedSnapshot() {
        clearSnapshot();
    }

    private void clearSnapshot() {
        jdbc.sql("""
                TRUNCATE reporting.report_audit_event,reporting.report_publication,
                  reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,
                  platform.business_audit_event,platform.business_event_outbox,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void listDetailAnalysisOverviewAndExportUseTheSameApprovedSnapshot() throws Exception {
        mvc.perform(get("/api/v1/production-records").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.status", "APPROVED").queryParam("filter.regionCode", "230208"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(APPROVED))
                .andExpect(jsonPath("$.data.items[0].values.PROD_AREA_MU").value("10.0000"));
        mvc.perform(get("/api/v1/production-records/{id}", APPROVED).principal(() -> ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.cultivatedAreaMu").value("10.0000"));

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("surveyYear", "2026")
                        .queryParam("indicatorCode", "PRODUCTION_CULTIVATED_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].businessYear").value("2026"))
                .andExpect(jsonPath("$.data.points[0].value").value(10.0))
                .andExpect(jsonPath("$.data.points[0].sourcePublicationVersion")
                        .value("APPROVED_PRODUCTION_RECORD:v0"));
        mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("10"))
                .andExpect(jsonPath("$.data[0].sourceCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"COUNTY\",\"regionCode\":\"230208\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '精确数据截止')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export).principal(() -> ACTOR))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"核定数据条数\",\"1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026年08月09日 12:34:56")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("production.production_record"))));
    }
}
