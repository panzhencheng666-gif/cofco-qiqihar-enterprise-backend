package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class PageDefinitionRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesARealDefinitionAtTheCanonicalEndpointWithoutConflictingWithTaskTwo() throws Exception {
        mockMvc.perform(get("/api/v1/page-definitions/MARKET/QUALITY")
                        .queryParam("productCode", "SOYBEAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("大豆质量指标"))
                .andExpect(jsonPath("$.data.columnGroups[0].fields[*].label")
                        .value(contains("蛋白", "出油率", "不完善粒", "水分", "杂质")))
                .andExpect(jsonPath("$.data.filters").isEmpty())
                .andExpect(jsonPath("$.data.defaultContext").isEmpty())
                .andExpect(jsonPath("$.data.pagination.pageSizeOptions", contains(20, 50, 100)));

        mockMvc.perform(get("/api/v1/master-data/page-definitions")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownDefinitionUsesTheControlledNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/page-definitions/MARKET/QUALITY")
                        .queryParam("productCode", "CORN")
                        .header("X-Trace-Id", "page-definition-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAGE_DEFINITION_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("page-definition-not-found"));
    }

    @Test
    void blankProductCodeUsesAControlledBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/page-definitions/MARKET/QUALITY")
                        .queryParam("productCode", " ")
                        .header("X-Trace-Id", "blank-product-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE_KEY"))
                .andExpect(jsonPath("$.traceId").value("blank-product-code"));
    }
}
