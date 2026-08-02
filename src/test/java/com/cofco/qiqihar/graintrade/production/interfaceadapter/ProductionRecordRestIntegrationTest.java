package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void removeTestRecords() {
        JdbcClient.create(dataSource).sql("DELETE FROM production.production_record WHERE last_modified_by = 'production-tester'").update();
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
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
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

    private static String validDraftBody() {
        return """
                {"productCode":"SOYBEAN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","reportedAt":"2026-08-02T08:00:00Z",
                 "cultivatedAreaMu":100,"yieldPerMuKilograms":180,"quality":{},"costs":{},"insurance":{},"subsidies":{}}
                """;
    }
}
