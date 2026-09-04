package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.AdministrativeBoundarySnapshot;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OverviewSamplePointRestIntegrationTest {
    private static final String PREFECTURE = "230200";
    private static final String COUNTY = "230202";
    private static final String TOWNSHIP = "230202997";
    private static final String VILLAGE = "230202997001";
    private static final String SURVEY_POINT = "94000000-0000-0000-0000-000000000001";
    private static final String LOGISTICS_POINT = "94000000-0000-0000-0000-000000000002";
    private static final String MISSING_POINT = "94000000-0000-0000-0000-000000000003";
    private static final String DRAFT_POINT = "94000000-0000-0000-0000-000000000004";
    private static final String DUPLICATE_POINT = "94000000-0000-0000-0000-000000000005";
    private static final String DIRECT_COUNTY_POINT = "94000000-0000-0000-0000-000000000006";

    @Test
    void designSampleReferenceChangesNeverEnterFormalListsOrSynthesizeOverviewMetrics()
            throws Exception {
        JsonNode formalSamplesBefore = responseData(get("/api/v1/overview/sample-points")
                .principal(() -> "production-tester")
                .queryParam("regionCode", COUNTY)
                .queryParam("productCode", "CORN")
                .queryParam("year", "2026"));
        JsonNode indicatorsBefore = responseData(get("/api/v1/overview/indicators")
                .principal(() -> "production-tester")
                .queryParam("regionCode", COUNTY)
                .queryParam("productCode", "CORN")
                .queryParam("year", "2026"));
        String contractVersion = jdbc.sql("""
                SELECT contract_version FROM platform.design_sample_contract WHERE active
                """).query(String.class).single();
        String contractDigest = jdbc.sql("""
                SELECT platform.current_design_sample_contract_digest()
                """).query(String.class).single();
        String createRequest = """
                {"contractVersion":"%s","contractDigest":"%s",
                 "context":{"domainCode":"REFERENCE","productCode":"GENERAL",
                            "objectTypeCode":"REFERENCE_POINT"},
                 "values":{"DSP_NAME":"总揽边界设计参考点",
                           "DSP_REGION_CODE":"230202",
                           "DSP_ADDRESS":"更新前地址",
                           "DSP_LONGITUDE":123.9,"DSP_LATITUDE":47.3}}
                """.formatted(contractVersion, contractDigest);
        String designId = objectMapper.readTree(mvc.perform(post("/api/v1/design-sample-points")
                        .header("Idempotency-Key", "overview-design-reference-boundary")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mvc.perform(put("/api/v1/design-sample-points/{id}", designId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractVersion":"%s","contractDigest":"%s","expectedVersion":0,
                                 "context":{"domainCode":"REFERENCE","productCode":"GENERAL",
                                            "objectTypeCode":"REFERENCE_POINT"},
                                 "values":{"DSP_NAME":"总揽边界设计参考点",
                                           "DSP_REGION_CODE":"230202",
                                           "DSP_ADDRESS":"更新后参考地址",
                                           "DSP_LONGITUDE":123.9,"DSP_LATITUDE":47.3}}
                                """.formatted(contractVersion, contractDigest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS").value("更新后参考地址"))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/api/v1/design-sample-points/{id}", designId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS").value("更新后参考地址"));

        assertEquals(formalSamplesBefore, responseData(get("/api/v1/overview/sample-points")
                .principal(() -> "production-tester")
                .queryParam("regionCode", COUNTY)
                .queryParam("productCode", "CORN")
                .queryParam("year", "2026")));
        assertEquals(indicatorsBefore, responseData(get("/api/v1/overview/indicators")
                .principal(() -> "production-tester")
                .queryParam("regionCode", COUNTY)
                .queryParam("productCode", "CORN")
                .queryParam("year", "2026")));
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;
    private JdbcClient jdbc;
    private AdministrativeBoundarySnapshot countyBoundarySnapshot;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        countyBoundarySnapshot = AdministrativeBoundarySnapshot.capture(jdbc, COUNTY);
        clean();
        insertRegionAndBoundaryFixtures();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        insertSamplePointFixtures();
        insertApprovedSourceFixtures();
    }

    @AfterEach
    void cleanAfterEach() {
        clean();
        countyBoundarySnapshot.restore(jdbc);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test
    void listsRetiredSamplesOnlyInTheHistoricalLayerForTheirRetirementYear() throws Exception {
        jdbc.sql("""
                UPDATE registry.sample_point
                SET deletion_state='RETIRED',effective_to=DATE '2027-02-04',
                    retired_at=TIMESTAMPTZ '2027-02-04 09:30:00+08',
                    retired_by='production-tester',retired_reason='年度样本调整'
                WHERE sample_point_id=CAST(:id AS uuid)
                """).param("id", SURVEY_POINT).update();

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT + "')]").isEmpty());

        mvc.perform(get("/api/v1/overview/historical-sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2027")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data[0].name").value("同一跨产品样本点"))
                .andExpect(jsonPath("$.data[0].roles[0].code").value("PRODUCTION"))
                .andExpect(jsonPath("$.data[0].types[0].code").value("FARMER"));

        mvc.perform(get("/api/v1/overview/historical-sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE sample_point_id=CAST(:id AS uuid) AND product_code='CORN'
                  AND status_code='APPROVED'
                """).param("id", SURVEY_POINT).query(Long.class).single());
    }

    @Test
    void exportsOnlyTheSameFormalStableSampleIdentitiesShownByOverviewLists() throws Exception {
        byte[] bytes = mvc.perform(get("/api/v1/overview/sample-points/export")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();

        String csv = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("唯一正式样本"));
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains(SURVEY_POINT));
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains(LOGISTICS_POINT));
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("\"物流样本\",\"1\""));
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("\"物流类\""));
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains("\"物流节点\""));
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains(DRAFT_POINT));
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains("待确认"));
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains("未校验"));
    }

    @Test
    void overallAggregateUsesOnePartitionAndClosesToItsPrefectureBuckets() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230200')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230200')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230200')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test
    void selectedProductShowsOnlyFormalSampleIdentitiesWithApprovedBusinessDataForThatProduct()
            throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "RICE")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].logisticsCount")
                        .value(org.hamcrest.Matchers.hasItem(0)));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "RICE")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].roles[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("PRODUCTION", "MARKET")))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].regionCode").value(org.hamcrest.Matchers.hasItem(VILLAGE)))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].roles[*].iconKey")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("production", "market")))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].types[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("FARMER", "RICE_MILL")))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + LOGISTICS_POINT
                        + "')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "RICE")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDataStatus").doesNotExist())
                .andExpect(jsonPath("$.data.roles[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("PRODUCTION", "MARKET")))
                .andExpect(jsonPath("$.data.associations.length()").value(2))
                .andExpect(jsonPath("$.data.associations[*].productCode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("RICE"))))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].typeCode")
                        .value(org.hamcrest.Matchers.hasItem("RICE_MILL")))
                .andExpect(jsonPath("$.data.associations[?(@.productCode == 'CORN')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", LOGISTICS_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "RICE")
                        .queryParam("year", "2026"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OVERVIEW_SAMPLE_POINT_NOT_FOUND"));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", LOGISTICS_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDataStatus").doesNotExist())
                .andExpect(jsonPath("$.data.roles[0].code").value("LOGISTICS"))
                .andExpect(jsonPath("$.data.associations.length()").value(2))
                .andExpect(jsonPath("$.data.associations[*].productCode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CORN"))))
                .andExpect(jsonPath("$.data.associations[*].sourceRole")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("ORIGIN", "DESTINATION")))
                .andExpect(jsonPath("$.data.associations[*].businessValues.ORIGIN_NODE.value")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("铁路物流节点"))))
                .andExpect(jsonPath("$.data.associations[*].businessValues.DESTINATION_NODE.value")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("公路物流节点"))))
                .andExpect(jsonPath("$.data.associations[*].businessValues.ROUTE_VOLUME.value")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("36"))))
                .andExpect(jsonPath("$.data.associations[*].businessValues.ROUTE_VOLUME.unitCode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("吨"))));
    }

    @Test
    void returnsListAndIconsFromOneScopeConsistentSnapshot() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-snapshot")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.totalCount").value(2))
                .andExpect(jsonPath("$.data.list.items.length()").value(2))
                .andExpect(jsonPath("$.data.icons.length()").value(2))
                .andExpect(jsonPath("$.data.icons[*].samplePointId")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                SURVEY_POINT, LOGISTICS_POINT)));
    }

    @Test
    void excludesApprovedLogisticsRowsUntilTheirSurveyPeriodIsConfirmed() throws Exception {
        jdbc.sql("""
                UPDATE logistics.route_event
                SET survey_period_governance_state='PENDING_GOVERNANCE'
                WHERE event_id='94000000-0000-0000-0000-000000000301'
                """).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "LOGISTICS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "LOGISTICS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void keepsSampleIdentityStableOnlyWhenTheSelectedProductHasApprovedBusinessData() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].unresolvedSourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].products.length()").value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'MARKET')].count")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].products[*].code")
                        .value(org.hamcrest.Matchers.hasItem("SOYBEAN")))
                .andExpect(jsonPath("$.data.categories[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "PRODUCTION", "MARKET")));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(2))
                .andExpect(jsonPath("$.data.associations[?(@.productCode == 'SOYBEAN')]")
                        .isEmpty());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "MARKET")
                        .queryParam("typeCode", "RICE_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));
    }

    @Test
    void requiresProductScopeAndCountsOneGovernedPointAcrossCategories() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("year", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("year", "2026")
                        .queryParam("productCode", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].products.length()").value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].products[0].code").value(org.hamcrest.Matchers.hasItem("CORN")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].latestBusinessDate").value(org.hamcrest.Matchers.hasItem("2026-08-05")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].summaryValues.SAMPLE_CONTACT.value")
                        .value(org.hamcrest.Matchers.hasItem("13900000000")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].summaryValues.SURVEYOR_NAME.value")
                        .value(org.hamcrest.Matchers.hasItem("王雷")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].summaryValues.SURVEYOR_PHONE.value")
                        .value(org.hamcrest.Matchers.hasItem("13800000000")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].summaryValues.CULTIVATED_AREA_MU.value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].summaryValues.ESTIMATED_OUTPUT_KG.value")
                        .value(org.hamcrest.Matchers.hasItem("200")));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(1))
                .andExpect(jsonPath("$.data.associations[0].productCode").value("CORN"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.SAMPLE_CONTACT.label")
                        .value("样本点联系方式"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.SURVEYOR_NAME.label")
                        .value("调研人"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.SURVEYOR_PHONE.label")
                        .value("调研人联系方式"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.MOISTURE.label")
                        .value("水分"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.MOISTURE.value")
                        .value("14.2"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.MOISTURE.unitCode")
                        .value("%"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.PROD_HARVEST_AREA_MU.label")
                        .value("预计收获面积"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.PROD_HARVEST_AREA_MU.value")
                        .value("9.5"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.PROD_GROWTH_STATUS.value")
                        .value("长势良好"))
                .andExpect(jsonPath("$.data.associations[?(@.productCode == 'SOYBEAN')]").isEmpty())
                .andExpect(jsonPath("$.data.associations[0].businessValues.PROD_CULTIVAR_NAME").doesNotExist())
                .andExpect(jsonPath("$.data.associations[0].businessValues.PROD_REPORTER_PHONE").doesNotExist());
    }

    @Test
    void usesOnlyTheLatestEffectiveApprovedProductionRecordForMapEntities() throws Exception {
        insertValidPoint(DUPLICATE_POINT, "SURVEY_SITE", "重复导入样本点", "APPROVED");
        insertProduction(
                "94000000-0000-0000-0000-000000000100",
                "CORN",
                "APPROVED",
                DUPLICATE_POINT);
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(
                  record_id,field_code,value)
                VALUES('94000000-0000-0000-0000-000000000100',
                       'PROD_SAMPLE_CONTACT','13900000000')
                """).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[?(@.name == '重复导入样本点')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));
    }

    @Test
    void aggregateTotalCountsDistinctPointsAcrossProductionAndMarket() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        jdbc.sql("DELETE FROM market.market_record").update();
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        jdbc.sql("DELETE FROM production.production_record").update();
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test
    void exposesOnlyTypesBackedByActualFormalRecordsWithUniqueIconKeys() throws Exception {
        String response = mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode categories = objectMapper.readTree(response).path("data").path("categories");
        List<String> categoryCodes = new ArrayList<>();
        List<String> typeCodes = new ArrayList<>();
        Set<String> iconKeys = new HashSet<>();
        categories.forEach(category -> {
            categoryCodes.add(category.path("code").asText());
            category.path("types").forEach(type -> {
                typeCodes.add(type.path("code").asText());
                iconKeys.add(type.path("iconKey").asText(null));
            });
        });

        org.assertj.core.api.Assertions.assertThat(categoryCodes)
                .containsExactly("PRODUCTION", "MARKET", "LOGISTICS");
        org.assertj.core.api.Assertions.assertThat(typeCodes).containsExactly(
                "FARMER", "TRADER", "RAIL_NODE", "ROAD_NODE");
        org.assertj.core.api.Assertions.assertThat(iconKeys)
                .doesNotContainNull()
                .hasSize(typeCodes.size());
    }

    @Test
    void closesFilteredEntityCountsAcrossTheValidatedCurrentListAndIcons() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(1))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.correctionSourceCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + MISSING_POINT
                        + "')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void exposesOnlyLocationValidatedIdentitiesInTheCurrentOverviewProjection() throws Exception {
        String listResponse = mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(1))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.correctionSourceCount").value(0))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[*].dataQualityReason")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andReturn().getResponse().getContentAsString();

        String aggregateResponse = mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode currentList = objectMapper.readTree(listResponse).path("data");
        JsonNode countyBucket = null;
        for (JsonNode bucket : objectMapper.readTree(aggregateResponse).path("data")) {
            if (COUNTY.equals(bucket.path("regionCode").asText())) countyBucket = bucket;
        }
        org.assertj.core.api.Assertions.assertThat(currentList.path("totalCount").asLong()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countyBucket).isNotNull();
        org.assertj.core.api.Assertions.assertThat(countyBucket.path("samplePointCount").asLong())
                .isEqualTo(countyBucket.path("validCoordinateCount").asLong());
        org.assertj.core.api.Assertions.assertThat(countyBucket.path("dataQualityIssueCount").asLong())
                .isZero();
        org.assertj.core.api.Assertions.assertThat(countyBucket.path("correctionSourceCount").asLong())
                .isZero();
        org.assertj.core.api.Assertions.assertThat(countyBucket.path("unresolvedSourceCount").asLong())
                .isZero();
    }

    @Test
    void keepsCorrectionAndUnresolvedGovernanceSourcesOutOfTheCurrentOverviewProjection() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctionSourceCount").value(0))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "MARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctionSourceCount").value(0))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "FARMER")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctionSourceCount").value(0))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0));
    }

    @Test
    void trustsPublishedColocatedEntitiesWithoutASecondConfirmationState() throws Exception {
        String first = "94000000-0000-0000-0000-000000000011";
        String second = "94000000-0000-0000-0000-000000000012";
        insertValidPoint(first, "SURVEY_SITE", "并址主体甲", "APPROVED");
        insertValidPoint(second, "SURVEY_SITE", "并址主体乙", "APPROVED");
        jdbc.sql("""
                UPDATE registry.sample_point
                SET governed_point=ST_Translate(governed_point,0.0005,0)
                WHERE sample_point_id IN (CAST(:first AS uuid),CAST(:second AS uuid))
                """).param("first", first).param("second", second).update();
        insertProduction("94000000-0000-0000-0000-000000000113", "CORN", "APPROVED", first);
        insertProduction("94000000-0000-0000-0000-000000000114", "CORN", "APPROVED", second);

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "并址主体"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "并址主体"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        jdbc.sql("""
                UPDATE registry.sample_point SET coordinate_shared_verified=true
                WHERE sample_point_id IN (CAST(:first AS uuid),CAST(:second AS uuid))
                """).param("first", first).param("second", second).update();

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "并址主体"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].samplePointId")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(first, second)))
                .andExpect(jsonPath("$.data[*].dataQualityReason")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void doesNotRevalidateAStableCoordinateAfterTheIngressGuardAcceptedPublication() throws Exception {
        String point = "94000000-0000-0000-0000-000000000031";
        insertValidPoint(point, "SURVEY_SITE", "正式归属越界样本", "APPROVED");
        // The trigger is deliberately bypassed to prove that reads do not create a second
        // governance workflow. Normal writes remain protected by the ingress trigger.
        jdbc.sql("ALTER TABLE registry.sample_point DISABLE TRIGGER sample_point_containment_guard")
                .update();
        try {
            jdbc.sql("""
                    UPDATE registry.sample_point
                    SET governed_point=ST_Translate(governed_point,1,1)
                    WHERE sample_point_id=CAST(:point AS uuid)
                    """).param("point", point).update();
        } finally {
            jdbc.sql("ALTER TABLE registry.sample_point ENABLE TRIGGER sample_point_containment_guard")
                    .update();
        }
        insertProduction("94000000-0000-0000-0000-000000000131", "CORN", "APPROVED", point);

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "正式归属越界样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(1))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "正式归属越界样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void doesNotTreatAChangedRenderBoundaryAsASecondSampleValidationGate() throws Exception {
        String point = "94000000-0000-0000-0000-000000000032";
        insertValidPoint(point, "SURVEY_SITE", "地图展示边界外样本", "APPROVED");
        jdbc.sql("""
                UPDATE registry.sample_point
                SET governed_point=ST_Translate(governed_point,0.0005,0.0005)
                WHERE sample_point_id=CAST(:point AS uuid)
                """).param("point", point).update();
        insertProduction("94000000-0000-0000-0000-000000000132", "CORN", "APPROVED", point);

        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Translate(geometry,1,1),
                    geo_json=ST_AsGeoJSON(ST_Translate(geometry,1,1))
                WHERE region_code=:region
                """).param("region", VILLAGE).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "地图展示边界外样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(1))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "地图展示边界外样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void trustsThePublishedFormalSampleInsteadOfRevalidatingItsApprovedBusinessRecord() throws Exception {
        String point = "94000000-0000-0000-0000-000000000033";
        String record = "94000000-0000-0000-0000-000000000133";
        insertValidPoint(point, "SURVEY_SITE", "审核后不二次校验样本", "APPROVED");
        insertProduction(record, "CORN", "APPROVED", point);
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(
                  record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_LONGITUDE','124.9'),
                      (:record,'PROD_SAMPLE_LATITUDE','48.3')
                """).param("record", record).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "审核后不二次校验样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(1))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0))
                .andExpect(jsonPath("$.data.items[0].samplePointId").value(point));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("query", "审核后不二次校验样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(point));
    }

    @Test
    void usesThePublishedStableCoordinateInsteadOfMovingThePointForEachObservation() throws Exception {
        String first = "94000000-0000-0000-0000-000000000021";
        String second = "94000000-0000-0000-0000-000000000022";
        String firstRecord = "94000000-0000-0000-0000-000000000123";
        String secondRecord = "94000000-0000-0000-0000-000000000124";
        insertValidPoint(first, "SURVEY_SITE", "年度坐标样本甲", "APPROVED");
        insertValidPoint(second, "SURVEY_SITE", "年度坐标样本乙", "APPROVED");
        jdbc.sql("""
                UPDATE registry.sample_point
                SET governed_point=ST_Translate(governed_point,0.0007,0)
                WHERE sample_point_id IN (CAST(:first AS uuid),CAST(:second AS uuid))
                """).param("first", first).param("second", second).update();
        double publishedLongitude = jdbc.sql("""
                SELECT ST_X(governed_point) FROM registry.sample_point
                WHERE sample_point_id=CAST(:point AS uuid)
                """).param("point", first).query(Double.class).single();
        double publishedLatitude = jdbc.sql("""
                SELECT ST_Y(governed_point) FROM registry.sample_point
                WHERE sample_point_id=CAST(:point AS uuid)
                """).param("point", first).query(Double.class).single();
        insertProduction(firstRecord, "CORN", "APPROVED", first);
        insertProduction(secondRecord, "CORN", "APPROVED", second);
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(
                  record_id,field_code,value)
                VALUES
                  (:firstRecord,'PROD_SAMPLE_LONGITUDE','123.9005'),
                  (:firstRecord,'PROD_SAMPLE_LATITUDE','47.3005'),
                  (:secondRecord,'PROD_SAMPLE_LONGITUDE','123.8995'),
                  (:secondRecord,'PROD_SAMPLE_LATITUDE','47.2995')
                """).param("firstRecord", firstRecord).param("secondRecord", secondRecord).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "年度坐标样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.validCoordinateCount").value(2))
                .andExpect(jsonPath("$.data.dataQualityIssueCount").value(0));
        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "年度坐标样本"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].longitude")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                publishedLongitude, publishedLongitude)))
                .andExpect(jsonPath("$.data[*].latitude")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                publishedLatitude, publishedLatitude)));
    }

    @Test
    void keepsRepeatedMonthsAndFutureYearsForOneStablePointOutOfDuplicateCoordinateWarnings() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id)
                VALUES
                  ('94000000-0000-0000-0000-000000000115','CORN','FARMER',:region,
                   DATE '2026-09-05',TIMESTAMPTZ '2026-09-06 08:00:00+08',10,20,'APPROVED',
                   'production-tester',CAST(:point AS uuid)),
                  ('94000000-0000-0000-0000-000000000116','CORN','FARMER',:region,
                   DATE '2027-05-05',TIMESTAMPTZ '2027-05-06 08:00:00+08',10,20,'APPROVED',
                   'production-tester',CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();

        for (String year : java.util.List.of("2026", "2027")) {
            mvc.perform(get("/api/v1/overview/sample-point-icons")
                            .principal(() -> "production-tester")
                            .queryParam("regionCode", VILLAGE)
                            .queryParam("productCode", "CORN")
                            .queryParam("year", year)
                            .queryParam("categoryCode", "PRODUCTION")
                            .queryParam("query", "同一跨产品"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT))
                    .andExpect(jsonPath("$.data[0].dataQualityReason").value(
                            org.hamcrest.Matchers.nullValue()));
        }
    }

    @Test
    void exposesApprovedProductionMarketAndLogisticsSamplesBefore2026ThroughTheSameContract()
            throws Exception {
        jdbc.sql("""
                UPDATE registry.sample_point SET effective_from=DATE '2020-01-01'
                WHERE sample_point_id IN (CAST(:surveyPoint AS uuid),CAST(:logisticsPoint AS uuid))
                """).param("surveyPoint", SURVEY_POINT).param("logisticsPoint", LOGISTICS_POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000401','CORN','FARMER',:region,
                  DATE '2024-08-05',TIMESTAMPTZ '2024-08-06 08:00:00+08',10,20,'APPROVED',
                  'production-tester',CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  packaging_amount,freight_amount,packaging_form,status_code,last_modified_by,
                  party_id,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000402','CORN','TRADER',:region,
                  DATE '2024-08-05',TIMESTAMPTZ '2024-08-06 08:00:00+08',2400,2480,'BOTH',0,
                  0,0,'BULK','APPROVED','market-tester',NULL,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_code,destination_region_code,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,
                  version,created_by,last_modified_by,created_at,updated_at,business_region_code,
                  sample_contact,survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000403','CORN',NULL,DATE '2024-08-05',
                  TIMESTAMPTZ '2024-08-06 08:00:00+08',:region,NULL,:region,NULL,'ROAD','INFLOW',
                  '2024物流样本','物流调研员','APPROVED',0,'logistics-tester','logistics-tester',
                  TIMESTAMPTZ '2024-08-06 08:00:00+08',TIMESTAMPTZ '2024-08-06 08:00:00+08',
                  :region,'13800000000',2024,8,'YEAR_MONTH','CONFIRMED',CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", LOGISTICS_POINT).update();
        jdbc.sql("""
                UPDATE logistics.route_event event
                SET sample_longitude=ST_X(point.governed_point),
                    sample_latitude=ST_Y(point.governed_point)
                FROM registry.sample_point point
                WHERE event.event_id='94000000-0000-0000-0000-000000000403'
                  AND point.sample_point_id=CAST(:point AS uuid)
                """).param("point", LOGISTICS_POINT).update();

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + COUNTY + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + COUNTY + "')].productionCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + COUNTY + "')].marketCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + COUNTY + "')].logisticsCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'PRODUCTION')].count")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'MARKET')].count")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'LOGISTICS')].count")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + SURVEY_POINT
                        + "')].roles[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("PRODUCTION", "MARKET")))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + LOGISTICS_POINT
                        + "')].roles[*].code")
                        .value(org.hamcrest.Matchers.hasItem("LOGISTICS")));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", LOGISTICS_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(1))
                .andExpect(jsonPath("$.data.associations[0].occurrenceDate").value("2024-08-05"))
                .andExpect(jsonPath("$.data.associations[0].sourceRole").value("SURVEY"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.SOURCE_ORGANIZATION.value")
                        .value("2024物流样本"))
                .andExpect(jsonPath("$.data.associations[0].businessValues.SAMPLE_LONGITUDE.value")
                        .exists())
                .andExpect(jsonPath("$.data.associations[0].businessValues.SAMPLE_LATITUDE.value")
                        .exists());
    }

    @Test
    void returnsApprovedMonthlyHistoryForOneStablePointWithoutDuplicatingItsMapIcon() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000117','CORN','FARMER',:region,
                  DATE '2026-09-05',TIMESTAMPTZ '2026-09-06 08:00:00+08',15,22,'APPROVED',
                  'production-tester',CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES('94000000-0000-0000-0000-000000000117',
                       'PROD_SAMPLE_CONTACT','13900000000')
                """).update();

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(2))
                .andExpect(jsonPath("$.data.associations[*].occurrenceDate")
                        .value(org.hamcrest.Matchers.hasItems("2026-08-05", "2026-09-05")))
                .andExpect(jsonPath("$.data.associations[?(@.occurrenceDate == '2026-09-05')].businessValues.CULTIVATED_AREA_MU.value")
                        .value(org.hamcrest.Matchers.hasItem("15")));
    }

    @Test
    void keepsAdministrativeAggregatesIndependentFromListFilters() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230200')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", PREFECTURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].regionName")
                        .value(org.hamcrest.Matchers.hasItem("龙沙区")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].regionLevel")
                        .value(org.hamcrest.Matchers.hasItem("COUNTY")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].unresolvedSourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].categoryCode").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", PREFECTURE)
                        .queryParam("categoryCode", "MARKET")
                        .queryParam("typeCode", "TRADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.regionCode == '230202')].unresolvedSourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].regionName")
                        .value(org.hamcrest.Matchers.hasItem("契约测试乡")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].regionLevel")
                        .value(org.hamcrest.Matchers.hasItem("TOWNSHIP")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + VILLAGE + "')].regionLevel")
                        .value(org.hamcrest.Matchers.hasItem("VILLAGE")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + VILLAGE + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)));
    }

    @Test
    void partitionsCountyIdentitiesBetweenChildRegionsAndTheExplicitLocalSampleBucket()
            throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','区县本级无坐标样本点',:region,'APPROVED','MISSING',
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("id", DIRECT_COUNTY_POINT).param("region", COUNTY).update();
        insertProductionAtRegion(
                "94000000-0000-0000-0000-000000000118",
                "CORN", "APPROVED", DIRECT_COUNTY_POINT, COUNTY);

        String aggregateResponse = mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].scopeKind")
                        .value(org.hamcrest.Matchers.hasItem("CHILD_REGION")))
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.scopeKind == 'PARENT_DIRECT')]").isEmpty())
                .andReturn().getResponse().getContentAsString();

        String listResponse = mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode aggregateData = objectMapper.readTree(aggregateResponse).path("data");
        long bucketTotal = 0;
        for (JsonNode bucket : aggregateData) bucketTotal += bucket.path("samplePointCount").asLong();
        assertEquals(objectMapper.readTree(listResponse).path("data").path("totalCount").asLong(), bucketTotal);

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + DIRECT_COUNTY_POINT + "')]").isEmpty());
    }

    @Test
    void projectsAValidatedParentIdentityIntoEveryUniquelyContainingChildScopeWithoutChangingItsIdentity()
            throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                SELECT CAST(:id AS uuid),'SURVEY_SITE','区县直属坐标落位样本点',:county,
                       'APPROVED','VALID',ST_Translate(ST_PointOnSurface(boundary.geometry),0.0007,0),
                       DATE '2026-01-01',
                       'production-tester','production-tester'
                FROM overview.administrative_boundary boundary WHERE boundary.region_code=:village
                """).param("id", DIRECT_COUNTY_POINT).param("county", COUNTY)
                .param("village", VILLAGE).update();
        insertProductionAtRegion(
                "94000000-0000-0000-0000-000000000118",
                "CORN", "APPROVED", DIRECT_COUNTY_POINT, COUNTY);
        jdbc.sql("""
                UPDATE overview.administrative_boundary
                SET geometry=ST_Translate(geometry,0.1,0.1)
                WHERE region_code=:township
                """).param("township", TOWNSHIP).update();

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + TOWNSHIP + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.data[?(@.scopeKind == 'PARENT_DIRECT')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + VILLAGE + "')].samplePointCount")
                        .value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.data[?(@.scopeKind == 'PARENT_DIRECT')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + DIRECT_COUNTY_POINT
                        + "')].regionCode").value(org.hamcrest.Matchers.hasItem(COUNTY)));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + DIRECT_COUNTY_POINT
                        + "')].regionCode").value(org.hamcrest.Matchers.hasItem(COUNTY)));
    }

    @Test
    void doesNotLeakAParentIdentityIntoAChildWhosePublishedBoundaryProtrudesOutsideItsParent()
            throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','逐级边界链路样本点',:county,
                       'APPROVED','VALID',ST_SetSRID(ST_MakePoint(123.912,47.3),4326),
                       DATE '2026-01-01','production-tester','production-tester')
                """).param("id", DIRECT_COUNTY_POINT).param("county", COUNTY).update();
        insertProductionAtRegion(
                "94000000-0000-0000-0000-000000000118",
                "CORN", "APPROVED", DIRECT_COUNTY_POINT, COUNTY);
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_Buffer(
                      ST_SetSRID(ST_MakePoint(123.912,47.3),4326),0.002)),
                    geo_json=ST_AsGeoJSON(ST_Multi(ST_Buffer(
                      ST_SetSRID(ST_MakePoint(123.912,47.3),4326),0.002)))
                WHERE region_code=:village
                """).param("village", VILLAGE).update();
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_Buffer(
                      ST_SetSRID(ST_MakePoint(123.9,47.3),4326),0.005)),
                    geo_json=ST_AsGeoJSON(ST_Multi(ST_Buffer(
                      ST_SetSRID(ST_MakePoint(123.9,47.3),4326),0.005)))
                WHERE region_code=:township
                """).param("township", TOWNSHIP).update();

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("parentCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.regionCode == '" + VILLAGE
                        + "')].samplePointCount").value(org.hamcrest.Matchers.hasItem(2)));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[?(@.samplePointId == '" + DIRECT_COUNTY_POINT
                        + "')]").isEmpty());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.samplePointId == '" + DIRECT_COUNTY_POINT
                        + "')]").isEmpty());
    }

    @Test
    void filtersListsAndExposesGeometryForCategorizedCountyAndDeeperIcons() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "FARMER")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCode").value(VILLAGE))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'PRODUCTION')].name")
                        .value(org.hamcrest.Matchers.hasItem("产情类")))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'PRODUCTION')].types[?(@.code == 'FARMER')].name")
                        .value(org.hamcrest.Matchers.hasItem("农户")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data.items[0].products.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data[0].longitude").isNumber())
                .andExpect(jsonPath("$.data[0].latitude").isNumber());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", TOWNSHIP)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("query", "不存在的样本点"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "LOGISTICS")
                        .queryParam("typeCode", "RAIL_NODE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(LOGISTICS_POINT));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", PREFECTURE)
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].roles[*].code")
                        .value(org.hamcrest.Matchers.hasItems("PRODUCTION", "MARKET", "LOGISTICS")));
    }

    @Test
    void returnsOneAuthorizedDetailWithAllApprovedAssociationsAndNoMapCommand() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data.name").value("同一跨产品样本点"))
                .andExpect(jsonPath("$.data.locationState").value("VALID"))
                .andExpect(jsonPath("$.data.associations.length()").value(2))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION')].productCode")
                        .value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].typeName")
                        .value(org.hamcrest.Matchers.hasItem("贸易商")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION' && @.productCode == 'CORN')].businessValues.SAMPLE_CONTACT.value")
                        .value(org.hamcrest.Matchers.hasItem("13900000000")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION' && @.productCode == 'CORN')].businessValues.ESTIMATED_OUTPUT_KG.value")
                        .value(org.hamcrest.Matchers.hasItem("200")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].businessValues.OPENING_INVENTORY.value")
                        .value(org.hamcrest.Matchers.hasItem("80")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].businessValues.MOISTURE.value")
                        .value(org.hamcrest.Matchers.hasItem("13.6")))
                .andExpect(jsonPath("$.data.longitude").doesNotExist())
                .andExpect(jsonPath("$.data.pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "FARMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(1))
                .andExpect(jsonPath("$.data.associations[0].categoryCode").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.associations[0].typeCode").value("FARMER"));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY)
                        .queryParam("categoryCode", "LOGISTICS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OVERVIEW_SAMPLE_POINT_NOT_FOUND"));

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", DRAFT_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OVERVIEW_SAMPLE_POINT_NOT_FOUND"));
    }

    @Test
    void doesNotUseDraftPointRegionToPlaceAnApprovedBusinessSource() throws Exception {
        jdbc.sql("""
                UPDATE registry.sample_point
                SET region_code='230281',location_state='MISSING',governed_point=NULL,
                    containment_boundary_sha256=NULL,containment_boundary_revision=NULL
                WHERE sample_point_id=CAST(:point AS uuid)
                """).param("point", DRAFT_POINT).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230281"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(0));
    }

    @Test
    void excludesAssociationsWhoseBusinessSourceRegionIsOutsideTheReaderScope() throws Exception {
        insertProductionAtRegion("94000000-0000-0000-0000-000000000108", "CORN", "APPROVED",
                SURVEY_POINT, "230281");
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id='production-tester'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('production-tester',:region)
                """).param("region", VILLAGE).update();

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(2));
    }

    @Test
    void enforcesExistingRegionAndObjectTypeAuthorization() throws Exception {
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id='production-tester'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('production-tester',:region)
                """).param("region", VILLAGE).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230281"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "TRADER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", COUNTY)
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT));
    }

    @Test
    void cannotReadApprovedRowsBeforeTheirTransactionCommits() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO production.production_record(
                      record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                      cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id)
                    VALUES('94000000-0000-0000-0000-000000000109','CORN','FARMER',?,DATE '2026-08-05',
                      TIMESTAMPTZ '2026-08-06 08:00:00+08',10,20,'APPROVED','production-tester',
                      CAST(? AS uuid))
                    """)) {
                statement.setString(1, VILLAGE);
                statement.setString(2, SURVEY_POINT);
                statement.executeUpdate();

                mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                                .principal(() -> "production-tester")
                                .queryParam("year", "2026")
                                .queryParam("productCode", "CORN")
                                .queryParam("regionCode", VILLAGE))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.associations.length()").value(2));
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertRegionAndBoundaryFixtures() {
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "契约测试乡", "230202", "TOWNSHIP", 997);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE, "契约测试村", TOWNSHIP, "VILLAGE", 1);
        jdbc.sql("""
                WITH township AS (
                  SELECT ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9,47.3),4326),0.005)) geometry
                )
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                SELECT :county,ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9,47.3),4326),0.02)),
                       'sample-point contract fixture','urn:test:sample-point-query',
                       'test-v1','Test fixture',:county,DATE '2026-08-11',repeat('7',64)
                UNION ALL
                SELECT :township,geometry,'sample-point contract fixture','urn:test:sample-point-query',
                       'test-v1','Test fixture',:township,DATE '2026-08-11',repeat('9',64)
                FROM township
                UNION ALL
                SELECT :village,ST_Multi(ST_Buffer(ST_PointOnSurface(geometry),0.002)),
                       'sample-point contract fixture','urn:test:sample-point-query','test-v1',
                       'Test fixture',:village,DATE '2026-08-11',repeat('8',64)
                FROM township
                """).param("county", COUNTY).param("township", TOWNSHIP)
                .param("village", VILLAGE).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()").query(Object.class).single();
    }

    private void insertSamplePointFixtures() {
        insertValidPoint(SURVEY_POINT, "SURVEY_SITE", "同一跨产品样本点", "APPROVED");
        insertValidLogisticsPoint();
        insertValidPoint(DRAFT_POINT, "SURVEY_SITE", "未批准样本点", "DRAFT");
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','缺少坐标样本点',:region,'APPROVED','MISSING',
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("id", MISSING_POINT).param("region", VILLAGE).update();
    }

    private void insertValidPoint(String id, String kind, String name, String approvalState) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                SELECT CAST(:id AS uuid),:kind,:name,:region,:approval,'VALID',
                       ST_PointOnSurface(boundary.geometry),DATE '2026-01-01',
                       'production-tester','production-tester'
                FROM overview.administrative_boundary boundary WHERE boundary.region_code=:region
                """).param("id", id).param("kind", kind).param("name", name)
                .param("region", VILLAGE).param("approval", approvalState).update();
    }

    private void insertValidLogisticsPoint() {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                SELECT CAST(:id AS uuid),'LOGISTICS_NODE','铁路物流节点',:region,'APPROVED','VALID',
                       ST_Translate(ST_PointOnSurface(boundary.geometry),0.0001,0.0001),DATE '2026-01-01',
                       'production-tester','production-tester'
                FROM overview.administrative_boundary boundary WHERE boundary.region_code=:region
                """).param("id", LOGISTICS_POINT).param("region", VILLAGE).update();
    }

    private void insertApprovedSourceFixtures() {
        insertProduction("94000000-0000-0000-0000-000000000101", "CORN", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000102", "SOYBEAN", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000103", "RICE", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000104", "CORN", "DRAFT", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000110", "CORN", "PENDING_REVIEW", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000111", "CORN", "RETURNED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000105", "CORN", "APPROVED", MISSING_POINT);
        insertProduction("94000000-0000-0000-0000-000000000106", "CORN", "APPROVED", null);
        insertProduction("94000000-0000-0000-0000-000000000107", "CORN", "APPROVED", DRAFT_POINT);
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by,party_id,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000201','CORN','TRADER',:region,
                  DATE '2026-08-05',TIMESTAMPTZ '2026-08-06 08:00:00+08',2500,2580,'BOTH',0,0,0,
                  'BULK','APPROVED','market-tester',NULL,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by,party_id,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000202','RICE','RICE_MILL',:region,
                  DATE '2026-08-05',TIMESTAMPTZ '2026-08-06 08:00:00+08',2600,2680,'BOTH',0,0,0,
                  'BULK','APPROVED','market-tester',NULL,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES('94000000-0000-0000-0000-000000000101','PROD_SAMPLE_CONTACT','13900000000'),
                      ('94000000-0000-0000-0000-000000000101','PROD_SURVEYOR_NAME','王雷'),
                      ('94000000-0000-0000-0000-000000000101','PROD_SURVEYOR_PHONE','13800000000'),
                      ('94000000-0000-0000-0000-000000000101','PROD_HARVEST_AREA_MU','9.5'),
                      ('94000000-0000-0000-0000-000000000101','PROD_GROWTH_STATUS','长势良好')
                """).update();
        jdbc.sql("""
                INSERT INTO production.production_record_quality(record_id,quality_code,value)
                VALUES('94000000-0000-0000-0000-000000000101','MOISTURE',14.2)
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,field_code,value,product_code,domain_binding)
                VALUES('94000000-0000-0000-0000-000000000201','MKT_SAMPLE_CONTACT',
                         '13700000000','CORN','EXTENSION'),
                      ('94000000-0000-0000-0000-000000000201','MKT_SURVEYOR_NAME',
                         '李敏','CORN','EXTENSION'),
                      ('94000000-0000-0000-0000-000000000201','MKT_SURVEYOR_PHONE',
                         '13600000000','CORN','EXTENSION')
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                  record_id,fact_code,value,product_code,object_type_code)
                VALUES('94000000-0000-0000-0000-000000000201','OPENING_INVENTORY',80,'CORN','TRADER'),
                      ('94000000-0000-0000-0000-000000000201','PURCHASE_VOLUME',12,'CORN','TRADER'),
                      ('94000000-0000-0000-0000-000000000201','SALES_VOLUME',9,'CORN','TRADER'),
                      ('94000000-0000-0000-0000-000000000201','MOISTURE',13.6,'CORN','TRADER')
                """).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code,sample_point_id)
                VALUES('QUERY_RAIL','铁路物流节点','RAIL_NODE',:region,CAST(:point AS uuid)),
                      ('QUERY_ROAD','公路物流节点','ROAD_NODE',:region,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", LOGISTICS_POINT).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_code,destination_region_code,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,
                  version,created_by,last_modified_by,created_at,updated_at)
                VALUES('94000000-0000-0000-0000-000000000301','CORN','2026-W32',DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',:region,'QUERY_RAIL',:region,'QUERY_ROAD',
                  'RAIL','INFLOW','测试来源','测试员','APPROVED',0,'logistics-tester','logistics-tester',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',TIMESTAMPTZ '2026-08-06 08:00:00+08')
                """).param("region", VILLAGE).update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES('94000000-0000-0000-0000-000000000301','ROUTE_VOLUME',36,'吨')
                """).update();
    }

    private void insertProduction(String id, String product, String status, String pointId) {
        insertProductionAtRegion(id, product, status, pointId, VILLAGE);
    }

    private void insertProductionAtRegion(
            String id, String product, String status, String pointId, String regionCode) {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,return_reason,last_modified_by,sample_point_id)
                VALUES(:id,:product,'FARMER',:region,DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',10,20,:status,
                  CASE WHEN :status='RETURNED' THEN '退回测试' ELSE NULL END,'production-tester',
                  CAST(:point AS uuid))
                """).param("id", id).param("product", product).param("region", regionCode)
                .param("status", status).param("point", pointId).update();
    }

    private void clean() {
        jdbc.sql("""
                TRUNCATE registry.sample_point,production.production_record,market.market_record,
                  logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM platform.design_sample_point").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE region_code IN (:township,:village)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE region_code IN (:township,:village)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN (:county,:township,:village)")
                .param("county", COUNTY).param("township", TOWNSHIP)
                .param("village", VILLAGE).update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, java.util.List.of(VILLAGE, TOWNSHIP));
    }

    private JsonNode responseData(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
