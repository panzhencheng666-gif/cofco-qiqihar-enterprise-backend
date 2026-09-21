package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.overview.application.OperationalFacilityWorkbook;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.jayway.jsonpath.JsonPath;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class, properties = "qiqihar.regional-public-data.enabled=false")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OperationalFacilityRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void removeUserSubmittedFacilities() {
        JdbcClient.create(dataSource).sql(
                "DELETE FROM overview.storage_facility WHERE source_origin='USER_SUBMITTED'").update();
    }

    @Test
    void returnsOnlyUserSubmittedStorageAndIndependentRailwayDetails() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("asOf", "2026-09-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'OWNED')].label").value("自有库点"))
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'LEASED')].label").value("租赁库点"))
                .andExpect(jsonPath("$.data.storageCategories[?(@.code == 'HISTORICAL_LEASED')].label").value("历史租赁库点"))
                .andExpect(jsonPath("$.data.storageFacilities").isEmpty())
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
                .andExpect(jsonPath("$.data.storageFacilities").isEmpty())
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
                .andExpect(jsonPath("$.data.storageFacilities").isEmpty());
    }

    @Test
    void userCreatesUpdatesRequeriesAndArchivesOwnedFacility() throws Exception {
        String created = mvc.perform(post("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"克山一号库","relationType":"OWNED","regionCode":"230229",
                                 "address":"克山县测试街道1号","longitude":125.86,"latitude":48.03,
                                 "operationalStatus":"ACTIVE","capacityTonnes":12000.500,
                                 "capacityAsOf":"2026-09-19","validFrom":"2026-01-01",
                                 "validTo":null,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("克山一号库"))
                .andExpect(jsonPath("$.data.workUnitCode").isNotEmpty())
                .andExpect(jsonPath("$.data.coordinatePrecision").value("EXACT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.data.code");

        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200").queryParam("asOf", "2026-09-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageFacilities[0].code").value(code));

        mvc.perform(put("/api/v1/overview/operational-facilities/{code}", code)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"克山一号租赁库","relationType":"LEASED","regionCode":"230229",
                                 "address":"克山县测试街道1号","longitude":125.86,"latitude":48.03,
                                 "operationalStatus":"ACTIVE","capacityTonnes":13000.000,
                                 "capacityAsOf":"2026-09-19","validFrom":"2026-01-01",
                                 "validTo":null,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationType").value("LEASED"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(delete("/api/v1/overview/operational-facilities/{code}", code)
                        .principal(() -> "production-tester").queryParam("expectedVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200").queryParam("asOf", "2026-09-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageFacilities").isEmpty());
    }

    @Test
    void downloadsTemplateAndAtomicallyImportsValidFacilities() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-facilities/import-template")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")));

        byte[] workbook = BusinessImportWorkbook.create(
                OperationalFacilityWorkbook.template(), java.util.List.of(
                        java.util.List.of("克山自有一号库", "自有库点", "230229", "克山县测试路1号",
                                "125.86", "48.03", "运营中", "12000.500", "2026-09-19",
                                "2026-01-01", ""),
                        java.util.List.of("泰来租赁一号库", "租赁库点", "230224", "泰来县测试路2号",
                                "123.41", "46.40", "运营中", "8000", "2026-09-19",
                                "2026-03-01", "2026-12-31")));
        MockMultipartFile file = new MockMultipartFile("file", "库点导入.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mvc.perform(multipart("/api/v1/overview/operational-facilities/imports")
                        .file(file).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.rowErrors").isEmpty());

        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200").queryParam("asOf", "2026-09-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageFacilities.length()").value(2));
    }

    @Test
    void rejectsTheWholeWorkbookWithActualRowFieldAndReason() throws Exception {
        byte[] workbook = BusinessImportWorkbook.create(
                OperationalFacilityWorkbook.template(), java.util.List.of(
                        java.util.List.of("克山自有一号库", "自有库点", "230229", "克山县测试路1号",
                                "125.86", "48.03", "运营中", "12000", "2026-09-19",
                                "2026-01-01", ""),
                        java.util.List.of("区域错误库点", "租赁库点", "999999", "不存在的区域",
                                "", "", "运营中", "8000", "2026-09-19", "", "")));
        MockMultipartFile file = new MockMultipartFile("file", "库点导入.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mvc.perform(multipart("/api/v1/overview/operational-facilities/imports")
                        .file(file).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.rowErrors[0].rowNumber").value(3))
                .andExpect(jsonPath("$.data.rowErrors[0].field").value("所在地区"))
                .andExpect(jsonPath("$.data.rowErrors[0].message").value("所在地区不属于四个运营区域"));

        mvc.perform(get("/api/v1/overview/operational-facilities")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230200").queryParam("asOf", "2026-09-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageFacilities").isEmpty());
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
