package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
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
class ReportingRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE reporting.report_audit_event,reporting.report_publication,reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,production.production_record RESTART IDENTITY CASCADE").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603)
                ON CONFLICT(code) DO NOTHING
                """).update();
    }

    @Test void requiresApprovedDataThenPreviewsExportsAndPublishes() throws Exception {
        String body = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\",\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',current_date,now(),100,20,'APPROVED','report-test')""").param("id", UUID.randomUUID().toString()).update();
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].value").value("1")).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports",preview).principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(post("/api/v1/reports/previews/{id}/publications",preview).principal(() -> "publisher").contentType(MediaType.APPLICATION_JSON).content("{\"exportTaskId\":\""+export+"\",\"expectedVersion\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.previewId").value(preview));
    }
}
