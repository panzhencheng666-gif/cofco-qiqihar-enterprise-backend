package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class, properties = "qiqihar.regional-public-data.enabled=false")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OperationalFacilityRestIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsGovernedStorageAndIndependentRailwayDetails() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("asOf", "2026-09-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'OWNED')].label").value("自有库点"))
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'LEASED')].label").value("租赁库点"))
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'HISTORICAL_LEASED')].label").value("历史租赁库点"))
                .andExpect(jsonPath("$.data.storageFacilities[?(@.code == 'KESHAN_DEPOT')].coordinatePrecision")
                        .value("STREET"))
                .andExpect(jsonPath("$.data.storageFacilities[?(@.code == 'KESHAN_DEPOT')].capacityTonnes")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.storageFacilities[?(@.code == 'KESHAN_DEPOT')].prices[0].current")
                        .value(false))
                .andExpect(jsonPath("$.data.sources[?(@.code == 'STORAGE')].notice").isNotEmpty())
                .andExpect(jsonPath("$.data.sources[?(@.code == 'RAILWAY')].sourceUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.railwayRoutes[0].geometryGeoJson").isNotEmpty());
    }

    @Test
    void rejectsUnknownAndDuplicateQueryParameters() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200")
                        .queryParam("unexpected", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OPERATIONAL_FACILITY_QUERY"));

        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200", "230229"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OPERATIONAL_FACILITY_QUERY"));
    }

    @Test
    void supportsTheOverallMapWithoutInventingAnAdministrativeRegionCode() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("asOf", "2026-09-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.storageFacilities[?(@.code == 'KESHAN_DEPOT')]").isNotEmpty())
                .andExpect(jsonPath("$.data.railwayFacilities[?(@.name == '泰来')]").isNotEmpty());
    }

    @Test
    void supportsAnUnfilteredProductCatalogue() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200")
                        .queryParam("asOf", "2026-09-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.storageFacilities[?(@.code == 'KESHAN_DEPOT')].prices").isNotEmpty());
    }

    @Test
    void rejectsAnInvalidAsOfDate() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("asOf", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OPERATIONAL_FACILITY_QUERY"));
    }
}
