package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryService;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotification;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private static final String APPLICANT_LOGIN = "qiqihar_master_data_applicant_login";
    private static final String APPLICANT_ROLE = "qiqihar_master_data_applicant";
    private static final String REVIEWER_LOGIN = "qiqihar_master_data_reviewer_login";
    private static final String REVIEWER_ROLE = "qiqihar_master_data_reviewer";
    private static final String APPLIER_LOGIN = "qiqihar_master_data_applier_login";
    private static final String APPLIER_ROLE = "qiqihar_master_data_applier";
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired BusinessNotificationRepository notifications;
    @Autowired BusinessEventDeliveryService eventDeliveries;
    @BeforeEach
    void clearRecords() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql(
                "TRUNCATE platform.business_audit_event,market.market_record,evidence.evidence_photo,"
                        + "registry.sample_point,market.business_party CASCADE")
                .update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE source_url='urn:test:market-sample-point'")
                .update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230200',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123,47),4326),0.5)),
                  'market sample-point contract fixture','urn:test:market-sample-point','test-v1',
                  'Test fixture','230200',DATE '2026-08-11',repeat('7',64))
                ON CONFLICT (region_code) DO NOTHING
                """).update();
    }
    @AfterEach
    void clearAuditEvents() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DROP TRIGGER IF EXISTS reject_market_correction_event_for_test "
                        + "ON platform.business_event_outbox")
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS platform.reject_market_correction_event_for_test()")
                .update();
        jdbc.sql("DROP TRIGGER IF EXISTS delay_market_resolution_revision_for_test "
                        + "ON registry.sample_subject_resolution_revision")
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS registry.delay_market_resolution_revision_for_test()")
                .update();
        jdbc.sql("DROP TRIGGER IF EXISTS delay_legacy_subject_identity_for_test "
                        + "ON registry.sample_point_subject_identity")
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS registry.delay_legacy_subject_identity_for_test()")
                .update();
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
        jdbc.sql("""
                DO $reset$
                BEGIN
                  UPDATE overview.region_surplus_calculation_contract
                  SET status_code='PENDING',effective_from=NULL,effective_to=NULL,
                    activated_by=NULL,activation_basis=NULL,activated_at=NULL
                  WHERE version_code='REGION_SURPLUS_V2';
                  UPDATE overview.region_surplus_calculation_contract
                  SET status_code='ACTIVE',
                    effective_from=TIMESTAMPTZ '1900-01-01 00:00:00+08',effective_to=NULL,
                    activated_by='V118_MIGRATION',
                    activation_basis='保留迁移前已审核记录及不可变报告快照',
                    activated_at=TIMESTAMPTZ '1900-01-01 00:00:00+08'
                  WHERE version_code='REGION_SURPLUS_V1';
                END
                $reset$
                """).update();
        jdbc.sql("TRUNCATE overview.region_surplus_calculation_activation_audit").update();
    }

    @Test void createsAndTransitionsCornFeedMillWithBothObjectPrices() throws Exception {
        String body = draftBody("CORN", "FEED_MILL", "MOISTURE", null)
                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"", "\"MKT_SAMPLE_LATITUDE\":\"47\"")
                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"", "\"MKT_SAMPLE_LONGITUDE\":\"123\"");
        String id = mockMvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").value("2300.0000"))
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTER_NAME").value("市场测试员"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_LATITUDE").value("47.0000000"))
                .andExpect(jsonPath("$.data.facts.PURCHASE_VOLUME").value("12.0000"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id).principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id).principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.facts.MOISTURE").value("14.6000"));
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'MARKET_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(3L);
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT party_id IS NULL AND sample_point_id IS NULL
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void displayFieldsCannotEstablishOrReuseIdentityAcrossProducts() throws Exception {
        String corn = draftBody("CORN", "TRADER", "MOISTURE", null)
                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"", "\"MKT_SAMPLE_LATITUDE\":\"47\"")
                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"", "\"MKT_SAMPLE_LONGITUDE\":\"123\"");
        String soybean = draftBody("SOYBEAN", "TRADER", "PROTEIN", null)
                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"", "\"MKT_SAMPLE_LATITUDE\":\"47\"")
                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"", "\"MKT_SAMPLE_LONGITUDE\":\"123\"");
        String first = create(corn);
        String second = create(soybean);
        approve(first);
        approve(second);

        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*)=2 AND count(party_id)=0 AND count(sample_point_id)=0
                FROM market.market_record WHERE record_id IN (:first,:second)
                """).param("first", first).param("second", second)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void onlyAnIndependentAuthorizedReviewerCanApproveOrReturnAMarketRecord() throws Exception {
        String id = create("CORN", "FEED_MILL", "MOISTURE");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));

        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_APPROVAL_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/market-records/{id}/return", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_RETURN_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM market.market_record
                WHERE record_id=:id AND status_code='APPROVED'
                  AND party_id IS NULL AND sample_point_id IS NULL
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void voidsAMarketDraftThroughHttpAndPersistsATerminalAuditedState() throws Exception {
        String id = create("CORN", "FEED_MILL", "MOISTURE");

        mockMvc.perform(post("/api/v1/market-records/{id}/void", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT status_code FROM market.market_record WHERE record_id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("VOIDED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:id
                  AND action_code='MARKET_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:id
                  AND action_code='MARKET_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_TRANSITION"));
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
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
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
    void publicInventoryRecordCanEnterReviewWithoutPrivateOwnershipInputs() throws Exception {
        String publicBody = singleFactDraft("CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)
                .replace("\"MKT_SAMPLE_SUBJECT_CODE\":\"fixture-market-subject-1\",", "");
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(publicBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_OWNERSHIP_TYPE").doesNotExist())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"));

        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_INVENTORY_GOVERNANCE_PENDING"));
    }

    @Test
    void copiedDisplayFieldsCannotClaimAnApprovedInventorySamplePoint() throws Exception {
        UUID partyId = UUID.randomUUID();
        UUID samplePointId = UUID.randomUUID();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,'齐齐哈尔第一粮店',0,now(),'market-tester',now(),'market-tester')
                """).param("partyId", partyId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,'齐齐哈尔第一粮店','230200','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123,47),4326),DATE '2026-01-01',
                  'market-tester','market-tester')
                """).param("pointId", samplePointId).param("partyId", partyId).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',DATE '2026-01-01',
                  'market-tester','样本点自有库存权属核定',now())
                """).param("pointId", samplePointId).param("partyId", partyId).update();

        String publicBody = singleFactDraft("CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)
                .replace("\"MKT_SAMPLE_SUBJECT_CODE\":\"fixture-market-subject-1\",", "")
                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"", "\"MKT_SAMPLE_LATITUDE\":\"47\"")
                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"", "\"MKT_SAMPLE_LONGITUDE\":\"123\"");
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(publicBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"))
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_STORAGE_REGION_CODE").doesNotExist())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(jdbc.sql("""
                SELECT party_id IS NULL AND sample_point_id IS NULL
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Boolean.class).single()).isTrue();
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"));
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_INVENTORY_GOVERNANCE_PENDING"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record_core_value
                WHERE record_id=:id AND field_code IN (
                  'MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE','MKT_STORAGE_REGION_CODE',
                  'MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE','MKT_INVENTORY_POLICY_ATTRIBUTE')
                """).param("id", id).query(Long.class).single()).isZero();

        UUID duplicatePartyId = UUID.randomUUID();
        UUID duplicatePointId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,'齐齐哈尔第一粮店',0,now(),'production-tester',now(),'production-tester')
                """).param("partyId", duplicatePartyId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,'齐齐哈尔第一粮店','230200','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123,47),4326),DATE '2026-01-01',
                  'production-tester','production-tester')
                """).param("pointId", duplicatePointId).param("partyId", duplicatePartyId).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',DATE '2026-01-01',
                  'production-tester','重复展示字段攻击夹具',now())
                """).param("pointId", duplicatePointId).param("partyId", duplicatePartyId).update();
        String duplicateAttackId = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(singleFactDraft("CORN", "FEED_MILL", "ENDING_INVENTORY", "21", false)
                                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"", "\"MKT_SAMPLE_LATITUDE\":\"47\"")
                                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"", "\"MKT_SAMPLE_LONGITUDE\":\"123\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(jdbc.sql("""
                SELECT party_id IS NULL AND sample_point_id IS NULL
                FROM market.market_record WHERE record_id=:id
                """).param("id", duplicateAttackId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void controlledSubjectResolutionEnablesInventoryGovernanceReplay() throws Exception {
        UUID partyId = UUID.randomUUID();
        UUID samplePointId = UUID.randomUUID();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,'受控解析企业',0,now(),'production-tester',now(),'production-tester')
                """).param("partyId", partyId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,'受控解析样本点','230200','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.2,47.2),4326),DATE '2026-01-01',
                  'production-tester','production-tester')
                """).param("pointId", samplePointId).param("partyId", partyId).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',DATE '2026-01-01',
                  'production-tester','受控解析库存权属核定',now())
                """).param("pointId", samplePointId).param("partyId", partyId).update();

        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(singleFactDraft(
                                "CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        String batchId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_batch(
                  batch_id,idempotency_key,input_digest,expected_item_count,status_code,created_at,created_by)
                VALUES(CAST(:batchId AS uuid),:key,repeat('a',64),1,'STAGED',now(),'production-tester')
                """).param("batchId", batchId).param("key", "market-inventory-" + id).update();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_item(
                  batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                  resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code)
                VALUES(CAST(:batchId AS uuid),1,'MARKET',:recordId,1,'LINK',:stableSubject,
                  :pointId,'EXT_007_EXPLICIT_DISPOSITION','STAGED')
                """).param("batchId", batchId).param("recordId", id)
                .param("stableSubject", "controlled-market-subject-1")
                .param("pointId", samplePointId).update();
        assertThat(jdbc.sql("""
                SELECT registry.apply_sample_subject_resolution(CAST(:batchId AS uuid),:actor)
                """).param("batchId", batchId).param("actor", "production-tester")
                .query(String.class).single()).isEqualTo("APPLIED");

        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("库存权属已核定"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record record
                JOIN registry.current_sample_subject_resolution resolution
                  ON resolution.source_domain='MARKET' AND resolution.source_record_id=record.record_id
                WHERE record.record_id=:recordId AND record.party_id=:partyId
                  AND record.sample_point_id=:pointId
                  AND resolution.target_sample_point_id=:pointId
                  AND resolution.actor='production-tester'
                """).param("recordId", id).param("partyId", partyId)
                .param("pointId", samplePointId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void approvedInventoryResolutionCanBeCorrectedForwardWithoutRewritingHistory() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        InventoryIdentity mistaken = createApprovedInventoryProfile(
                jdbc, "误绑定库存主体", "误绑定库存样本点", 123.20, 47.20, "误绑定测试档案");
        InventoryIdentity corrected = createApprovedInventoryProfile(
                jdbc, "纠正后库存主体", "纠正后库存样本点", 123.30, 47.30, "纠正测试档案");
        String id = createAndSubmitInventoryRecord();

        String mistakenBatch = stageMarketResolution(
                jdbc, id, mistaken.samplePointId(), 1, "mistaken-approved-subject");
        assertThat(applyResolution(jdbc, mistakenBatch)).isEqualTo("APPLIED");
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(versioned(singleFactDraft(
                                "CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)
                                .replace("\"MKT_SAMPLE_NAME\":\"齐齐哈尔第一粮店\"",
                                        "\"MKT_SAMPLE_NAME\":\"纠正后库存样本点\"")
                                .replace("\"MKT_SAMPLE_LATITUDE\":\"47.3543\"",
                                        "\"MKT_SAMPLE_LATITUDE\":\"47.30\"")
                                .replace("\"MKT_SAMPLE_LONGITUDE\":\"123.9182\"",
                                        "\"MKT_SAMPLE_LONGITUDE\":\"123.30\""), 2)))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("""
                SELECT party_id=:partyId AND sample_point_id=:pointId AND version=2
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).param("partyId", mistaken.partyId())
                .param("pointId", mistaken.samplePointId()).query(Boolean.class).single()).isTrue();

        jdbc.sql("""
                SELECT overview.activate_region_surplus_calculation_contract(
                  'REGION_SURPLUS_V2',clock_timestamp()-interval '1 second',
                  'production-tester','审核后库存身份纠正测试')
                """).query((row, ignored) -> true).single();
        String beforeCorrectionDashboard = mockMvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("INSUFFICIENT_COVERAGE")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources.length()")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath(
                        "$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources[*].subjectKey")
                        .value(org.hamcrest.Matchers.hasItem(mistaken.partyId().toString())))
                .andReturn().getResponse().getContentAsString();
        assertThat(beforeCorrectionDashboard).contains(mistaken.partyId().toString())
                .doesNotContain(corrected.partyId().toString());

        UUID mistakenRevisionId = jdbc.sql("""
                SELECT resolution_revision_id FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:id
                """).param("id", id).query(UUID.class).single();
        Instant updatedBeforeCorrection = jdbc.sql("""
                SELECT updated_at FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Instant.class).single();
        long eventSequenceBeforeCorrection = jdbc.sql("""
                SELECT coalesce(max(event_sequence),0) FROM platform.business_event_outbox
                """).query(Long.class).single();
        String correctionBatch = stageApprovedMarketCorrection(
                jdbc, id, corrected.samplePointId(), 2,
                "corrected-approved-subject", mistakenRevisionId);
        assertThat(correctApprovedMarketResolution(jdbc, correctionBatch)).isEqualTo("APPLIED");

        assertThat(jdbc.sql("""
                SELECT updated_at FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Instant.class).single()).isAfter(updatedBeforeCorrection);
        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM platform.business_audit_event audit
                JOIN platform.business_event_outbox event USING(event_id)
                WHERE audit.aggregate_type='MARKET_RECORD' AND audit.aggregate_id=:id
                  AND audit.action_code='MARKET_INVENTORY_IDENTITY_CORRECTED'
                  AND event.aggregate_type=audit.aggregate_type
                  AND event.aggregate_id=audit.aggregate_id
                  AND event.action_code=audit.action_code
                  AND event.actor_subject_id='production-tester'
                  AND event.work_unit_code='TEST'
                  AND event.region_codes=ARRAY['230200']::varchar(18)[]
                  AND event.product_code='CORN'
                  AND event.detail=audit.detail
                  AND event.detail->>'sourceVersion'='2'
                  AND event.detail->>'correctedVersion'='3'
                  AND event.detail->>'resolutionRevisionId'=:revisionId
                  AND event.detail->>'predecessorResolutionRevisionId'=:predecessorId
                  AND event.detail::text NOT LIKE '%MKT_INVENTORY_%'
                  AND event.detail::text NOT LIKE :partyId
                  AND event.detail::text NOT LIKE :samplePointId
                  AND event.detail::text NOT LIKE '%stableSubject%'
                  AND event.event_sequence>:afterSequence
                """).param("id", id).param("revisionId", jdbc.sql("""
                        SELECT resolution_revision_id FROM registry.current_sample_subject_resolution
                        WHERE source_domain='MARKET' AND source_record_id=:id
                        """).param("id", id).query(String.class).single())
                .param("predecessorId", mistakenRevisionId.toString())
                .param("partyId", "%" + corrected.partyId() + "%")
                .param("samplePointId", "%" + corrected.samplePointId() + "%")
                .param("afterSequence", eventSequenceBeforeCorrection)
                .query(Long.class).single()).isEqualTo(1L);

        AuthorizedReadScope realtimeScope = new AuthorizedReadScope(
                "production-tester", Set.of("*"));
        List<BusinessNotification> notificationEvents = notifications.findVisibleAfter(
                realtimeScope, "production-tester", eventSequenceBeforeCorrection, 20);
        assertThat(notificationEvents).anySatisfy(event -> {
            assertThat(event.aggregateId()).isEqualTo(id);
            assertThat(event.actionCode()).isEqualTo("MARKET_INVENTORY_IDENTITY_CORRECTED");
        });
        List<BusinessNotification> streamedEvents = new ArrayList<>();
        var deliveryResult = eventDeliveries.drain(
                "test-market-correction-" + UUID.randomUUID(), "test-instance",
                realtimeScope, "production-tester", eventSequenceBeforeCorrection, 20,
                streamedEvents::add);
        assertThat(deliveryResult.failedCount()).isZero();
        assertThat(streamedEvents).anySatisfy(event -> {
            assertThat(event.aggregateId()).isEqualTo(id);
            assertThat(event.actionCode()).isEqualTo("MARKET_INVENTORY_IDENTITY_CORRECTED");
        });

        assertThat(jdbc.sql("""
                SELECT record.version || ':' || record.party_id || ':' || record.sample_point_id || ':' ||
                  governance.status_code || ':' || governance.sample_point_id || ':' ||
                  count(revision.resolution_revision_id)
                FROM market.market_record record
                JOIN market.market_inventory_governance governance ON governance.record_id=record.record_id
                JOIN registry.sample_subject_resolution_revision revision
                  ON revision.source_domain='MARKET' AND revision.source_record_id=record.record_id
                WHERE record.record_id=:id
                GROUP BY record.version,record.party_id,record.sample_point_id,
                  governance.status_code,governance.sample_point_id
                """).param("id", id).query(String.class).single()).isEqualTo(
                        "3:" + corrected.partyId() + ":" + corrected.samplePointId()
                                + ":READY:" + corrected.samplePointId() + ":2");
        assertThat(jdbc.sql("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM market.market_record_core_value
                WHERE record_id=:id AND field_code IN (
                  'MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
                  'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
                  'MKT_INVENTORY_POLICY_ATTRIBUTE')
                """).param("id", id).query(String.class).single()).isEqualTo(
                        "MKT_CARGO_OWNER_CODE:" + corrected.partyId()
                                + ",MKT_INVENTORY_CUTOFF_DATE:2026-08-31"
                                + ",MKT_INVENTORY_HOLDER_CODE:" + corrected.partyId()
                                + ",MKT_INVENTORY_OWNERSHIP_TYPE:OWNED"
                                + ",MKT_INVENTORY_POLICY_ATTRIBUTE:COMMERCIAL"
                                + ",MKT_STORAGE_REGION_CODE:230200");
        UUID correctedRevisionId = jdbc.sql("""
                SELECT resolution_revision_id FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:id
                """).param("id", id).query(UUID.class).single();
        assertThat(jdbc.sql("""
                SELECT resolution_sequence || ':' || source_version || ':' || actor || ':' ||
                  (predecessor_revision_id=:predecessorId) || ':' ||
                  (before_sha256 ~ '^[0-9a-f]{64}$') || ':' || (after_sha256 ~ '^[0-9a-f]{64}$')
                FROM registry.sample_subject_resolution_revision
                WHERE resolution_revision_id=:revisionId
                """).param("revisionId", correctedRevisionId)
                .param("predecessorId", mistakenRevisionId).query(String.class).single())
                .isEqualTo("2:3:production-tester:true:true:true");
        assertThat(jdbc.sql("""
                SELECT ((before_snapshot::jsonb)->>'partyId') || ':' ||
                  ((after_snapshot::jsonb)->>'partyId') || ':' ||
                  applied_source_version || ':' || applied_by
                FROM registry.sample_subject_resolution_item
                WHERE batch_id=CAST(:batchId AS uuid) AND item_sequence=1
                """).param("batchId", correctionBatch).query(String.class).single()).isEqualTo(
                        mistaken.partyId() + ":" + corrected.partyId() + ":2:production-tester");

        String staleVersionBatch = stageApprovedMarketCorrection(
                jdbc, id, corrected.samplePointId(), 2,
                "corrected-approved-subject", correctedRevisionId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> correctApprovedMarketResolution(jdbc, staleVersionBatch))
                .hasMessageContaining("source version mismatch");
        String nonCurrentPredecessorBatch = stageApprovedMarketCorrection(
                jdbc, id, corrected.samplePointId(), 3,
                "corrected-approved-subject", mistakenRevisionId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> correctApprovedMarketResolution(jdbc, nonCurrentPredecessorBatch))
                .hasMessageContaining("predecessor is not the current appended revision");

        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230208',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.40,47.40),4326),0.05)),
                  'market correction cross-region fixture','urn:test:market-sample-point','test-v1',
                  'Test fixture','230208',DATE '2026-08-13',repeat('6',64))
                ON CONFLICT (region_code) DO NOTHING
                """).update();
        InventoryIdentity crossRegion = createApprovedInventoryProfile(
                jdbc, "跨地区纠正目标主体", "跨地区纠正目标样本点", 123.40, 47.40,
                "跨地区纠正负例", "230208", "2026-01-01");
        String crossRegionBatch = stageApprovedMarketCorrection(
                jdbc, id, crossRegion.samplePointId(), 3,
                "cross-region-correction-subject", correctedRevisionId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> correctApprovedMarketResolution(jdbc, crossRegionBatch))
                .hasMessageContaining("outside its governed region, type, or effective date");

        InventoryIdentity futureEffective = createApprovedInventoryProfile(
                jdbc, "未生效纠正目标主体", "未生效纠正目标样本点", 123.25, 47.25,
                "未生效纠正负例", "230200", "2026-09-01");
        String futureEffectiveBatch = stageApprovedMarketCorrection(
                jdbc, id, futureEffective.samplePointId(), 3,
                "future-effective-correction-subject", correctedRevisionId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> correctApprovedMarketResolution(jdbc, futureEffectiveBatch))
                .hasMessageContaining("outside its governed region, type, or effective date");
        assertThat(jdbc.sql("""
                SELECT version=3 AND party_id=:partyId AND sample_point_id=:pointId
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).param("partyId", corrected.partyId())
                .param("pointId", corrected.samplePointId()).query(Boolean.class).single()).isTrue();

        String afterCorrectionDashboard = mockMvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("INSUFFICIENT_COVERAGE")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources.length()")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath(
                        "$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources[*].subjectKey")
                        .value(org.hamcrest.Matchers.hasItem(corrected.partyId().toString())))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterCorrectionDashboard).contains(corrected.partyId().toString())
                .doesNotContain(mistaken.partyId().toString());

        mockMvc.perform(get("/api/v1/market-records/{id}", id)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("库存权属已核定"))
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_SUBJECT_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        SELECT registry.rollback_sample_subject_resolution(
                          CAST(:batchId AS uuid),:actor)
                        """).param("batchId", correctionBatch).param("actor", "production-tester")
                .query(String.class).single()).hasMessageContaining("source version mismatch");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        DELETE FROM registry.sample_subject_resolution_revision
                        WHERE resolution_revision_id=:revisionId
                        """).param("revisionId", correctedRevisionId).update())
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        DELETE FROM registry.sample_subject_resolution_audit
                        WHERE batch_id=CAST(:batchId AS uuid)
                        """).param("batchId", correctionBatch).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void approvedInventoryCorrectionRollsBackEveryProjectionAndEventWhenOutboxFails() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        InventoryIdentity mistaken = createApprovedInventoryProfile(
                jdbc, "回滚前库存主体", "回滚前库存样本点", 123.11, 47.11, "回滚前档案");
        InventoryIdentity corrected = createApprovedInventoryProfile(
                jdbc, "回滚目标库存主体", "回滚目标库存样本点", 123.12, 47.12, "回滚目标档案");
        String id = createAndSubmitInventoryRecord();
        String initialBatch = stageMarketResolution(
                jdbc, id, mistaken.samplePointId(), 1, "rollback-original-subject");
        assertThat(applyResolution(jdbc, initialBatch)).isEqualTo("APPLIED");
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        UUID predecessorRevisionId = jdbc.sql("""
                SELECT resolution_revision_id FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:id
                """).param("id", id).query(UUID.class).single();
        Instant updatedBeforeCorrection = jdbc.sql("""
                SELECT updated_at FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Instant.class).single();
        String correctionBatch = stageApprovedMarketCorrection(
                jdbc, id, corrected.samplePointId(), 2,
                "rollback-corrected-subject", predecessorRevisionId);

        jdbc.sql("""
                CREATE FUNCTION platform.reject_market_correction_event_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $trigger$
                BEGIN
                  IF NEW.action_code='MARKET_INVENTORY_IDENTITY_CORRECTED' THEN
                    RAISE EXCEPTION 'forced correction outbox failure';
                  END IF;
                  RETURN NEW;
                END
                $trigger$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_market_correction_event_for_test
                BEFORE INSERT ON platform.business_event_outbox
                FOR EACH ROW EXECUTE FUNCTION platform.reject_market_correction_event_for_test()
                """).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> correctApprovedMarketResolution(jdbc, correctionBatch))
                .hasMessageContaining("forced correction outbox failure");
        assertThat(jdbc.sql("""
                SELECT version=2 AND party_id=:partyId AND sample_point_id=:pointId
                  AND updated_at=:updatedAt
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).param("partyId", mistaken.partyId())
                .param("pointId", mistaken.samplePointId())
                .param("updatedAt", Timestamp.from(updatedBeforeCorrection))
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT resolution_revision_id=:predecessorId
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:id
                """).param("id", id).param("predecessorId", predecessorRevisionId)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT (SELECT status_code FROM registry.sample_subject_resolution_batch
                         WHERE batch_id=CAST(:batchId AS uuid)) || ':' ||
                       (SELECT status_code FROM registry.sample_subject_resolution_item
                         WHERE batch_id=CAST(:batchId AS uuid) AND item_sequence=1) || ':' ||
                       (SELECT count(*) FROM registry.sample_subject_resolution_revision
                         WHERE batch_id=CAST(:batchId AS uuid)) || ':' ||
                       (SELECT count(*) FROM registry.sample_subject_resolution_audit
                         WHERE batch_id=CAST(:batchId AS uuid)) || ':' ||
                       (SELECT count(*) FROM platform.business_audit_event
                         WHERE aggregate_id=:id AND action_code='MARKET_INVENTORY_IDENTITY_CORRECTED') || ':' ||
                       (SELECT count(*) FROM platform.business_event_outbox
                         WHERE aggregate_id=:id AND action_code='MARKET_INVENTORY_IDENTITY_CORRECTED')
                """).param("batchId", correctionBatch).param("id", id)
                .query(String.class).single()).isEqualTo("STAGED:STAGED:0:0:0:0");
    }

    @Test
    void concurrentCorrectionsCannotBindOneStableSubjectToDifferentSamplePoints() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ApprovedInventoryRecord first = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "并发主体甲", "并发原样本点甲", 123.01, 47.01, "并发原档案甲"),
                "concurrent-original-subject-a");
        ApprovedInventoryRecord second = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "并发主体乙", "并发原样本点乙", 123.02, 47.02, "并发原档案乙"),
                "concurrent-original-subject-b");
        InventoryIdentity firstTarget = createApprovedInventoryProfile(
                jdbc, "并发目标主体甲", "并发目标样本点甲", 123.03, 47.03, "并发目标档案甲");
        InventoryIdentity secondTarget = createApprovedInventoryProfile(
                jdbc, "并发目标主体乙", "并发目标样本点乙", 123.04, 47.04, "并发目标档案乙");
        String sharedStableSubject = "concurrent-shared-stable-subject";
        String firstBatch = stageApprovedMarketCorrection(
                jdbc, first.recordId(), firstTarget.samplePointId(), 2,
                sharedStableSubject, first.revisionId());
        String secondBatch = stageApprovedMarketCorrection(
                jdbc, second.recordId(), secondTarget.samplePointId(), 2,
                sharedStableSubject, second.revisionId());
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runCorrectionsConcurrently(firstBatch, secondBatch);

        assertExactlyOneCorrectionSucceeded(outcomes, "stable subject id already points to another sample point");
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT target_sample_point_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND stable_subject_id=:stableSubject
                  AND resolution_action='LINK'
                """).param("stableSubject", sharedStableSubject)
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void concurrentCorrectionsCannotBindDifferentStableSubjectsToOneSamplePoint() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ApprovedInventoryRecord first = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "并发点主体甲", "并发点原样本点甲", 123.05, 47.05, "并发点原档案甲"),
                "concurrent-point-original-subject-a");
        ApprovedInventoryRecord second = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "并发点主体乙", "并发点原样本点乙", 123.06, 47.06, "并发点原档案乙"),
                "concurrent-point-original-subject-b");
        InventoryIdentity sharedTarget = createApprovedInventoryProfile(
                jdbc, "并发共享目标主体", "并发共享目标样本点", 123.07, 47.07, "并发共享目标档案");
        String firstStableSubject = "concurrent-target-stable-subject-a";
        String secondStableSubject = "concurrent-target-stable-subject-b";
        String firstBatch = stageApprovedMarketCorrection(
                jdbc, first.recordId(), sharedTarget.samplePointId(), 2,
                firstStableSubject, first.revisionId());
        String secondBatch = stageApprovedMarketCorrection(
                jdbc, second.recordId(), sharedTarget.samplePointId(), 2,
                secondStableSubject, second.revisionId());
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runCorrectionsConcurrently(firstBatch, secondBatch);

        assertExactlyOneCorrectionSucceeded(
                outcomes, "target sample point already belongs to another stable subject id");
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT stable_subject_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND target_sample_point_id=:samplePointId
                  AND resolution_action='LINK'
                """).param("samplePointId", sharedTarget.samplePointId())
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void concurrentPendingLinksCannotBindOneStableSubjectToDifferentSamplePoints() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String firstRecord = createAndSubmitInventoryRecord();
        String secondRecord = createAndSubmitInventoryRecord();
        InventoryIdentity firstTarget = createApprovedInventoryProfile(
                jdbc, "初次并发目标主体甲", "初次并发目标样本点甲", 123.08, 47.08, "初次并发目标档案甲");
        InventoryIdentity secondTarget = createApprovedInventoryProfile(
                jdbc, "初次并发目标主体乙", "初次并发目标样本点乙", 123.09, 47.09, "初次并发目标档案乙");
        String sharedStableSubject = "pending-link-shared-stable-subject";
        String firstBatch = stageMarketResolution(
                jdbc, firstRecord, firstTarget.samplePointId(), 1, sharedStableSubject);
        String secondBatch = stageMarketResolution(
                jdbc, secondRecord, secondTarget.samplePointId(), 1, sharedStableSubject);
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runResolutionsConcurrently(
                firstBatch, ResolutionEntryPoint.APPLY, secondBatch, ResolutionEntryPoint.APPLY);

        assertExactlyOneResolutionSucceeded(
                outcomes, "stable subject id already points to another sample point");
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT target_sample_point_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND stable_subject_id=:stableSubject
                  AND resolution_action='LINK'
                """).param("stableSubject", sharedStableSubject)
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void concurrentPendingLinksCannotBindDifferentStableSubjectsToOneSamplePoint() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String firstRecord = createAndSubmitInventoryRecord();
        String secondRecord = createAndSubmitInventoryRecord();
        InventoryIdentity sharedTarget = createApprovedInventoryProfile(
                jdbc, "初次共享目标主体", "初次共享目标样本点", 123.10, 47.10, "初次共享目标档案");
        String firstStableSubject = "pending-link-target-stable-subject-a";
        String secondStableSubject = "pending-link-target-stable-subject-b";
        String firstBatch = stageMarketResolution(
                jdbc, firstRecord, sharedTarget.samplePointId(), 1, firstStableSubject);
        String secondBatch = stageMarketResolution(
                jdbc, secondRecord, sharedTarget.samplePointId(), 1, secondStableSubject);
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runResolutionsConcurrently(
                firstBatch, ResolutionEntryPoint.APPLY, secondBatch, ResolutionEntryPoint.APPLY);

        assertExactlyOneResolutionSucceeded(
                outcomes, "target sample point already belongs to another stable subject id");
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT stable_subject_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND target_sample_point_id=:samplePointId
                  AND resolution_action='LINK'
                """).param("samplePointId", sharedTarget.samplePointId())
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void pendingLinkAndApprovedCorrectionShareTheSameStableSubjectGate() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ApprovedInventoryRecord approved = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "混合并发原主体", "混合并发原样本点", 123.11, 47.11, "混合并发原档案"),
                "mixed-race-original-subject");
        String pendingRecord = createAndSubmitInventoryRecord();
        InventoryIdentity correctionTarget = createApprovedInventoryProfile(
                jdbc, "混合并发纠正主体", "混合并发纠正样本点", 123.12, 47.12, "混合并发纠正档案");
        InventoryIdentity pendingTarget = createApprovedInventoryProfile(
                jdbc, "混合并发初次主体", "混合并发初次样本点", 123.13, 47.13, "混合并发初次档案");
        String sharedStableSubject = "mixed-apply-correction-shared-stable-subject";
        String correctionBatch = stageApprovedMarketCorrection(
                jdbc, approved.recordId(), correctionTarget.samplePointId(), 2,
                sharedStableSubject, approved.revisionId());
        String pendingBatch = stageMarketResolution(
                jdbc, pendingRecord, pendingTarget.samplePointId(), 1, sharedStableSubject);
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runResolutionsConcurrently(
                pendingBatch, ResolutionEntryPoint.APPLY,
                correctionBatch, ResolutionEntryPoint.CORRECT);

        assertExactlyOneResolutionSucceeded(
                outcomes, "stable subject id already points to another sample point");
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT target_sample_point_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND stable_subject_id=:stableSubject
                  AND resolution_action='LINK'
                """).param("stableSubject", sharedStableSubject)
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void legacyRegistrationAndPendingLinkShareTheSameStableSubjectGate() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String sharedStableSubject = "legacy-initial-shared-stable-subject";
        String legacySource = createApprovedUnlinkedMarketSubject(jdbc, sharedStableSubject);
        String pendingSource = createAndSubmitInventoryRecord();
        InventoryIdentity legacyTarget = createApprovedInventoryProfile(
                jdbc, "主数据初次目标主体", "主数据初次目标样本点", 123.20, 47.20, "主数据初次目标档案");
        InventoryIdentity resolutionTarget = createApprovedInventoryProfile(
                jdbc, "回放初次目标主体", "回放初次目标样本点", 123.21, 47.21, "回放初次目标档案");
        String pendingBatch = stageMarketResolution(
                jdbc, pendingSource, resolutionTarget.samplePointId(), 1, sharedStableSubject);
        installCrossProjectionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runLegacyRegistrationAndResolutionConcurrently(
                legacySource, legacyTarget.samplePointId(),
                pendingBatch, ResolutionEntryPoint.APPLY);

        assertExactlyOneCrossProjectionWriterSucceeded(
                outcomes, "stable subject id already points to another sample point");
        assertThat(jdbc.sql("""
                WITH combined AS (
                  SELECT subject_id stable_subject_id,sample_point_id
                  FROM registry.sample_point_subject_identity WHERE business_domain='MARKET'
                  UNION ALL
                  SELECT stable_subject_id,target_sample_point_id
                  FROM registry.current_sample_subject_resolution
                  WHERE source_domain='MARKET' AND resolution_action='LINK'
                )
                SELECT count(*) || ':' || count(DISTINCT sample_point_id)
                FROM combined WHERE stable_subject_id=:stableSubject
                """).param("stableSubject", sharedStableSubject)
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void legacyRegistrationAndApprovedCorrectionShareTheSameTargetPointGate() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String legacyStableSubject = "legacy-correction-stable-subject";
        String correctionStableSubject = "resolution-correction-stable-subject";
        String legacySource = createApprovedUnlinkedMarketSubject(jdbc, legacyStableSubject);
        ApprovedInventoryRecord approved = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "主数据纠正原主体", "主数据纠正原样本点", 123.22, 47.22, "主数据纠正原档案"),
                "legacy-correction-original-subject");
        InventoryIdentity sharedTarget = createApprovedInventoryProfile(
                jdbc, "主数据纠正共享主体", "主数据纠正共享样本点", 123.23, 47.23, "主数据纠正共享档案");
        String correctionBatch = stageApprovedMarketCorrection(
                jdbc, approved.recordId(), sharedTarget.samplePointId(), 2,
                correctionStableSubject, approved.revisionId());
        installCrossProjectionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runLegacyRegistrationAndResolutionConcurrently(
                legacySource, sharedTarget.samplePointId(),
                correctionBatch, ResolutionEntryPoint.CORRECT);

        assertExactlyOneCrossProjectionWriterSucceeded(
                outcomes, "target sample point already belongs to another stable subject id");
        assertThat(jdbc.sql("""
                WITH combined AS (
                  SELECT subject_id stable_subject_id,sample_point_id
                  FROM registry.sample_point_subject_identity WHERE business_domain='MARKET'
                  UNION ALL
                  SELECT stable_subject_id,target_sample_point_id
                  FROM registry.current_sample_subject_resolution
                  WHERE source_domain='MARKET' AND resolution_action='LINK'
                )
                SELECT count(*) || ':' || count(DISTINCT stable_subject_id)
                FROM combined WHERE sample_point_id=:samplePointId
                """).param("samplePointId", sharedTarget.samplePointId())
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void governedSubjectApplyAndPendingLinkShareTheSameStableSubjectGate() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String sharedStableSubject = "governed-initial-shared-stable-subject";
        InventoryIdentity governedTarget = createApprovedInventoryProfile(
                jdbc, "正式治理初次目标主体", "正式治理初次目标样本点", 123.24, 47.24, "正式治理初次目标档案");
        InventoryIdentity resolutionTarget = createApprovedInventoryProfile(
                jdbc, "正式回放初次目标主体", "正式回放初次目标样本点", 123.25, 47.25, "正式回放初次目标档案");
        long governedRequest = prepareApprovedSubjectInsert(
                sharedStableSubject, governedTarget.samplePointId());
        String pendingSource = createAndSubmitInventoryRecord();
        String pendingBatch = stageMarketResolution(
                jdbc, pendingSource, resolutionTarget.samplePointId(), 1, sharedStableSubject);
        installCrossProjectionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runGovernedSubjectApplyAndResolutionConcurrently(
                governedRequest, pendingBatch, ResolutionEntryPoint.APPLY);

        assertExactlyOneResolutionSucceeded(
                outcomes, "stable subject id already points to another sample point");
        assertThat(jdbc.sql("""
                WITH combined AS (
                  SELECT subject_id stable_subject_id,sample_point_id
                  FROM registry.sample_point_subject_identity WHERE business_domain='MARKET'
                  UNION ALL
                  SELECT stable_subject_id,target_sample_point_id
                  FROM registry.current_sample_subject_resolution
                  WHERE source_domain='MARKET' AND resolution_action='LINK'
                )
                SELECT count(*) || ':' || count(DISTINCT sample_point_id)
                FROM combined WHERE stable_subject_id=:stableSubject
                """).param("stableSubject", sharedStableSubject)
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void governedSubjectApplyAndApprovedCorrectionShareTheSameTargetPointGate() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ApprovedInventoryRecord approved = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "正式治理纠正原主体", "正式治理纠正原样本点", 123.26, 47.26, "正式治理纠正原档案"),
                "governed-correction-original-subject");
        InventoryIdentity sharedTarget = createApprovedInventoryProfile(
                jdbc, "正式治理纠正共享主体", "正式治理纠正共享样本点", 123.27, 47.27, "正式治理纠正共享档案");
        String governedStableSubject = "governed-correction-legacy-subject";
        String correctionStableSubject = "governed-correction-resolution-subject";
        long governedRequest = prepareApprovedSubjectInsert(
                governedStableSubject, sharedTarget.samplePointId());
        String correctionBatch = stageApprovedMarketCorrection(
                jdbc, approved.recordId(), sharedTarget.samplePointId(), 2,
                correctionStableSubject, approved.revisionId());
        installCrossProjectionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runGovernedSubjectApplyAndResolutionConcurrently(
                governedRequest, correctionBatch, ResolutionEntryPoint.CORRECT);

        assertExactlyOneResolutionSucceeded(
                outcomes, "target sample point already belongs to another stable subject id");
        assertThat(jdbc.sql("""
                WITH combined AS (
                  SELECT subject_id stable_subject_id,sample_point_id
                  FROM registry.sample_point_subject_identity WHERE business_domain='MARKET'
                  UNION ALL
                  SELECT stable_subject_id,target_sample_point_id
                  FROM registry.current_sample_subject_resolution
                  WHERE source_domain='MARKET' AND resolution_action='LINK'
                )
                SELECT count(*) || ':' || count(DISTINCT stable_subject_id)
                FROM combined WHERE sample_point_id=:samplePointId
                """).param("samplePointId", sharedTarget.samplePointId())
                .query(String.class).single()).isEqualTo("1:1");
    }

    @Test
    void governedSubjectUpdateChecksCurrentProjectionAndDeleteReleasesIdentityForRegister()
            throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String governedStableSubject = "governed-update-delete-stable-subject";
        InventoryIdentity originalTarget = createApprovedInventoryProfile(
                jdbc, "正式治理更新原主体", "正式治理更新原样本点", 123.28, 47.28, "正式治理更新原档案");
        InventoryIdentity conflictingTarget = createApprovedInventoryProfile(
                jdbc, "正式治理更新冲突主体", "正式治理更新冲突样本点", 123.29, 47.29, "正式治理更新冲突档案");
        InventoryIdentity replacementTarget = createApprovedInventoryProfile(
                jdbc, "正式治理更新替代主体", "正式治理更新替代样本点", 123.30, 47.30, "正式治理更新替代档案");
        applyApprovedSubjectChange(prepareApprovedSubjectChange(
                "MARKET", governedStableSubject, originalTarget.samplePointId(), "INSERT", null));

        String conflictingSource = createAndSubmitInventoryRecord();
        String conflictingBatch = stageMarketResolution(
                jdbc, conflictingSource, conflictingTarget.samplePointId(), 1,
                "governed-update-conflicting-current-subject");
        assertThat(applyResolution(jdbc, conflictingBatch)).isEqualTo("APPLIED");
        String originalSnapshot = legacySubjectSnapshot(jdbc, "MARKET", governedStableSubject);
        String conflictingUpdate = replaceSubjectPoint(
                jdbc, originalSnapshot, conflictingTarget.samplePointId());
        long conflictingRequest = prepareApprovedSubjectChange(
                "MARKET", governedStableSubject, conflictingTarget.samplePointId(),
                "UPDATE", conflictingUpdate);

        assertThatThrownBy(() -> applyApprovedSubjectChange(conflictingRequest))
                .hasMessageContaining("target sample point already belongs to another stable subject id");
        assertThat(legacySubjectSnapshot(jdbc, "MARKET", governedStableSubject))
                .contains(originalTarget.samplePointId().toString());

        String replacementUpdate = replaceSubjectPoint(
                jdbc, originalSnapshot, replacementTarget.samplePointId());
        applyApprovedSubjectChange(prepareApprovedSubjectChange(
                "MARKET", governedStableSubject, replacementTarget.samplePointId(),
                "UPDATE", replacementUpdate));
        String replacementSnapshot = legacySubjectSnapshot(jdbc, "MARKET", governedStableSubject);
        assertThat(replacementSnapshot).contains(replacementTarget.samplePointId().toString());
        applyApprovedSubjectChange(prepareApprovedSubjectChange(
                "MARKET", governedStableSubject, replacementTarget.samplePointId(),
                "DELETE", replacementSnapshot));

        String registerSource = createApprovedUnlinkedMarketSubject(jdbc, governedStableSubject);
        assertThat(jdbc.sql("""
                SELECT platform.register_approved_sample_subject('MARKET',:recordId,:samplePointId)
                """).param("recordId", registerSource)
                .param("samplePointId", originalTarget.samplePointId())
                .query(Long.class).single()).isPositive();
        assertThat(legacySubjectSnapshot(jdbc, "MARKET", governedStableSubject))
                .contains(originalTarget.samplePointId().toString());
    }

    @Test
    void governedSubjectIdentityGateKeepsProductionAndMarketDomainsIsolated() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String sharedStableSubject = "governed-cross-domain-stable-subject";
        InventoryIdentity sharedTarget = createApprovedInventoryProfile(
                jdbc, "正式治理跨域主体", "正式治理跨域样本点", 123.31, 47.31, "正式治理跨域档案");
        long marketRequest = prepareApprovedSubjectChange(
                "MARKET", sharedStableSubject, sharedTarget.samplePointId(), "INSERT", null);
        long productionRequest = prepareApprovedSubjectChange(
                "PRODUCTION", sharedStableSubject, sharedTarget.samplePointId(), "INSERT", null);

        List<CorrectionOutcome> outcomes = runGovernedSubjectAppliesConcurrently(
                marketRequest, productionRequest);

        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.message()).isEqualTo("APPLIED");
        });
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point_subject_identity
                WHERE subject_id=:stableSubject AND sample_point_id=:samplePointId
                  AND business_domain IN ('MARKET','PRODUCTION')
                """).param("stableSubject", sharedStableSubject)
                .param("samplePointId", sharedTarget.samplePointId())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void reversedMultiItemCorrectionBatchesAcquireTheWholeIdentitySetWithoutDeadlock() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ApprovedInventoryRecord firstA = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "反序甲一原主体", "反序甲一原样本点", 123.14, 47.14, "反序甲一原档案"),
                "reverse-a1-original-subject");
        ApprovedInventoryRecord secondA = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "反序甲二原主体", "反序甲二原样本点", 123.15, 47.15, "反序甲二原档案"),
                "reverse-a2-original-subject");
        ApprovedInventoryRecord firstB = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "反序乙一原主体", "反序乙一原样本点", 123.16, 47.16, "反序乙一原档案"),
                "reverse-b1-original-subject");
        ApprovedInventoryRecord secondB = createApprovedInventoryRecord(
                jdbc, createApprovedInventoryProfile(
                        jdbc, "反序乙二原主体", "反序乙二原样本点", 123.17, 47.17, "反序乙二原档案"),
                "reverse-b2-original-subject");
        InventoryIdentity firstTarget = createApprovedInventoryProfile(
                jdbc, "反序共享目标主体甲", "反序共享目标样本点甲", 123.18, 47.18, "反序共享目标档案甲");
        InventoryIdentity secondTarget = createApprovedInventoryProfile(
                jdbc, "反序共享目标主体乙", "反序共享目标样本点乙", 123.19, 47.19, "反序共享目标档案乙");
        String firstStableSubject = "reverse-shared-stable-subject-a";
        String secondStableSubject = "reverse-shared-stable-subject-b";
        String firstBatch = stageApprovedMarketCorrectionBatch(jdbc, List.of(
                new CorrectionItem(firstA.recordId(), firstTarget.samplePointId(),
                        2, firstStableSubject, firstA.revisionId()),
                new CorrectionItem(secondA.recordId(), secondTarget.samplePointId(),
                        2, secondStableSubject, secondA.revisionId())));
        String secondBatch = stageApprovedMarketCorrectionBatch(jdbc, List.of(
                new CorrectionItem(firstB.recordId(), secondTarget.samplePointId(),
                        2, secondStableSubject, firstB.revisionId()),
                new CorrectionItem(secondB.recordId(), firstTarget.samplePointId(),
                        2, firstStableSubject, secondB.revisionId())));
        installResolutionRaceDelay(jdbc);

        List<CorrectionOutcome> outcomes = runCorrectionsConcurrently(firstBatch, secondBatch);

        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.message()).isEqualTo("APPLIED");
            assertThat(outcome.message()).doesNotContainIgnoringCase("deadlock");
        });
        assertThat(jdbc.sql("""
                SELECT count(*) || ':' || count(DISTINCT stable_subject_id) || ':' ||
                       count(DISTINCT target_sample_point_id)
                FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND stable_subject_id IN (:firstStable,:secondStable)
                  AND resolution_action='LINK'
                """).param("firstStable", firstStableSubject)
                .param("secondStable", secondStableSubject)
                .query(String.class).single()).isEqualTo("4:2:2");
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT batch.batch_id) FILTER (WHERE batch.status_code='APPLIED') || ':' ||
                       count(*) FILTER (WHERE item.status_code='APPLIED') || ':' ||
                       count(revision.resolution_revision_id)
                FROM registry.sample_subject_resolution_batch batch
                JOIN registry.sample_subject_resolution_item item ON item.batch_id=batch.batch_id
                LEFT JOIN registry.sample_subject_resolution_revision revision
                  ON revision.batch_id=item.batch_id AND revision.item_sequence=item.item_sequence
                WHERE batch.batch_id IN (CAST(:firstBatch AS uuid),CAST(:secondBatch AS uuid))
                """).param("firstBatch", firstBatch).param("secondBatch", secondBatch)
                .query(String.class).single()).isEqualTo("2:4:4");
    }

    @Test
    void staleCrossRegionAndExpiredControlledResolutionsRemainPending() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID crossPartyId = UUID.randomUUID();
        UUID crossPointId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,'跨地区权威主体',0,now(),'production-tester',now(),'production-tester')
                """).param("partyId", crossPartyId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,'跨地区权威样本点','230208','APPROVED','MISSING',
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("pointId", crossPointId).param("partyId", crossPartyId).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',DATE '2026-01-01',
                  'production-tester','跨地区负例',now())
                """).param("pointId", crossPointId).param("partyId", crossPartyId).update();

        String crossRecordId = createAndSubmitInventoryRecord();
        String staleBatch = stageMarketResolution(
                jdbc, crossRecordId, crossPointId, 0, "stale-cross-region-subject");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applyResolution(jdbc, staleBatch))
                .hasMessageContaining("version mismatch");
        String crossBatch = stageMarketResolution(
                jdbc, crossRecordId, crossPointId, 1, "cross-region-subject");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applyResolution(jdbc, crossBatch))
                .hasMessageContaining("outside its governed region or effective date");
        assertInventoryApprovalRemainsPending(crossRecordId);

        UUID expiredPartyId = UUID.randomUUID();
        UUID expiredPointId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,'过期边界权威主体',0,now(),'production-tester',now(),'production-tester')
                """).param("partyId", expiredPartyId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,'晚于数据时间的样本点','230200','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.1,47.1),4326),DATE '2026-09-01',
                  'production-tester','production-tester')
                """).param("pointId", expiredPointId).param("partyId", expiredPartyId).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',DATE '2026-09-01',
                  'production-tester','生效时间负例',now())
                """).param("pointId", expiredPointId).param("partyId", expiredPartyId).update();
        String expiredRecordId = createAndSubmitInventoryRecord();
        String expiredBatch = stageMarketResolution(
                jdbc, expiredRecordId, expiredPointId, 1, "future-effective-subject");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applyResolution(jdbc, expiredBatch))
                .hasMessageContaining("outside its governed region or effective date");
        assertInventoryApprovalRemainsPending(expiredRecordId);
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
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
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
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
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
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
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

    @Test
    void marksEndingInventoryForGovernanceReviewWithoutPrivateContractInputs() throws Exception {
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleFactDraft(
                                "CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"))
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_OWNERSHIP_TYPE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_SUBJECT_CODE").doesNotExist());
    }

    @Test
    void filtersByExplicitSurveyPeriodAndRealDraftOrSubmissionTime() throws Exception {
        String id = create("CORN", "FEED_MILL", "MOISTURE");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                UPDATE market.market_record
                SET created_at=TIMESTAMPTZ '2026-08-05 09:00:00+08',
                    reported_at=TIMESTAMPTZ '2030-01-01 09:00:00+08'
                WHERE record_id=:id
                """).param("id", id).update();

        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026").queryParam("filter.surveyMonth", "8")
                        .queryParam("filter.fillingDateFrom", "2026-08-05")
                        .queryParam("filter.fillingDateTo", "2026-08-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].values.MKT_SURVEY_YEAR").value("2026"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_SURVEY_MONTH").value("8"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_SURVEY_PERIOD_PRECISION").value("YEAR_MONTH"))
                .andExpect(jsonPath("$.data.items[0].values.MKT_FILLING_TIME_BASIS").value("DRAFT_CREATED_AT"));

        jdbc.sql("""
                UPDATE market.market_record SET survey_month=NULL,survey_period_precision='YEAR'
                WHERE record_id=:id
                """).param("id", id).update();
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026").queryParam("filter.surveyMonth", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT submitted_at IS NOT NULL FROM market.market_record WHERE record_id=:id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        jdbc.sql("""
                UPDATE market.market_record SET submitted_at=TIMESTAMPTZ '2026-08-06 10:30:00+08'
                WHERE record_id=:id
                """).param("id", id).update();
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.fillingDateFrom", "2026-08-06")
                        .queryParam("filter.fillingDateTo", "2026-08-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].values.MKT_FILLING_TIME_BASIS").value("SUBMITTED_AT"));
    }

    private String create(String product, String objectType, String qualityCode) throws Exception {
        return create(draftBody(product, objectType, qualityCode, null));
    }

    private String create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void approve(String id) throws Exception {
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
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
        return singleFactDraft(product, objectType, factCode, value, true);
    }

    private String singleFactDraft(
            String product, String objectType, String factCode, String value,
            boolean includeInventoryContract) {
        String inventoryContract = "";
        return """
                {"productCode":"%s","coreValues":{
                 "MKT_OBJECT_TYPE":"%s","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK"%s,%s},
                 "facts":{"%s":"%s"},"evidencePhotoIds":["%s"]}
                """.formatted(
                        product, objectType, inventoryContract, submissionMetadata(), factCode, value,
                        stageEvidencePhoto());
    }

    private UUID stageEvidencePhoto() {
        UUID id = UUID.randomUUID();
        JdbcClient.create(dataSource).sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','market-fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,'市场测试水印','market-tester',now())
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

    private String createAndSubmitInventoryRecord() throws Exception {
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(singleFactDraft(
                                "CORN", "FEED_MILL", "ENDING_INVENTORY", "20", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").value("待库存权属核定"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        return id;
    }

    private InventoryIdentity createApprovedInventoryProfile(
            JdbcClient jdbc, String partyName, String pointName,
            double longitude, double latitude, String approvalBasis) {
        return createApprovedInventoryProfile(
                jdbc, partyName, pointName, longitude, latitude, approvalBasis,
                "230200", "2026-01-01");
    }

    private InventoryIdentity createApprovedInventoryProfile(
            JdbcClient jdbc, String partyName, String pointName,
            double longitude, double latitude, String approvalBasis,
            String regionCode, String effectiveFrom) {
        UUID partyId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO market.business_party(
                  party_id,current_name,version,created_at,created_by,updated_at,updated_by)
                VALUES(:partyId,:partyName,0,now(),'production-tester',now(),'production-tester')
                """).param("partyId", partyId).param("partyName", partyName).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,owner_party_id,canonical_name,region_code,approval_state,
                  location_state,governed_point,effective_from,created_by,updated_by)
                VALUES(:pointId,'SURVEY_SITE',:partyId,:pointName,:regionCode,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),CAST(:effectiveFrom AS date),
                  'production-tester','production-tester')
                """).param("pointId", pointId).param("partyId", partyId).param("pointName", pointName)
                .param("regionCode", regionCode).param("effectiveFrom", effectiveFrom)
                .param("longitude", longitude).param("latitude", latitude).update();
        jdbc.sql("""
                INSERT INTO market.sample_point_inventory_contract(
                  sample_point_id,object_type_code,ownership_type,cargo_owner_party_id,policy_attribute,
                  effective_from,approved_by,approval_basis,approved_at)
                VALUES(:pointId,'FEED_MILL','OWNED',:partyId,'COMMERCIAL',CAST(:effectiveFrom AS date),
                  'production-tester',:approvalBasis,now())
                """).param("pointId", pointId).param("partyId", partyId)
                .param("effectiveFrom", effectiveFrom).param("approvalBasis", approvalBasis).update();
        return new InventoryIdentity(partyId, pointId);
    }

    private ApprovedInventoryRecord createApprovedInventoryRecord(
            JdbcClient jdbc, InventoryIdentity identity, String stableSubject) throws Exception {
        String recordId = createAndSubmitInventoryRecord();
        String batchId = stageMarketResolution(jdbc, recordId, identity.samplePointId(), 1, stableSubject);
        assertThat(applyResolution(jdbc, batchId)).isEqualTo("APPLIED");
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", recordId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        UUID revisionId = jdbc.sql("""
                SELECT resolution_revision_id FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:recordId
                """).param("recordId", recordId).query(UUID.class).single();
        return new ApprovedInventoryRecord(recordId, revisionId);
    }

    private String createApprovedUnlinkedMarketSubject(
            JdbcClient jdbc, String stableSubject) throws Exception {
        String recordId = create("CORN", "FEED_MILL", "MOISTURE");
        approve(recordId);
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,product_code,field_code,domain_binding,value)
                VALUES(:recordId,'CORN','MKT_SAMPLE_SUBJECT_CODE','EXTENSION',:stableSubject)
                """).param("recordId", recordId).param("stableSubject", stableSubject).update();
        assertThat(jdbc.sql("""
                SELECT status_code='APPROVED' AND sample_point_id IS NULL
                FROM market.market_record WHERE record_id=:recordId
                """).param("recordId", recordId).query(Boolean.class).single()).isTrue();
        return recordId;
    }

    private void installResolutionRaceDelay(JdbcClient jdbc) {
        jdbc.sql("""
                CREATE FUNCTION registry.delay_market_resolution_revision_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $trigger$
                BEGIN
                  PERFORM pg_sleep(0.5);
                  RETURN NEW;
                END
                $trigger$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER delay_market_resolution_revision_for_test
                BEFORE INSERT ON registry.sample_subject_resolution_revision
                FOR EACH ROW
                WHEN (NEW.source_domain='MARKET')
                EXECUTE FUNCTION registry.delay_market_resolution_revision_for_test()
                """).update();
    }

    private void installCrossProjectionRaceDelay(JdbcClient jdbc) {
        installResolutionRaceDelay(jdbc);
        jdbc.sql("""
                CREATE FUNCTION registry.delay_legacy_subject_identity_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $trigger$
                BEGIN
                  PERFORM pg_sleep(0.5);
                  RETURN NEW;
                END
                $trigger$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER delay_legacy_subject_identity_for_test
                BEFORE INSERT ON registry.sample_point_subject_identity
                FOR EACH ROW
                WHEN (NEW.business_domain='MARKET')
                EXECUTE FUNCTION registry.delay_legacy_subject_identity_for_test()
                """).update();
    }

    private List<CorrectionOutcome> runCorrectionsConcurrently(
            String firstBatch, String secondBatch) throws Exception {
        return runResolutionsConcurrently(
                firstBatch, ResolutionEntryPoint.CORRECT,
                secondBatch, ResolutionEntryPoint.CORRECT);
    }

    private List<CorrectionOutcome> runResolutionsConcurrently(
            String firstBatch, ResolutionEntryPoint firstEntryPoint,
            String secondBatch, ResolutionEntryPoint secondEntryPoint) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> runResolutionTransaction(firstBatch, firstEntryPoint, ready, start));
            var second = executor.submit(
                    () -> runResolutionTransaction(secondBatch, secondEntryPoint, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private List<CorrectionOutcome> runLegacyRegistrationAndResolutionConcurrently(
            String legacySourceRecord, UUID legacyTargetPoint,
            String resolutionBatch, ResolutionEntryPoint resolutionEntryPoint) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var legacy = executor.submit(() -> runLegacyRegistrationTransaction(
                    legacySourceRecord, legacyTargetPoint, ready, start));
            var resolution = executor.submit(() -> runResolutionTransaction(
                    resolutionBatch, resolutionEntryPoint, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(legacy.get(15, TimeUnit.SECONDS), resolution.get(15, TimeUnit.SECONDS));
        }
    }

    private List<CorrectionOutcome> runGovernedSubjectApplyAndResolutionConcurrently(
            long governedRequest, String resolutionBatch,
            ResolutionEntryPoint resolutionEntryPoint) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var governed = executor.submit(
                    () -> runGovernedSubjectApplyTransaction(governedRequest, ready, start));
            var resolution = executor.submit(() -> runResolutionTransaction(
                    resolutionBatch, resolutionEntryPoint, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(governed.get(15, TimeUnit.SECONDS), resolution.get(15, TimeUnit.SECONDS));
        }
    }

    private List<CorrectionOutcome> runGovernedSubjectAppliesConcurrently(
            long firstRequest, long secondRequest) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> runGovernedSubjectApplyTransaction(firstRequest, ready, start));
            var second = executor.submit(
                    () -> runGovernedSubjectApplyTransaction(secondRequest, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private long prepareApprovedSubjectInsert(String stableSubject, UUID targetPointId) throws Exception {
        return prepareApprovedSubjectChange(
                "MARKET", stableSubject, targetPointId, "INSERT", null);
    }

    private long prepareApprovedSubjectChange(
            String domain, String stableSubject, UUID targetPointId,
            String operation, String suppliedSnapshot) throws Exception {
        String snapshot = suppliedSnapshot == null ? """
                {"business_domain":"%s","subject_id":"%s","sample_point_id":"%s",
                 "created_at":"2026-08-13T20:00:00+08:00","created_by":"production-tester"}
                """.formatted(domain, stableSubject, targetPointId) : suppliedSnapshot;
        long requestId = withOfficialMasterDataRole(
                APPLICANT_LOGIN, APPLICANT_ROLE, connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT platform.submit_master_data_change(
                              'SUBJECT',?,?,CAST(? AS jsonb),clock_timestamp(),?)
                            """)) {
                        statement.setString(1, domain + ":" + stableSubject);
                        statement.setString(2, operation);
                        statement.setString(3, snapshot);
                        statement.setString(4, "正式三角色身份申请");
                        try (var result = statement.executeQuery()) {
                            result.next();
                            return result.getLong(1);
                        }
                    }
                });
        withOfficialMasterDataRole(REVIEWER_LOGIN, REVIEWER_ROLE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT platform.review_master_data_change(?,'APPROVE',?)")) {
                statement.setLong(1, requestId);
                statement.setString(2, "正式三角色独立复核");
                statement.executeQuery();
                return null;
            }
        });
        return requestId;
    }

    private void applyApprovedSubjectChange(long requestId) throws Exception {
        withOfficialMasterDataRole(APPLIER_LOGIN, APPLIER_ROLE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT platform.apply_master_data_change(?)")) {
                statement.setLong(1, requestId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getBoolean(1)).isTrue();
                    return null;
                }
            }
        });
    }

    private String legacySubjectSnapshot(JdbcClient jdbc, String domain, String stableSubject) {
        return jdbc.sql("""
                SELECT to_jsonb(identity_row)::text
                FROM registry.sample_point_subject_identity identity_row
                WHERE business_domain=:domain AND subject_id=:stableSubject
                """).param("domain", domain).param("stableSubject", stableSubject)
                .query(String.class).single();
    }

    private String replaceSubjectPoint(JdbcClient jdbc, String snapshot, UUID targetPointId) {
        return jdbc.sql("""
                SELECT (CAST(:snapshot AS jsonb)
                  || jsonb_build_object('sample_point_id',CAST(:samplePointId AS text)))::text
                """).param("snapshot", snapshot).param("samplePointId", targetPointId)
                .query(String.class).single();
    }

    private CorrectionOutcome runGovernedSubjectApplyTransaction(
            long requestId, CountDownLatch ready, CountDownLatch start) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement authorization = connection.createStatement()) {
                authorization.execute("SET SESSION AUTHORIZATION " + APPLIER_LOGIN);
                authorization.execute("SET ROLE " + APPLIER_ROLE);
            }
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                connection.rollback();
                return CorrectionOutcome.failure("concurrency start timed out");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT platform.apply_master_data_change(?)")) {
                statement.setLong(1, requestId);
                statement.setQueryTimeout(10);
                try (var result = statement.executeQuery()) {
                    result.next();
                    boolean applied = result.getBoolean(1);
                    connection.commit();
                    return applied
                            ? CorrectionOutcome.success("APPLIED")
                            : CorrectionOutcome.failure("master data request was not applied");
                }
            } catch (Exception failure) {
                connection.rollback();
                return CorrectionOutcome.failure(rootMessage(failure));
            } finally {
                try (Statement authorization = connection.createStatement()) {
                    authorization.execute("RESET ROLE");
                    authorization.execute("RESET SESSION AUTHORIZATION");
                }
            }
        } catch (Exception failure) {
            return CorrectionOutcome.failure(rootMessage(failure));
        }
    }

    private <T> T withOfficialMasterDataRole(
            String login, String role, SqlConnectionWork<T> work) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement authorization = connection.createStatement()) {
            authorization.execute("SET SESSION AUTHORIZATION " + login);
            authorization.execute("SET ROLE " + role);
            try {
                return work.run(connection);
            } finally {
                authorization.execute("RESET ROLE");
                authorization.execute("RESET SESSION AUTHORIZATION");
            }
        }
    }

    private CorrectionOutcome runLegacyRegistrationTransaction(
            String sourceRecordId, UUID targetPointId,
            CountDownLatch ready, CountDownLatch start) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                connection.rollback();
                return CorrectionOutcome.failure("concurrency start timed out");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT platform.register_approved_sample_subject('MARKET',?,?::uuid)
                    """)) {
                statement.setString(1, sourceRecordId);
                statement.setString(2, targetPointId.toString());
                statement.setQueryTimeout(10);
                try (var result = statement.executeQuery()) {
                    result.next();
                    String value = result.getString(1);
                    connection.commit();
                    return CorrectionOutcome.success(value);
                }
            } catch (Exception failure) {
                connection.rollback();
                return CorrectionOutcome.failure(rootMessage(failure));
            }
        } catch (Exception failure) {
            return CorrectionOutcome.failure(rootMessage(failure));
        }
    }

    private CorrectionOutcome runResolutionTransaction(
            String batchId, ResolutionEntryPoint entryPoint,
            CountDownLatch ready, CountDownLatch start) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                connection.rollback();
                return CorrectionOutcome.failure("concurrency start timed out");
            }
            String function = entryPoint == ResolutionEntryPoint.APPLY
                    ? "registry.apply_sample_subject_resolution"
                    : "registry.correct_approved_market_inventory_resolution";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + function + "(?::uuid,?)")) {
                statement.setString(1, batchId);
                statement.setString(2, "production-tester");
                statement.setQueryTimeout(10);
                try (var result = statement.executeQuery()) {
                    result.next();
                    String value = result.getString(1);
                    connection.commit();
                    return CorrectionOutcome.success(value);
                }
            } catch (Exception failure) {
                connection.rollback();
                return CorrectionOutcome.failure(rootMessage(failure));
            }
        } catch (Exception failure) {
            return CorrectionOutcome.failure(rootMessage(failure));
        }
    }

    private void assertExactlyOneCorrectionSucceeded(
            List<CorrectionOutcome> outcomes, String expectedConflict) {
        assertExactlyOneResolutionSucceeded(outcomes, expectedConflict);
    }

    private void assertExactlyOneResolutionSucceeded(
            List<CorrectionOutcome> outcomes, String expectedConflict) {
        assertThat(outcomes).filteredOn(CorrectionOutcome::succeeded).hasSize(1)
                .allSatisfy(outcome -> assertThat(outcome.message()).isEqualTo("APPLIED"));
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded()).hasSize(1)
                .allSatisfy(outcome -> {
                    assertThat(outcome.message()).contains(expectedConflict);
                    assertThat(outcome.message()).doesNotContainIgnoringCase("deadlock");
                });
    }

    private void assertExactlyOneCrossProjectionWriterSucceeded(
            List<CorrectionOutcome> outcomes, String expectedConflict) {
        assertThat(outcomes).filteredOn(CorrectionOutcome::succeeded).hasSize(1)
                .allSatisfy(outcome -> assertThat(outcome.message()).matches("APPLIED|[0-9]+"));
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded()).hasSize(1)
                .allSatisfy(outcome -> {
                    assertThat(outcome.message()).contains(expectedConflict);
                    assertThat(outcome.message()).doesNotContainIgnoringCase("deadlock");
                });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }

    private String stageMarketResolution(
            JdbcClient jdbc, String recordId, UUID pointId, long expectedVersion, String stableSubject) {
        String batchId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_batch(
                  batch_id,idempotency_key,input_digest,expected_item_count,status_code,created_at,created_by)
                VALUES(CAST(:batchId AS uuid),:key,repeat('b',64),1,'STAGED',now(),'production-tester')
                """).param("batchId", batchId).param("key", "market-resolution-" + batchId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_item(
                  batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                  resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code)
                VALUES(CAST(:batchId AS uuid),1,'MARKET',:recordId,:version,'LINK',:stableSubject,
                  :pointId,'EXT_007_EXPLICIT_DISPOSITION','STAGED')
                """).param("batchId", batchId).param("recordId", recordId)
                .param("version", expectedVersion).param("stableSubject", stableSubject)
                .param("pointId", pointId).update();
        return batchId;
    }

    private String stageApprovedMarketCorrection(
            JdbcClient jdbc, String recordId, UUID pointId, long expectedVersion,
            String stableSubject, UUID expectedPredecessorRevisionId) {
        return stageApprovedMarketCorrectionBatch(jdbc, List.of(
                new CorrectionItem(recordId, pointId, expectedVersion,
                        stableSubject, expectedPredecessorRevisionId)));
    }

    private String stageApprovedMarketCorrectionBatch(
            JdbcClient jdbc, List<CorrectionItem> items) {
        String batchId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_batch(
                  batch_id,idempotency_key,input_digest,expected_item_count,status_code,created_at,created_by)
                VALUES(CAST(:batchId AS uuid),:key,repeat('d',64),:itemCount,
                  'STAGED',now(),'production-tester')
                """).param("batchId", batchId).param("key", "market-correction-" + batchId)
                .param("itemCount", items.size()).update();
        for (int index = 0; index < items.size(); index++) {
            CorrectionItem item = items.get(index);
            jdbc.sql("""
                    INSERT INTO registry.sample_subject_resolution_item(
                      batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                      resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code,
                      expected_predecessor_resolution_revision_id)
                    VALUES(CAST(:batchId AS uuid),:sequence,'MARKET',:recordId,:version,'LINK',:stableSubject,
                      :pointId,'EXT_007_APPROVED_INVENTORY_CORRECTION','STAGED',:predecessorId)
                    """).param("batchId", batchId).param("sequence", index + 1)
                    .param("recordId", item.recordId()).param("version", item.expectedVersion())
                    .param("stableSubject", item.stableSubject())
                    .param("pointId", item.pointId()).param("predecessorId", item.predecessorRevisionId())
                    .update();
        }
        return batchId;
    }

    private String applyResolution(JdbcClient jdbc, String batchId) {
        return jdbc.sql("""
                SELECT registry.apply_sample_subject_resolution(CAST(:batchId AS uuid),:actor)
                """).param("batchId", batchId).param("actor", "production-tester")
                .query(String.class).single();
    }

    private String correctApprovedMarketResolution(JdbcClient jdbc, String batchId) {
        return jdbc.sql("""
                SELECT registry.correct_approved_market_inventory_resolution(
                  CAST(:batchId AS uuid),:actor)
                """).param("batchId", batchId).param("actor", "production-tester")
                .query(String.class).single();
    }

    private record InventoryIdentity(UUID partyId, UUID samplePointId) {}
    private record ApprovedInventoryRecord(String recordId, UUID revisionId) {}
    private record CorrectionItem(
            String recordId, UUID pointId, long expectedVersion,
            String stableSubject, UUID predecessorRevisionId) {}
    private enum ResolutionEntryPoint { APPLY, CORRECT }
    private record CorrectionOutcome(boolean succeeded, String message) {
        static CorrectionOutcome success(String message) {
            return new CorrectionOutcome(true, message);
        }

        static CorrectionOutcome failure(String message) {
            return new CorrectionOutcome(false, message);
        }
    }

    @FunctionalInterface
    private interface SqlConnectionWork<T> {
        T run(Connection connection) throws Exception;
    }

    private void assertInventoryApprovalRemainsPending(String id) throws Exception {
        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_INVENTORY_GOVERNANCE_PENDING"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("""
                SELECT party_id IS NULL AND sample_point_id IS NULL
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT status_code FROM market.market_inventory_governance WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.current_sample_subject_resolution
                WHERE source_domain='MARKET' AND source_record_id=:id AND resolution_action='LINK'
                """).param("id", id).query(Long.class).single()).isZero();
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
