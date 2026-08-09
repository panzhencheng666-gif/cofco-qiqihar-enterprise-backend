package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(MarketMonitoringRestIntegrationTest.FixedClockConfiguration.class)
class MarketMonitoringRestIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @BeforeEach
    void clearRecords() {
        JdbcClient.create(dataSource).sql(
                "TRUNCATE platform.business_audit_event,market.market_record,evidence.evidence_photo CASCADE")
                .update();
    }
    @AfterEach
    void clearAuditEvents() {
        JdbcClient.create(dataSource).sql("TRUNCATE platform.business_audit_event").update();
    }

    @Test void createsAndTransitionsCornFeedMillWithBothObjectPrices() throws Exception {
        String body = draftBody("CORN", "FEED_MILL", "MOISTURE", null);
        String id = mockMvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTER_NAME").value("市场测试员"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_LATITUDE").value("47.3543000"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id).principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id).principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.facts.MOISTURE").value("14.6000"));
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'MARKET_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void capturesBothObjectPricesWithoutExposingATradeDirection() throws Exception {
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_TRADE_DIRECTION')]").isEmpty())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_ACTUAL_TRADE_PRICE')]").isEmpty())
                .andExpect(jsonPath(
                        "$.data.coreFields[?(@.code == 'MKT_PURCHASE_BASE_PRICE' && @.required == true)]").exists())
                .andExpect(jsonPath(
                        "$.data.coreFields[?(@.code == 'MKT_SALE_BASE_PRICE' && @.required == true)]").exists())
                .andExpect(jsonPath("$.data.groups[?(@.category == 'PURCHASE')].label").value("采购业务"))
                .andExpect(jsonPath("$.data.groups[?(@.label =~ /.*成交.*/)]").isEmpty());

        String body = """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"FEED_MILL","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2380",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",%s},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},
                 "evidencePhotoIds":["%s"]}
                """.formatted(submissionMetadata(), stageEvidencePhoto());

        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_TRADE_DIRECTION").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_ACTUAL_TRADE_PRICE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").value("2380.0000"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT trade_direction FROM market.market_record WHERE record_id = :id
                """).param("id", id).query(String.class).single()).isEqualTo("BOTH");
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

    @Test
    void exposesConcreteReportedObjectSeparatelyFromItsCategory() throws Exception {
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SAMPLE_NAME')].label")
                        .value("填报对象/客户名称"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SAMPLE_NAME')].required")
                        .value(true))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_REPORTER_PHONE')].label")
                        .value("填报人联系方式"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SAMPLE_CONTACT')].label")
                        .value("填报对象/客户联系方式"));
    }

    @Test
    void rejectsMissingConcreteReportedObjectWithoutCreatingARecord() throws Exception {
        long before = recordCount();
        String body = draftBody("CORN", "FEED_MILL", "MOISTURE", null)
                .replace("\"MKT_SAMPLE_NAME\":\"齐齐哈尔第一粮店\",", "");

        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));

        org.assertj.core.api.Assertions.assertThat(recordCount()).isEqualTo(before);
    }

    @Test
    void acceptsCodeKeyedCoreValuesAndRoundTripsADatabaseDefinedTextControl() throws Exception {
        String body = """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"FEED_MILL","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_SOURCE_NOTE":"产地直采",%s},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},
                 "evidencePhotoIds":["%s"]}
                """.formatted(submissionMetadata(), stageEvidencePhoto());

        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_SOURCE_NOTE").value("产地直采"))
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.objectTypeCode").doesNotExist())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_OBJECT_TYPE").value("FEED_MILL"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SOURCE_NOTE").value("产地直采"));
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.MKT_SOURCE_NOTE").value("产地直采"));

        String modified = body.replace("产地直采", "铁路到库");
        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(modified, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.coreValues.MKT_SOURCE_NOTE").value("铁路到库"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_LONGITUDE").value("123.9182000"));

        String cleared = body.replaceAll(
                ",\\s*\"MKT_SOURCE_NOTE\":\"产地直采\"", "");
        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(cleared, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.coreValues.MKT_SOURCE_NOTE")
                        .value(org.hamcrest.Matchers.nullValue()));
        org.assertj.core.api.Assertions.assertThat(extensionValueCount(id)).isEqualTo(6);

        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(modified.replace("14.6", "15.2"), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_RECORD_VERSION_CONFLICT"));
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.coreValues.MKT_SOURCE_NOTE")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.facts.MOISTURE").value("14.6000"));
        org.assertj.core.api.Assertions.assertThat(extensionValueCount(id)).isEqualTo(6);

        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN").queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SOURCE_NOTE')].controlType")
                        .value("TEXT"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SOURCE_NOTE')].capability")
                        .value("GENERIC"));
    }

    @Test
    @Transactional
    @Rollback
    void transactionFixtureExposesAndRoundTripsAProductExtensionWithoutLeakingIt() throws Exception {
        installDynamicExtensionFixture();
        String cornBody = draftBody("CORN", "FEED_MILL", "MOISTURE", null)
                .replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                        "\"MKT_PACKAGING_FORM\":\"BULK\",\"MKT_TEST_SOURCE_NOTE\":\"玉米产地直采\"");
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(cornBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_TEST_SOURCE_NOTE").value("玉米产地直采"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_TEST_SOURCE_NOTE").value("玉米产地直采"));
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.MKT_TEST_SOURCE_NOTE")
                        .value("玉米产地直采"));
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_TEST_SOURCE_NOTE')].description")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())));

        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("objectTypeCode", "DEEP_PROCESSOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_TEST_SOURCE_NOTE')]")
                        .doesNotExist());
        long before = recordCount();
        String soybeanBody = draftBody("SOYBEAN", "DEEP_PROCESSOR", "PROTEIN", null)
                .replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                        "\"MKT_PACKAGING_FORM\":\"BULK\",\"MKT_TEST_SOURCE_NOTE\":\"越权值\"");
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(soybeanBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        org.assertj.core.api.Assertions.assertThat(recordCount()).isEqualTo(before);
    }

    private void installDynamicExtensionFixture() {
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                INSERT INTO platform.market_core_field_definition(
                    code, label, control_type, sort_order, description,
                    domain_binding, capability, required)
                VALUES ('MKT_TEST_SOURCE_NOTE', '测试来源说明', 'TEXT', 906, NULL,
                        'EXTENSION', 'GENERIC', false)
                """).update();
        client.sql("""
                INSERT INTO platform.field_definition(code, name, value_type)
                VALUES ('MKT_TEST_SOURCE_NOTE', '测试来源说明', 'TEXT')
                """).update();
        client.sql("""
                INSERT INTO platform.page_definition_field(
                    product_code, business_domain, page_kind, field_code, sort_order)
                VALUES ('CORN', 'MARKET', 'MONITORING', 'MKT_TEST_SOURCE_NOTE', 61)
                """).update();
        client.sql("""
                INSERT INTO platform.page_column_group_field(
                    product_code, business_domain, page_kind, group_code,
                    field_code, sort_order, unit, description)
                VALUES ('CORN', 'MARKET', 'MONITORING', 'MARKET',
                        'MKT_TEST_SOURCE_NOTE', 61, NULL, NULL)
                """).update();
        client.sql("""
                INSERT INTO platform.market_core_field_applicability(
                    product_code, business_domain, page_kind, field_code, domain_binding)
                VALUES ('CORN', 'MARKET', 'MONITORING', 'MKT_TEST_SOURCE_NOTE', 'EXTENSION')
                """).update();
        client.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        client.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    @Test
    void rejectsNonPlainDecimalSyntaxWithoutWriting() throws Exception {
        String valid = draftBody("CORN", "FEED_MILL", "MOISTURE", null);
        for (String invalid : List.of("+1", "1e3", "1E3")) {
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(valid.replace("\"MKT_PURCHASE_BASE_PRICE\":\"2300\"",
                                    "\"MKT_PURCHASE_BASE_PRICE\":\"" + invalid + "\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(valid.replace("\"MOISTURE\":\"14.6\"",
                                    "\"MOISTURE\":\"" + invalid + "\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        }
        org.assertj.core.api.Assertions.assertThat(recordCount()).isZero();
    }

    @Test
    void acceptsCoordinateBoundariesAndRejectsOutOfRangeCoordinatesWithoutWriting() throws Exception {
        String valid = draftBody("CORN", "FEED_MILL", "MOISTURE", null);
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("\"47.3543\"", "\"-90\"")
                                .replace("\"123.9182\"", "\"180\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_LATITUDE").value("-90.0000000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_LONGITUDE").value("180.0000000"));

        long before = recordCount();
        for (String body : List.of(
                valid.replace("\"47.3543\"", "\"90.0000001\""),
                valid.replace("\"123.9182\"", "\"-180.0000001\""))) {
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        }
        assertThat(recordCount()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0} fact uses the shared plain-decimal parser")
    @MethodSource("decimalFactCategories")
    void rejectsNonPlainDecimalSyntaxAcrossEveryFactCategoryWithoutWriting(
            String category, String product, String objectType, String factCode) throws Exception {
        for (String invalid : List.of("+1", "1e3", "1E3")) {
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(singleFactDraft(product, objectType, factCode, invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
            org.assertj.core.api.Assertions.assertThat(recordCount()).isZero();
        }

        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleFactDraft(product, objectType, factCode, "14.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.facts." + factCode).value("14.5000"));
        org.assertj.core.api.Assertions.assertThat(recordCount()).isOne();
    }

    @Test
    void rejectsUnknownAndReadonlyKeysEvenWhenTheirValuesAreNull() throws Exception {
        String valid = draftBody("CORN", "FEED_MILL", "MOISTURE", null);
        for (String code : List.of("MKT_UNKNOWN", "MKT_ACTUAL_TRADE_PRICE")) {
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(valid.replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                                    "\"MKT_PACKAGING_FORM\":\"BULK\",\"" + code + "\":null")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        }
        org.assertj.core.api.Assertions.assertThat(recordCount()).isZero();
    }

    @Test
    void rejectsUnknownReadonlyPseudocodeAndRetiredDirectionCoreValues() throws Exception {
        String valid = draftBody("CORN", "FEED_MILL", "MOISTURE", null);
        for (String body : List.of(
                valid.replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                        "\"MKT_PACKAGING_FORM\":\"BULK\",\"MKT_UNKNOWN\":\"x\""),
                valid.replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                        "\"MKT_PACKAGING_FORM\":\"BULK\",\"MKT_ACTUAL_TRADE_PRICE\":\"1\""),
                valid.replace("\"MOISTURE\":\"14.6\"", "\"CORN_MOISTURE\":\"14.6\""),
                valid.replace("\"MKT_PACKAGING_FORM\":\"BULK\"",
                        "\"MKT_PACKAGING_FORM\":\"BULK\",\"MKT_TRADE_DIRECTION\":\"PURCHASE\""))) {
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        org.assertj.core.api.Assertions.assertThat(recordCount()).isZero();
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
                        .value("被调查对象当前对外采购报价"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_SALE_BASE_PRICE')].description")
                        .value("被调查对象当前对外销售报价"))
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_TRADE_DIRECTION')]").isEmpty())
                .andExpect(jsonPath("$.data.coreFields[?(@.code == 'MKT_ACTUAL_TRADE_PRICE')]").isEmpty())
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
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").value("2300.0000"))
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
                .andExpect(jsonPath("$.data.items[0].values.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_SALE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_REPORTER_PHONE").value("13800000000"))
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
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.returnReason").value("请补充凭证"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"));
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(4));

        long before = recordCount();
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("CORN", "FEED_MILL", "SALES_VOLUME", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_MARKET_FACT"));
        org.assertj.core.api.Assertions.assertThat(recordCount()).isEqualTo(before);
    }

    @Test
    void putMayChangeParentContextBeforeReplacingFactsWhenTheFinalStateIsApplicable()
            throws Exception {
        String initial = singleFactDraft(
                "CORN", "FEED_MILL", "PROCESSING_INPUT", "18.5");
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(initial))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.facts.PROCESSING_INPUT").value("18.5000"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        String replacement = singleFactDraft(
                "CORN", "BREEDING_FACTORY", "PURCHASE_VOLUME", "21.5");
        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(replacement, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.coreValues.MKT_OBJECT_TYPE")
                        .value("BREEDING_FACTORY"))
                .andExpect(jsonPath("$.data.facts.PROCESSING_INPUT").doesNotExist())
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("21.5000"));

        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(initial, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_RECORD_VERSION_CONFLICT"));
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.coreValues.MKT_OBJECT_TYPE")
                        .value("BREEDING_FACTORY"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("21.5000"));
    }

    @Test
    void stateTransitionsKeepReportedAtAndUseTheApplicationClockForUpdatedAt() throws Exception {
        String approvedId = create("CORN", "FEED_MILL", "MOISTURE");
        resetTransitionTimes(approvedId);

        mockMvc.perform(post("/api/v1/market-records/{id}/submit", approvedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTED_AT").value("2026-08-02T00:00:00Z"));
        assertTransitionTimes(approvedId);

        resetTransitionTimes(approvedId);
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", approvedId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTED_AT").value("2026-08-02T00:00:00Z"));
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
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTED_AT").value("2026-08-02T00:00:00Z"));
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
    void rejectsPathologicalAndMetadataOverPrecisionCoreDecimalsWithoutWrites() throws Exception {
        for (String latitude : List.of("1E999999999", "47.12345678")) {
            String body = draftBody("CORN", "FEED_MILL", "MOISTURE", null)
                    .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"",
                            "\"MKT_SAMPLE_LATITUDE\":\"" + latitude + "\"");
            mockMvc.perform(post("/api/v1/market-records")
                            .principal(() -> "market-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));
        }

        assertThat(recordCount()).isZero();
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

    @Test
    void activeMarketDefinitionsDoNotExposeRetiredInflowOrStorageLossInputs() throws Exception {
        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "RESERVE_ENTERPRISE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[*].fields[?(@.code == 'STOCK_INFLOW')]").isEmpty())
                .andExpect(jsonPath("$.data.groups[*].fields[?(@.code == 'STORAGE_LOSS')]").isEmpty())
                .andExpect(jsonPath("$.data.groups[*].fields[?(@.code == 'STOCK_OUTFLOW')]").exists())
                .andExpect(jsonPath("$.data.groups[*].fields[?(@.code == 'ENDING_INVENTORY')]").exists());
    }

    private String create(String product, String objectType, String qualityCode) throws Exception {
        return mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(product, objectType, qualityCode, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String draftBody(String product, String objectType, String qualityCode, Long version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","coreValues":{
                 "MKT_OBJECT_TYPE":"%s","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",%s},
                 "facts":{"PURCHASE_VOLUME":"12","%s":"14.6"},
                 "evidencePhotoIds":["%s"]%s}
                """.formatted(
                        product, objectType, submissionMetadata(), qualityCode,
                        stageEvidencePhoto(), versionValue);
    }

    private String singleFactDraft(
            String product, String objectType, String factCode, String value) {
        return """
                {"productCode":"%s","coreValues":{
                 "MKT_OBJECT_TYPE":"%s","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",%s},
                 "facts":{"%s":"%s"},"evidencePhotoIds":["%s"]}
                """.formatted(
                        product, objectType, submissionMetadata(), factCode, value,
                        stageEvidencePhoto());
    }

    private UUID stageEvidencePhoto() {
        UUID id = UUID.randomUUID();
        JdbcClient.create(dataSource).sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','market-fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,repeat('a',64),now(),47.3543,123.9182,'市场测试水印','market-tester',now())
                """).param("id", id).update();
        return id;
    }

    private static String submissionMetadata() {
        return """
                "MKT_REPORTER_NAME":"测试填报员","MKT_REPORTER_PHONE":"13800000000",
                "MKT_SAMPLE_NAME":"齐齐哈尔第一粮店","MKT_SAMPLE_CONTACT":"13900000000",
                "MKT_SAMPLE_LATITUDE":"47.3543",
                "MKT_SAMPLE_LONGITUDE":"123.9182"
                """.strip();
    }

    private long recordCount() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single();
    }

    private long extensionValueCount(String id) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT count(*) FROM market.market_record_core_value
                        WHERE record_id = :id
                        """).param("id", id).query(Long.class).single();
    }

    private String versioned(String draft, long version) {
        return draft.strip().replaceFirst("}$", ",\"version\":" + version + "}");
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

    private static Stream<Arguments> decimalFactCategories() {
        return Stream.of(
                Arguments.of("QUALITY", "CORN", "FEED_MILL", "MOISTURE"),
                Arguments.of("PURCHASE", "CORN", "FEED_MILL", "PURCHASE_VOLUME"),
                Arguments.of("SALES", "CORN", "TRADER", "SALES_VOLUME"),
                Arguments.of("PROCESSING", "CORN", "FEED_MILL", "PROCESSING_INPUT"),
                Arguments.of("INVENTORY", "CORN", "FEED_MILL", "ENDING_INVENTORY"));
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
