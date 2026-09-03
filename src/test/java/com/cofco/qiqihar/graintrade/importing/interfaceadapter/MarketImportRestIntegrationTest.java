package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportTemplateCatalog;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class MarketImportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired MarketImportPort market;
    @Autowired BusinessImportTemplateCatalog templateCatalog;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  market.market_record,evidence.evidence_photo,registry.sample_point
                  RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230200',ST_Multi(ST_MakeEnvelope(122,46,125,49,4326)),
                  'market import sample-point fixture','urn:test:market-import-sample-point','test-v1',
                  'Test fixture','230200',DATE '2026-08-18',repeat('8',64))
                ON CONFLICT (region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();
    }

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,market.market_record,evidence.evidence_photo,registry.sample_point RESTART IDENTITY CASCADE").update();
    }

    @Test
    void importsAnOwnedMarketEvidenceRowAtomicallyAndLocksTheReporterToTheAuthenticatedEmployee() throws Exception {
        String photoId = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n" + row(photoId, "14.6");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.domainCode").value("MARKET"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("""
                        SELECT value FROM market.market_record_core_value
                        WHERE field_code='MKT_REPORTER_NAME'
                        """).query(String.class).single())
                .isEqualTo("市场测试员");
        assertThat(jdbc.sql("""
                        SELECT value FROM market.market_record_core_value
                        WHERE field_code='MKT_SURVEYOR_PHONE'
                        """).query(String.class).single())
                .isEqualTo("13800000000");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM market.market_record_core_value
                        WHERE field_code='MKT_REPORTER_PHONE'
                        """).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT attached_domain FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)")
                        .param("id", photoId).query(String.class).single())
                .isEqualTo("MARKET");
    }

    @Test
    void publishesTheServerTemplateAndReplaysTheSameIdempotentJob() throws Exception {
        mvc.perform(get("/api/v1/imports/market/template").principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(MarketImportTemplate.csv()));

        String photoId = uploadEvidence();
        byte[] csv = (String.join(",", MarketImportTemplate.HEADERS) + "\n" + row(photoId, "14.6"))
                .getBytes(StandardCharsets.UTF_8);
        String first = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(first.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"))
                .isEqualTo(second.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isOne();
    }

    @Test
    void importsTheSameServerOwnedTemplateFromXlsx() throws Exception {
        String photoId = uploadEvidence();
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FEED_MILL");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("surveyYear", "2026");
        fields.put("surveyMonth", "8");
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_PURCHASE_BASE_PRICE", "2300");
        fields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        fields.put("MKT_PACKAGING_AMOUNT", "12");
        fields.put("MKT_FREIGHT_AMOUNT", "72");
        fields.put("MKT_PACKAGING_FORM", "BULK");
        fields.put("MKT_SURVEYOR_NAME", "王雷");
        fields.put("MKT_SURVEYOR_PHONE", "13800000000");
        fields.put("MKT_SAMPLE_NAME", "齐齐哈尔第一粮店");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        fields.put("PURCHASE_VOLUME", "12");
        fields.put("MOISTURE", "14.6");
        fields.put("ENDING_INVENTORY", "20");
        fields.put(MarketImportTemplate.EVIDENCE_PHOTO_ID, photoId);
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                template, List.of(values));

        String importResponse = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-xlsx-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String importJobId = importResponse.replaceFirst(
                "(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/imports/market")
                        .param("pageNumber", "0").param("pageSize", "5")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(importJobId))
                .andExpect(jsonPath("$.data.items[0].domainCode").value("MARKET"))
                .andExpect(jsonPath("$.data.items[0].importedRows").value(1))
                .andExpect(jsonPath("$.data.items[0].failedRows").value(0));

        mvc.perform(get("/api/v1/imports/market")
                        .param("pageNumber", "0").param("pageSize", "5")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(importJobId));

        mvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.MKT_STORAGE_REGION_CODE").doesNotExist());
    }

    @Test
    void importsAndRequeriesIndividuallyBlankMarketBusinessValues() throws Exception {
        var template = internalTemplate("CORN", "TRADER");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("surveyYear", "2026");
        fields.put("surveyMonth", "8");
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_SAMPLE_NAME", "市场业务字段留空样本");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(values));

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "optional-market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN").param("objectTypeCode", "TRADER")
                        .header("Idempotency-Key", "optional-market")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        String recordId = jdbc.sql("SELECT record_id FROM market.market_record")
                .query(String.class).single();
        mvc.perform(get("/api/v1/market-records/{id}", recordId).principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_PURCHASE_BASE_PRICE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_SALE_BASE_PRICE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_CARRIAGE_BOARD_AMOUNT").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_PACKAGING_AMOUNT").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_FREIGHT_AMOUNT").doesNotExist());
    }

    @Test
    void importsPublicInventoryFieldsDirectlyIntoPendingReviewWithoutHiddenContracts() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FEED_MILL");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("surveyYear", "2026");
        fields.put("surveyMonth", "8");
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_PURCHASE_BASE_PRICE", "2300");
        fields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        fields.put("MKT_PACKAGING_AMOUNT", "12");
        fields.put("MKT_FREIGHT_AMOUNT", "72");
        fields.put("MKT_PACKAGING_FORM", "BULK");
        fields.put("MKT_SURVEYOR_NAME", "王雷");
        fields.put("MKT_SURVEYOR_PHONE", "13800000000");
        fields.put("MKT_SAMPLE_NAME", "条件字段失败粮店");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        fields.put("ENDING_INVENTORY", "20");
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(values));

        String response = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "missing-inventory-contract.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "missing-inventory-contract")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("COMPLETED");
        String recordId = jdbc.sql("SELECT record_id FROM market.market_record").query(String.class).single();
        mvc.perform(post("/api/v1/market-records/{id}/submit", recordId)
                        .principal(() -> "market-tester")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_STORAGE_REGION_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist());
        mvc.perform(post("/api/v1/market-records/{id}/approve", recordId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record_core_value
                WHERE record_id=:id AND field_code LIKE 'MKT_INVENTORY_%'
                """).param("id", recordId).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsASoybeanWorkbookFromTheCornMenuBeforeAnyDurableEffect() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .param("objectTypeCode", "TRADER")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var soybean = internalTemplate("SOYBEAN", "TRADER");
        byte[] workbook = BusinessImportWorkbook.create(soybean, List.of(
                java.util.Collections.nCopies(soybean.headers().size(), "")));

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "soybean-market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "TRADER")
                        .header("Idempotency-Key", "market-context-mismatch")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_CONTEXT_MISMATCH"));

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isZero();
    }

    @Test
    void selectedObjectQueryStillDownloadsTheUnifiedMultiSheetWorkbook() throws Exception {
        var response = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "RESERVE_ENTERPRISE")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("市场-玉米-批量导入模板.xlsx");
        byte[] workbook = response.getContentAsByteArray();

        assertThat(com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                .parseWorksheetNames(workbook)).contains("贸易商", "深加工企业", "农资店", "填报说明");
        assertThat(BusinessImportWorkbook.context(workbook, "MARKET").productCode()).isEqualTo("CORN");
        assertThat(BusinessImportWorkbook.context(workbook, "MARKET").objectTypeCode()).isNull();
    }

    @Test
    void deepProcessorWorkbookOmitsSalesFieldsWhileTraderKeepsThem() throws Exception {
        byte[] deepProcessor = marketWorkbook("CORN", "DEEP_PROCESSOR");
        byte[] trader = marketWorkbook("CORN", "TRADER");

        List<String> names = com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                .parseWorksheetNames(deepProcessor);
        List<String> deepProcessorLabels = withoutTrailingBlanks(
                com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(deepProcessor, names.indexOf("深加工企业") + 1, 256).getFirst());
        List<String> traderLabels = withoutTrailingBlanks(
                com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(trader, names.indexOf("贸易商") + 1, 256).getFirst());

        assertThat(deepProcessorLabels)
                .doesNotContain("采集对象销售价格（元/吨）", "销售量（吨）");
        assertThat(traderLabels)
                .contains("采集对象销售价格（元/吨）", "销售量（吨）");
    }

    @Test
    void allObjectTypesDownloadAsSeparateLockedWorksheets() throws Exception {
        byte[] workbook = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        String workbookXml = zipEntry(workbook, "xl/workbook.xml");
        assertThat(workbookXml)
                .contains("name=\"贸易商\"", "name=\"深加工企业\"", "name=\"农资店\"")
                .doesNotContain("name=\"市场填报\"");
        assertThat(withoutTrailingBlanks(
                com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(workbook, 2, 256).getFirst()))
                .doesNotContain("采集对象销售价格（元/吨）", "销售量（吨）");
        List<String> names = com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                .parseWorksheetNames(workbook);
        int agriculturalInputSheet = names.indexOf("农资店") + 1;
        assertThat(withoutTrailingBlanks(
                com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(workbook, agriculturalInputSheet, 256).getFirst()))
                .contains("种子销售量（公斤）", "种子零售价（元/公斤）", "供货状态", "种植意向趋势");
    }

    @Test
    void importsHumanEnteredMultiSheetValuesAndReportsInvalidCellsBySheetRowAndColumn() throws Exception {
        var trader = internalTemplate("CORN", "TRADER");
        java.util.Map<String, String> validFields = new java.util.HashMap<>();
        validFields.put("surveyYear", "２０２６");
        validFields.put("surveyMonth", " 8 ");
        validFields.put("MKT_SAMPLE_NAME", "　多表贸易样本　");
        validFields.put("MKT_REGION", " 230200 ");
        validFields.put("MKT_SAMPLE_CONTACT", "13800000000");
        validFields.put("MKT_SAMPLE_LATITUDE", "47.35");
        validFields.put("MKT_SAMPLE_LONGITUDE", "123.91");
        validFields.put("MKT_TRADE_DATE", "2026年8月3日");
        validFields.put("MKT_PURCHASE_BASE_PRICE", "2，300.12345 元/吨");
        validFields.put("MKT_SALE_BASE_PRICE", "2400.55555元/吨");
        validFields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36元");
        validFields.put("MKT_PACKAGING_AMOUNT", "12 元");
        validFields.put("MKT_FREIGHT_AMOUNT", "72元");
        validFields.put("MKT_PACKAGING_FORM", "　散装　");
        validFields.put("PURCHASE_VOLUME", "12.34567吨");
        validFields.put("SALES_VOLUME", "8.76543 吨");
        validFields.put("ENDING_INVENTORY", "20.00009吨");
        byte[] validWorkbook = cornWorkbook(Map.of("TRADER", List.of(values(trader, validFields))));

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-玉米-批量导入模板.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                validWorkbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "market-multi-human-valid")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1));

        String recordId = jdbc.sql("SELECT record_id FROM market.market_record")
                .query(String.class).single();
        assertThat(jdbc.sql("SELECT purchase_base_price FROM market.market_record WHERE record_id=:id")
                .param("id", recordId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("2300.1235");
        assertThat(jdbc.sql("SELECT sale_base_price FROM market.market_record WHERE record_id=:id")
                .param("id", recordId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("2400.5556");
        assertThat(jdbc.sql("SELECT value FROM market.market_record_fact WHERE record_id=:id AND fact_code='SALES_VOLUME'")
                .param("id", recordId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("8.7654");
        mvc.perform(get("/api/v1/market-records/{id}", recordId).principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_SAMPLE_NAME").value("多表贸易样本"))
                .andExpect(jsonPath("$.data.facts.SALES_VOLUME").value(8.7654));

        var deep = internalTemplate("CORN", "DEEP_PROCESSOR");
        java.util.Map<String, String> invalidFields = new java.util.HashMap<>();
        invalidFields.put("surveyYear", "2026");
        invalidFields.put("MKT_SAMPLE_NAME", "多表非法深加工样本");
        invalidFields.put("MKT_REGION", "230200");
        invalidFields.put("MKT_PURCHASE_BASE_PRICE", "价格待定");
        byte[] invalidWorkbook = cornWorkbook(
                Map.of("DEEP_PROCESSOR", List.of(values(deep, invalidFields))));
        String response = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-玉米-批量导入模板.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                invalidWorkbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "market-multi-human-invalid")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/imports/market/{jobId}/errors", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("深加工企业")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("工作表行号")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("错误列")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("采集对象收购价格")));
    }

    private byte[] cornWorkbook(Map<String, List<List<String>>> rowsByObjectType) {
        return BusinessImportWorkbook.createSheets(templateCatalog.objectTypes("MARKET", "CORN").stream()
                .map(option -> new BusinessImportWorkbook.WorkbookSheet(option.label(),
                        internalTemplate("CORN", option.code()),
                        rowsByObjectType.getOrDefault(option.code(), List.of())))
                .toList());
    }

    private static List<String> values(BusinessImportWorkbook.Template template, Map<String, String> fields) {
        return template.headers().stream().map(code -> fields.getOrDefault(code, "")).toList();
    }

    private byte[] marketWorkbook(String productCode, String objectTypeCode) throws Exception {
        return mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", productCode)
                        .param("objectTypeCode", objectTypeCode).principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static String zipEntry(byte[] bytes, String expectedName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (expectedName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("missing XLSX entry " + expectedName);
    }

    private BusinessImportWorkbook.Template internalTemplate(
            String productCode, String objectTypeCode) {
        return MarketImportTemplate.workbook(market.definition(productCode, objectTypeCode));
    }

    private static java.util.List<String> withoutTrailingBlanks(java.util.List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return java.util.List.copyOf(values.subList(0, size));
    }

    @Test
    void rejectsHostileMarketDecimalsBeforeWritingAnyRecord() throws Exception {
        String photoId = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n"
                + row(photoId, "1E999999999");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-hostile-decimal")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
    }

    @Test
    void returnsPerRowErrorsAndRollsBackTheEntireMarketBatch() throws Exception {
        String firstPhoto = uploadEvidence();
        String invalidPhoto = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n"
                + row(firstPhoto, "14.6") + row(invalidPhoto, "not-a-decimal");

        String response = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-invalid-row").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/market/{jobId}/errors", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NOT_IMPORTED_ATOMIC_BATCH")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);
        mvc.perform(get("/api/v1/imports/market/{jobId}/errors", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ERROR_FILE_NOT_ALLOWED"));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);

        String retryResponse = mvc.perform(post("/api/v1/imports/market/{jobId}/retries", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retryOf").value(jobId))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String retryJobId = retryResponse.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE import_job_id=:job")
                .param("job", UUID.fromString(jobId)).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE import_job_id=:job")
                .param("job", UUID.fromString(retryJobId)).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isZero();
    }

    private long importErrorDownloadAuditCount(String jobId) {
        return jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId).query(Long.class).single();
    }

    private static String row(String photoId, String moisture) {
        return String.join(",", "CORN", "FEED_MILL", "230200", "2026-08-01", "2300", "", "36", "12", "72", "BULK",
                "客户端伪造姓名", "13800000000", "齐齐哈尔第一粮店", "13900000000", "47.3543", "123.9182",
                "12", moisture, photoId) + "\n";
    }

    private String uploadEvidence() throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "market.png", "image/png", png()))
                        .param("capturedAt", "2026-08-09T08:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔市 市场采集").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(40, 120, 80));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

}
