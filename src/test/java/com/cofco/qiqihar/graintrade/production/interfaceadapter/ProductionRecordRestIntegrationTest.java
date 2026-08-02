package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionRecordRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void insertFactDefinitions() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO platform.production_fact_definition
                    (code, category, label, value_type, decimal_precision, decimal_scale)
                VALUES ('QUALITY_TEST', 'QUALITY', '测试质量', 'DECIMAL', 18, 4),
                       ('COST_TEST', 'COST', '测试成本', 'DECIMAL', 18, 4),
                       ('INSURANCE_TEST', 'INSURANCE', '测试保险', 'DECIMAL', 18, 4),
                       ('SUBSIDY_TEST', 'SUBSIDY', '测试补贴', 'DECIMAL', 18, 4)
                ON CONFLICT (code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.production_fact_applicability
                    (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
                SELECT definition.code, product.code, NULL, 'PRODUCTION', 'MONITORING',
                       CASE definition.category WHEN 'QUALITY' THEN 110 WHEN 'COST' THEN 120
                            WHEN 'INSURANCE' THEN 130 ELSE 140 END
                FROM platform.production_fact_definition definition CROSS JOIN platform.product product
                WHERE definition.code IN ('QUALITY_TEST', 'COST_TEST', 'INSURANCE_TEST', 'SUBSIDY_TEST')
                ON CONFLICT DO NOTHING
                """).update();
    }

    @AfterEach
    void removeTestRecords() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM production.production_record WHERE last_modified_by = 'production-tester'").update();
        jdbc.sql("DELETE FROM platform.production_fact_definition WHERE code LIKE '%_TEST'").update();
    }

    @Test
    void rejectsUnknownQueryParametersAndRequiresAnAuthenticatedPrincipalForWrites() throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("unrecognized", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));

        mockMvc.perform(post("/api/v1/production-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void createsAndSubmitsWithTheRequestPrincipalRatherThanAFabricatedActor() throws Exception {
        String id = mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCTION_RECORD_VERSION_CONFLICT"));
    }

    @Test
    void obtainsObjectTypeApplicabilityFromMasterData() throws Exception {
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"FARMER\"", "\"TRADER\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_OBJECT_TYPE"));
    }

    @Test
    void roundTripsAllFactsUsesStringDecimalsAndPreservesThemAcrossStateChanges() throws Exception {
        String id = create(fullDraftBody());
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cultivatedAreaMu").value("1.2346"))
                .andExpect(jsonPath("$.data.quality.QUALITY_TEST").value("3.0000"))
                .andExpect(jsonPath("$.data.costs.COST_TEST").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_TEST").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_TEST").value("6.0000"));

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-records/{id}/return", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(put("/api/v1/production-records/{id}", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(fullDraftBody().replaceFirst("}\\s*$", ",\"version\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.costs.COST_TEST").value("4.0000"));
    }

    @Test
    void rejectsStrictInvalidFiltersFutureDatesCultivarMismatchAndIllegalTransitions() throws Exception {
        for (String query : new String[] {
                "filter.status=NOT_A_STATUS", "filter.objectTypeCode=TRADER", "filter.surveyDate=2026-99-99"}) {
            mockMvc.perform(get("/api/v1/production-records?productCode=SOYBEAN&pageKind=MONITORING&pageNumber=0&pageSize=20&" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));
        }
        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("2026-08-01", "2099-08-01")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"surveyDate\"", "\"cultivarCode\":\"HEINONG_84\",\"surveyDate\"")
                                .replace("\"SOYBEAN\"", "\"CORN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_CULTIVAR"));

        String id = create(validDraftBody());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_TRANSITION"));
    }

    @Test
    void listsDatabaseLabelsDynamicValuesAllowedActionsAndAllThreeProducts() throws Exception {
        create(fullDraftBody());
        for (String product : new String[] {"CORN", "SOYBEAN", "RICE"}) {
            mockMvc.perform(get("/api/v1/production-records")
                            .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                            .queryParam("pageNumber", "0").queryParam("pageSize", "100"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_REGION").value("龙沙区"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_OBJECT_TYPE").value("农户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_STATUS").value("草稿"))
                .andExpect(jsonPath("$.data.items[0].values.COST_TEST").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"));
    }

    @Test
    void exposesDatabaseDrivenFactDefinitionsForTheDynamicForm() throws Exception {
        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("objectTypeCode", "FARMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].category").value("QUALITY"))
                .andExpect(jsonPath("$.data.groups[0].fields[0].code").value("QUALITY_TEST"))
                .andExpect(jsonPath("$.data.groups[0].fields[0].label").value("测试质量"))
                .andExpect(jsonPath("$.data.groups[1].category").value("COST"))
                .andExpect(jsonPath("$.data.groups[2].category").value("INSURANCE"))
                .andExpect(jsonPath("$.data.groups[3].category").value("SUBSIDY"));
    }

    private String create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.reportedAt").exists())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static String validDraftBody() {
        return """
                {"productCode":"SOYBEAN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"100","yieldPerMuKilograms":"180",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{}}
                """;
    }

    private static String fullDraftBody() {
        return """
                {"productCode":"SOYBEAN","objectTypeCode":"FARMER","regionCode":"230202",
                 "cultivarCode":"HEINONG_84","surveyDate":"2026-08-01",
                 "cultivatedAreaMu":"1.23456","yieldPerMuKilograms":"2.34567",
                 "quality":{"QUALITY_TEST":"3"},"costs":{"COST_TEST":"4"},
                 "insurance":{"INSURANCE_TEST":"5"},"subsidies":{"SUBSIDY_TEST":"6"}}
                """;
    }
}
