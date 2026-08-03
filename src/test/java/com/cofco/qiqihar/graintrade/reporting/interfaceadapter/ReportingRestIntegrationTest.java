package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
        jdbc.sql("TRUNCATE platform.business_audit_event,reporting.report_audit_event,reporting.report_publication,reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,production.production_record RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope").update();
        jdbc.sql("DELETE FROM platform.security_user_role").update();
        jdbc.sql("DELETE FROM platform.security_user").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope").update();
        jdbc.sql("DELETE FROM platform.work_unit").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('QI','齐齐哈尔工作单位',9001),('HEI','黑河工作单位',9002)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('QI','230200'),('QI','230202'),('HEI','231100')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES ('reporter','报表专员','QI'),('publisher','报表发布员','QI'),
                       ('limited-reporter','区县报表专员','QI'),('outside-unit-reporter','外单位报表专员','HEI')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES ('reporter','REPORTER'),('publisher','REPORT_PUBLISHER'),
                       ('limited-reporter','REPORTER'),('outside-unit-reporter','REPORTER')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES ('reporter','230200'),('publisher','230200'),
                       ('limited-reporter','230202'),('outside-unit-reporter','231100')
                """).update();
    }

    @AfterEach void cleanAfterEach() {
        clean();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test void requiresApprovedDataThenPreviewsExportsAndPublishes() throws Exception {
        String body = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\",\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "limited-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "outside-unit-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',current_date,now(),100,20,'APPROVED','report-test')""").param("id", UUID.randomUUID().toString()).update();
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].value").value("1")).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports",preview).principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export).principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("报告名称")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("核定数据条数")));
        mvc.perform(post("/api/v1/reports/previews/{id}/publications",preview).principal(() -> "publisher").contentType(MediaType.APPLICATION_JSON).content("{\"exportTaskId\":\""+export+"\",\"expectedVersion\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.previewId").value(preview));
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.report_audit_event").query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isEqualTo(4L);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform.business_audit_event").update())
                .hasMessageContaining("business audit events are immutable");
    }
}
