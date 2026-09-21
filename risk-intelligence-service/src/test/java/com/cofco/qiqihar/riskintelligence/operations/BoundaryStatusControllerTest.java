package com.cofco.qiqihar.riskintelligence.operations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.riskintelligence.configuration.RiskDatabaseBoundary;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BoundaryStatusControllerTest {
    @Test
    void returnsTheVerifiedLiveBoundary() throws Exception {
        RiskDatabaseBoundary boundary = new RiskDatabaseBoundary(
                "qiqihar_enterprise_test",
                "risk_runtime",
                Set.of("risk"),
                Instant.parse("2026-09-21T06:00:00Z"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new BoundaryStatusController(boundary))
                .build();

        mvc.perform(get("/api/v1/risk-intelligence/operations/boundary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseName").value("qiqihar_enterprise_test"))
                .andExpect(jsonPath("$.databaseUser").value("risk_runtime"))
                .andExpect(jsonPath("$.writableSchemas[0]").value("risk"))
                .andExpect(jsonPath("$.verifiedAt").value("2026-09-21T06:00:00Z"));
    }
}
