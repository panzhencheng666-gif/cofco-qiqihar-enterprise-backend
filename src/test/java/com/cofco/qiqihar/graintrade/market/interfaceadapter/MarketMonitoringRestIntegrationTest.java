package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(MarketMonitoringRestIntegrationTest.FixedClockConfiguration.class)
class MarketMonitoringRestIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @BeforeEach void clearRecords() { JdbcClient.create(dataSource).sql("DELETE FROM market.market_record").update(); }

    @Test void createsAndTransitionsCornFeedMillUsingServerCalculatedActualPrice() throws Exception {
        String body = """
            {"productCode":"CORN","objectTypeCode":"FEED_MILL","regionCode":"230200",
             "tradeDate":"2026-08-01","direction":"PURCHASE","purchaseBasePrice":"2300",
             "saleBasePrice":null,"carriageBoardAmount":"36","packagingAmount":"12","freightAmount":"72","packagingForm":"BULK",
             "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"}}
            """;
        String id = mockMvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.actualTradePrice").value("2420.0000"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id).principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id).principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.facts.MOISTURE").value("14.6000"));
    }

    @Test void exposesOnlyApplicableFeedMillDefinitionsAndRejectsUnauthenticatedWrites() throws Exception {
        mockMvc.perform(get("/api/v1/market-record-definitions").queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[?(@.category == 'PURCHASE')].fields[?(@.code == 'PURCHASE_VOLUME')]").exists())
                .andExpect(jsonPath("$.data.groups[?(@.category == 'QUALITY')].fields[?(@.code == 'MOISTURE')]").exists())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_PACKAGING_FORM')].options[?(@.value == 'BULK')].label").value("散粮"));
        mockMvc.perform(post("/api/v1/market-records").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0}/{1} exposes database-owned form definition")
    @MethodSource("allApplicableContexts")
    void exposesOrderedFormDefinitionForEveryApplicableObject(
            String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", product).queryParam("objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.coreFields[0].label").value("对象类型"))
                .andExpect(jsonPath("$.data.coreFields[0].options[?(@.value == '" + objectType + "')]").exists())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_REPORTED_AT' && @.controlType == 'READONLY_DATETIME')]").exists())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_PURCHASE_BASE_PRICE')].description")
                        .value("采购基础价未包含车板、包装和运费组成"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SALE_BASE_PRICE')].description")
                        .value("销售基础价未包含车板、包装和运费组成"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_ACTUAL_TRADE_PRICE')].description")
                        .value("实际成交价已包含车板、包装和运费组成"))
                .andExpect(jsonPath("$.data.groups.length()").value(5))
                .andExpect(jsonPath("$.data.groups[0].label").value("质量指标"))
                .andExpect(jsonPath("$.data.groups[?(@.category == 'QUALITY')].fields[?(@.code == '" + qualityCode + "')]").exists());
    }

    @ParameterizedTest(name = "{0}/{1} round-trips purchase volume and quality")
    @MethodSource("quantityQualityContexts")
    void fullWriteReviewAndListPreserveQuantityQualityAndServerPrice(
            String product, String objectType, String qualityCode) throws Exception {
        String id = create(product, objectType, qualityCode);
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualTradePrice").value("2420.0000"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"))
                .andExpect(jsonPath("$.data.facts." + qualityCode).value("14.6000"));
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"));
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PURCHASE_VOLUME").value("12.0000"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_REPORTED_AT").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].values.MKT_ACTUAL_TRADE_PRICE").value("2420.0000"))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"));
    }

    @Test
    void strictQueryCasReturnPutAndNoPartialWriteUseTypedErrors() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("unknown", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD_QUERY"));
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "2147483648").queryParam("pageSize", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD_QUERY"));
        mockMvc.perform(post("/api/v1/market-records")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        String id = create("CORN", "FEED_MILL", "MOISTURE");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_RECORD_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/v1/market-records/{id}/return", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"请补充凭证\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.facts.MOISTURE").value("14.6000"));
        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("CORN", "FEED_MILL", "MOISTURE", 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"));

        long before = recordCount();
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("CORN", "FEED_MILL", "SALES_VOLUME", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_MARKET_FACT"));
        org.assertj.core.api.Assertions.assertThat(recordCount()).isEqualTo(before);
    }

    @Test
    void stateTransitionsKeepReportedAtAndUseTheApplicationClockForUpdatedAt() throws Exception {
        String approvedId = create("CORN", "FEED_MILL", "MOISTURE");
        resetTransitionTimes(approvedId);

        mockMvc.perform(post("/api/v1/market-records/{id}/submit", approvedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportedAt").value("2026-08-02T00:00:00Z"));
        assertTransitionTimes(approvedId);

        resetTransitionTimes(approvedId);
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", approvedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportedAt").value("2026-08-02T00:00:00Z"));
        assertTransitionTimes(approvedId);

        String returnedId = create("CORN", "FEED_MILL", "MOISTURE");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", returnedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        resetTransitionTimes(returnedId);
        mockMvc.perform(post("/api/v1/market-records/{id}/return", returnedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"请补充凭证\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportedAt").value("2026-08-02T00:00:00Z"));
        assertTransitionTimes(returnedId);
    }

    private void resetTransitionTimes(String id) {
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                        UPDATE market.market_record
                        SET reported_at = '2026-08-02T08:00:00+08:00',
                            updated_at = '2026-08-02T08:00:00+08:00'
                        WHERE record_id = :id
                        """).param("id", id).update();
    }

    private void assertTransitionTimes(String id) {
        JdbcClient client = JdbcClient.create(dataSource);
        org.assertj.core.api.Assertions.assertThat(client.sql("""
                        SELECT reported_at = '2026-08-02T00:00:00Z'::timestamptz
                        FROM market.market_record WHERE record_id = :id
                        """).param("id", id).query(Boolean.class).single()).isTrue();
        org.assertj.core.api.Assertions.assertThat(client.sql("""
                        SELECT updated_at = '2026-08-03T04:05:06Z'::timestamptz
                        FROM market.market_record WHERE record_id = :id
                        """).param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void definitionRejectsUnknownCaseVariantBlankAndRepeatedProductContexts() throws Exception {
        for (String product : List.of("UNKNOWN", "corn", " ")) {
            mockMvc.perform(get("/api/v1/market-record-definitions").queryParam("productCode", product))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        }
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN", "RICE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
    }

    private String create(String product, String objectType, String qualityCode) throws Exception {
        return mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(product, objectType, qualityCode, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
    }

    private String draftBody(String product, String objectType, String qualityCode, Long version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","objectTypeCode":"%s","regionCode":"230200",
                 "tradeDate":"2026-08-01","direction":"PURCHASE","purchaseBasePrice":"2300",
                 "saleBasePrice":null,"carriageBoardAmount":"36","packagingAmount":"12",
                 "freightAmount":"72","packagingForm":"BULK",
                 "facts":{"PURCHASE_VOLUME":"12","%s":"14.6"}%s}
                """.formatted(product, objectType, qualityCode, versionValue);
    }

    private long recordCount() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedMarketClock() {
            return Clock.fixed(
                    Instant.parse("2026-08-03T04:05:06Z"),
                    ZoneId.of("Asia/Shanghai"));
        }
    }

    private static Stream<Arguments> allApplicableContexts() {
        return Stream.of(
                context("CORN", "TRADER", "MOISTURE"),
                context("CORN", "DEEP_PROCESSOR", "MOISTURE"),
                context("CORN", "WHOLESALE_MARKET", "MOISTURE"),
                context("CORN", "RESERVE_ENTERPRISE", "MOISTURE"),
                context("CORN", "BREEDING_FACTORY", "MOISTURE"),
                context("CORN", "FEED_MILL", "MOISTURE"),
                context("SOYBEAN", "TRADER", "PROTEIN"),
                context("SOYBEAN", "DEEP_PROCESSOR", "PROTEIN"),
                context("SOYBEAN", "WHOLESALE_MARKET", "PROTEIN"),
                context("SOYBEAN", "RESERVE_ENTERPRISE", "PROTEIN"),
                context("RICE", "TRADER", "MILLING_YIELD"),
                context("RICE", "DEEP_PROCESSOR", "MILLING_YIELD"),
                context("RICE", "WHOLESALE_MARKET", "MILLING_YIELD"),
                context("RICE", "RESERVE_ENTERPRISE", "MILLING_YIELD"),
                context("RICE", "RICE_MILL", "MILLING_YIELD"));
    }

    private static Stream<Arguments> quantityQualityContexts() {
        return Stream.of(
                context("CORN", "DEEP_PROCESSOR", "MOISTURE"),
                context("SOYBEAN", "DEEP_PROCESSOR", "PROTEIN"),
                context("RICE", "DEEP_PROCESSOR", "MILLING_YIELD"),
                context("CORN", "BREEDING_FACTORY", "MOISTURE"),
                context("CORN", "FEED_MILL", "MOISTURE"),
                context("RICE", "RICE_MILL", "MILLING_YIELD"));
    }

    private static Arguments context(String product, String objectType, String qualityCode) {
        return Arguments.of(product, objectType, qualityCode);
    }
}
