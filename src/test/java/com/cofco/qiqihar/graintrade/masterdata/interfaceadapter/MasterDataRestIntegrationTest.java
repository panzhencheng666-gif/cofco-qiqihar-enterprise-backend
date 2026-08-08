package com.cofco.qiqihar.graintrade.masterdata.interfaceadapter;

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
class MasterDataRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesRegionsProductsCultivarsAndTheCurrentBusinessPeriod() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(29))
                .andExpect(jsonPath("$.data[0].name").value("齐齐哈尔市"))
                .andExpect(jsonPath("$.data[3].parentCode").value("230200"));

        mockMvc.perform(get("/api/v1/master-data/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(contains("玉米", "大豆", "稻谷")));

        mockMvc.perform(get("/api/v1/master-data/products/SOYBEAN/cultivars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(contains("黑农84", "东生22")));

        mockMvc.perform(get("/api/v1/master-data/business-periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '2026-W32')].code")
                        .value(contains("2026-W32")))
                .andExpect(jsonPath("$.data[?(@.code == '2026-W32')].startsOn")
                        .value(contains("2026-08-03")))
                .andExpect(jsonPath("$.data[?(@.code == '2026-W32')].endsOn")
                        .value(contains("2026-08-09")));
    }

    @Test
    void exposesApplicableObjectsAndProductSpecificPageFields() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/object-types")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("domain", "MARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name")
                        .value(contains("贸易商", "深加工", "批发市场", "承储企业")));

        mockMvc.perform(get("/api/v1/master-data/page-definitions")
                        .queryParam("productCode", "RICE")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[*].name")
                        .value(contains("水分", "出米率", "出糙率", "杂质")))
                .andExpect(jsonPath("$.data.defaultContext").doesNotExist());
    }

    @Test
    void exposesDynamicNavigationProductsOnlyWhenTheRequestedPageExists() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/products")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(contains("大豆", "稻谷")));
    }

    @Test
    void rejectsIncompleteOrBlankProductPageApplicabilityQueries() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/products").queryParam("domain", "MARKET"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE_APPLICABILITY"));
        mockMvc.perform(get("/api/v1/master-data/products").queryParam("pageKind", "QUALITY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE_APPLICABILITY"));
        mockMvc.perform(get("/api/v1/master-data/products")
                        .queryParam("domain", " ")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE_APPLICABILITY"));
    }

    @Test
    void missingPageDefinitionUsesTheEstablishedErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/page-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY")
                        .header("X-Trace-Id", "master-data-not-found"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MASTER_DATA_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Requested master data does not exist"))
                .andExpect(jsonPath("$.traceId").value("master-data-not-found"));
    }
}
