package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Transactional
@Rollback
class ProductionObjectRestIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void provisionScopedActors() {
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('PRODUCTION_OBJECT_LIMITED','产情对象受限测试单位',9951)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('PRODUCTION_OBJECT_LIMITED','230202') ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES ('production-object-limited','受限产情维护员','PRODUCTION_OBJECT_LIMITED'),
                       ('production-object-reader','产情对象只读员','TEST')
                ON CONFLICT(subject_id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES ('production-object-limited','BUSINESS_OPERATOR'),
                       ('production-object-reader','REPORTER')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES ('production-object-limited','230202'),
                       ('production-object-reader','230281')
                ON CONFLICT DO NOTHING
                """).update();
    }

    @Test
    void createsRequeriesUpdatesAndAuditsAProductionSurveyObject() throws Exception {
        String id = create("讷河市权威调查对象");

        mockMvc.perform(get("/api/v1/production-objects").principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].objectId").value(id))
                .andExpect(jsonPath("$.data[0].objectName").value("讷河市权威调查对象"))
                .andExpect(jsonPath("$.data[0].objectTypeId").value("village-committee"))
                .andExpect(jsonPath("$.data[0].regionCode").value("230281"))
                .andExpect(jsonPath("$.data[0].responsibleUserId").value("production-tester"))
                .andExpect(jsonPath("$.data[0].responsiblePerson").value("产情测试员"))
                .andExpect(jsonPath("$.data[0].version").value(0));

        mockMvc.perform(put("/api/v1/production-objects/{id}", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("讷河市更新后调查对象", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectName").value("讷河市更新后调查对象"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/production-objects/{id}", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("禁止过期覆盖", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCTION_OBJECT_VERSION_CONFLICT"));

        long revisions = jdbc.sql("""
                SELECT count(*) FROM production.monitoring_object_revision
                WHERE object_id=CAST(:id AS uuid)
                """).param("id", id).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(revisions).isEqualTo(2);
        long auditEvents = jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='PRODUCTION_OBJECT' AND aggregate_id=:id
                  AND action_code IN ('PRODUCTION_OBJECT_CREATED','PRODUCTION_OBJECT_UPDATED')
                """).param("id", id).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(auditEvents).isEqualTo(2);
    }

    @Test
    void rejectsUnauthenticatedInvalidAndDuplicateCreatesWithoutWriting() throws Exception {
        mockMvc.perform(post("/api/v1/production-objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/production-objects")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("非法调查对象", null)
                                .replace("\"productIds\":[\"corn\"]", "\"productIds\":[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_OBJECT"));

        create("重复调查对象");
        mockMvc.perform(post("/api/v1/production-objects")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("重复调查对象", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCTION_OBJECT_CONFLICT"));

        long duplicateCount = jdbc.sql("""
                SELECT count(*) FROM production.monitoring_object
                WHERE region_code='230281' AND lower(btrim(object_name))=lower(btrim('重复调查对象'))
                """).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(duplicateCount).isOne();
    }

    @Test
    void enforcesPermissionAndResponsibilityRegionOnWritesAndReads() throws Exception {
        create("讷河市责任区对象");

        mockMvc.perform(post("/api/v1/production-objects")
                        .principal(() -> "production-object-limited")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("越区新增对象", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mockMvc.perform(post("/api/v1/production-objects")
                        .principal(() -> "production-object-reader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("只读越权对象", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        mockMvc.perform(get("/api/v1/production-objects")
                        .principal(() -> "production-object-limited"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/production-objects")
                        .principal(() -> "production-object-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].objectName").value("讷河市责任区对象"));
    }

    private String create(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/production-objects")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"objectId\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static String body(String name, Integer version) {
        String versionField = version == null ? "" : ",\"version\":" + version;
        return """
                {
                  "objectName":"%s",
                  "objectTypeId":"village-committee",
                  "regionCode":"230281",
                  "productIds":["corn"],
                  "cultivarIds":[],
                  "sourceChannelId":"administrative-village-ledger",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":null,
                  "validityStatus":"active",
                  "roles":[
                    {"roleId":"production-survey","label":"产情调查对象",
                     "effectiveFrom":"2026-08-01","effectiveTo":null,
                     "capabilityTemplateVersionId":"CAPABILITY-PRODUCTION-FULL-2"}
                  ]%s
                }
                """.formatted(name, versionField);
    }
}
