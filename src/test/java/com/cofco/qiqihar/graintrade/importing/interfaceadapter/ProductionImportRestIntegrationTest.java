package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionImportRestIntegrationTest {
    private static final String PHOTO_ID = "00000000-0000-0000-0000-000000000011";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ProductionImportPort production;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(CAST(:id AS uuid),'STAGED','fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,'测试水印','production-tester',now())
                """).param("id", PHOTO_ID).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order) VALUES('IMPORT_LIMITED','导入受限测试单位',9901)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES('IMPORT_LIMITED','230200')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('limited-importer','受限导入员','IMPORT_LIMITED') ON CONFLICT(subject_id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code) VALUES('limited-importer','BUSINESS_OPERATOR')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES('limited-importer','230200')
                ON CONFLICT DO NOTHING
                """).update();
    }

    @AfterEach
    void cleanAfterEach() {
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void downloadsAProductSpecificXlsxTemplateWithoutAReporterInput() throws Exception {
        var response = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("产情-玉米-批量导入模板.xlsx");
        byte[] workbook = response.getContentAsByteArray();

        var template = downloadedProductTemplate(workbook, "产情", "CORN");
        assertThat(XlsxTable.parseWorksheet(workbook, 1, template.headers().size()))
                .containsExactly(template.labels());
        assertThat(template.headers()).isEqualTo(template.labels())
                .allSatisfy(header -> assertThat(header)
                        .doesNotContain("_")
                        .doesNotMatch(".*[A-Za-z].*"));
        assertThat(template.labels()).startsWith(
                "样本点类型", "数据年份", "数据月份", "样本点名称", "地区");
        assertThat(template.labels())
                .contains("预计收获面积（亩）", "水分（%）", "地租（元/亩）",
                        "期初库存（吨）", "期末余粮（吨）")
                .doesNotContain("具体品种", "所在地区代码", "未销售余粮（吨）");
    }

    @Test
    void importsTheDownloadedXlsxProtocolWithSurveyDetailsAndServerOwnedReporter() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FARMER");
        java.util.ArrayList<String> row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "地区",
                "齐齐哈尔市 / 梅里斯达斡尔族区");
        put(row, template.headers(), "数据年份", "2026");
        put(row, template.headers(), "数据月份", "8");
        put(row, template.headers(), "播种面积（亩）", "100");
        put(row, template.headers(), "预计单产（公斤/亩）", "500");
        put(row, template.headers(), "调研人", "王雷");
        put(row, template.headers(), "调研人联系方式", "13800000000");
        put(row, template.headers(), "样本点联系方式", "13900000000");
        put(row, template.headers(), "纬度（度）", "47.3543");
        put(row, template.headers(), "经度（度）", "123.9182");
        put(row, template.headers(), "样本点名称", "龙江县第一调查户");
        put(row, template.headers(), "预计收获面积（亩）", "96.5");
        put(row, template.headers(), "生育阶段", "灌浆期");
        put(row, template.headers(), "销售数量（吨）", "12");
        put(row, template.headers(), "水分（%）", "14.2");
        byte[] workbook = BusinessImportWorkbook.create(
                template, java.util.List.of(row));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "corn-production.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "corn-production-xlsx")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1));

        mvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_CULTIVAR").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_NAME").value("龙江县第一调查户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_HARVEST_AREA_MU").value("96.5"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_GROWTH_STAGE").value("灌浆期"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SALES_VOLUME").value("12"))
                .andExpect(jsonPath("$.data.items[0].values.MOISTURE").value("14.2000"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_REPORTER_NAME").value("产情测试员"));

        assertThat(jdbc.sql("SELECT region_code FROM production.production_record")
                .query(String.class).single()).isEqualTo("230208");
    }

    @Test
    void importsOptionalUnsoldSurplusWithoutAskingForInternalGovernanceKeys() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FARMER");
        java.util.ArrayList<String> row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "地区", "230208");
        put(row, template.headers(), "样本点名称", "库存合同测试样本点");
        put(row, template.headers(), "数据年份", "2026");
        put(row, template.headers(), "数据月份", "8");
        put(row, template.headers(), "播种面积（亩）", "100");
        put(row, template.headers(), "预计单产（公斤/亩）", "500");
        put(row, template.headers(), "调研人", "王雷");
        put(row, template.headers(), "调研人联系方式", "13800000000");
        put(row, template.headers(), "样本点联系方式", "13900000000");
        put(row, template.headers(), "纬度（度）", "47.3543");
        put(row, template.headers(), "经度（度）", "123.9182");
        put(row, template.headers(), "期初库存（吨）", "20");
        put(row, template.headers(), "期末余粮（吨）", "12");
        byte[] workbook = BusinessImportWorkbook.create(template, java.util.List.of(row));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "missing-surplus-contract.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "missing-surplus-contract")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        mvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_ENDING_INVENTORY").value("12"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_OPENING_INVENTORY").value("20"));
    }

    @Test
    void neverExposesAttachmentOrSurplusGovernanceKeysInTheBusinessWorkbook() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = downloadedProductTemplate(downloaded, "产情", "CORN");
        assertThat(template.headers())
                .allSatisfy(header -> assertThat(header)
                        .doesNotContain("_")
                        .doesNotMatch(".*[A-Za-z].*"))
                .doesNotContain("现场水印照片编号", "稳定主体码", "内部治理截止日期");
    }

    @Test
    void rejectsASoybeanWorkbookFromTheCornMenuBeforeAnyDurableEffect() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var soybean = internalTemplate("SOYBEAN", "FARMER");
        byte[] workbook = BusinessImportWorkbook.create(soybean, java.util.List.of(
                java.util.Collections.nCopies(soybean.headers().size(), "")));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "soybean-production.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-context-mismatch")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_CONTEXT_MISMATCH"));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsObsoleteBusinessColumnsWithoutFallingBackToLegacyXlsxParsing() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var current = internalTemplate("CORN", "FARMER");
        var obsoleteLabels = new java.util.ArrayList<>(current.labels());
        obsoleteLabels.set(0, "旧调查日期");
        var obsolete = new BusinessImportWorkbook.Template(
                current.domainCode(), current.domainLabel(), current.productCode(), current.objectTypeCode(),
                current.headers(), obsoleteLabels);
        byte[] workbook = BusinessImportWorkbook.create(obsolete, java.util.List.of(
                java.util.Collections.nCopies(obsolete.headers().size(), "")));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "obsolete-contract.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "obsolete-production-contract")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_CONTRACT_MISMATCH"));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job")
                .query(Long.class).single()).isZero();
    }

    @Test
    void explainsTheUnexpectedColumnBeforeCreatingAnImportTask() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = downloadedProductTemplate(downloaded, "产情", "SOYBEAN");
        var row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        row.set(0, "农户");
        byte[] workbook = BusinessImportWorkbook.create(template, java.util.List.of(row));
        byte[] extraColumn = replaceZipEntry(workbook, "xl/worksheets/sheet1.xml",
                content -> content.replace("</row></sheetData>",
                        "<c r=\"AM2\" t=\"inlineStr\"><is><t>多余照片.jpg</t></is></c></row></sheetData>"));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "soybean-extra-column.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", extraColumn))
                        .param("productCode", "SOYBEAN")
                        .header("Idempotency-Key", "soybean-extra-column")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IMPORT_FORMAT"))
                .andExpect(jsonPath("$.error.message")
                        .value("文件多出第 39 列，请删除模板之外的列后重试。"));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job")
                .query(Long.class).single()).isZero();
    }

    private static void put(java.util.List<String> row, java.util.List<String> headers,
            String header, String value) {
        row.set(headers.indexOf(header), value);
    }

    private BusinessImportWorkbook.Template internalTemplate(String productCode, String objectTypeCode) {
        return ProductionImportTemplate.workbook(
                production.importDefinition(productCode, objectTypeCode));
    }

    private static BusinessImportWorkbook.Template downloadedProductTemplate(
            byte[] workbook, String label, String productCode) {
        var rows = XlsxTable.parseWorksheet(workbook, 1, 256);
        java.util.List<String> labels = withoutTrailingBlanks(rows.get(0));
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(workbook, "PRODUCTION");
        return new BusinessImportWorkbook.Template("PRODUCTION", label, productCode, null,
                context.contractVersion(), context.contractDigest(), labels, labels, java.util.List.of());
    }

    private static java.util.List<String> withoutTrailingBlanks(java.util.List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return java.util.List.copyOf(values.subList(0, size));
    }

    private static byte[] replaceZipEntry(byte[] workbook, String expectedName, UnaryOperator<String> replace) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(workbook);
                ZipInputStream zipInput = new ZipInputStream(input, StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = zipInput.readAllBytes();
                if (expectedName.equals(entry.getName())) {
                    content = replace.apply(new String(content, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
                zipOutput.write(content);
                zipOutput.closeEntry();
            }
            zipOutput.finish();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void rejectsPathologicalAndOverPrecisionImportDecimalsWithoutProductionWrites() throws Exception {
        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230200,,2026-07-31,1E999999999,20,导入填报员,王雷,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000021
                CORN,FARMER,230200,,2026-07-31,100000000000000.0000,20,导入填报员,王雷,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000022
                """;

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "hostile-decimals.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "hostile-decimals")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
    }

    @Test
    void validatesRowsDownloadsErrorsAndRetriesWithoutDuplicatingTheOriginalImport() throws Exception {
        mvc.perform(get("/api/v1/imports/production/template").principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(
                        "productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId\n"));

        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230200,,2026-07-31,10.5,20,导入填报员,王雷,13800000000,13900000000,47.3543,123.9182,%s
                CORN,FARMER,230200,,bad-date,5,30,导入填报员,王雷,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000023
                """.formatted(PHOTO_ID);
        mvc.perform(multipart("/api/v1/imports/production").file(new MockMultipartFile("file", "outside.csv", "text/csv", """
                        productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                        CORN,FARMER,231100,,2026-07-31,10,20,导入填报员,王雷,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000024
                        """.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "outside-scope-import").principal(() -> "limited-importer"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        MockMultipartFile file = new MockMultipartFile("file", "production.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1"));

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId).principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);
        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ERROR_FILE_NOT_ALLOWED"));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);

        mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();

        mvc.perform(post("/api/v1/imports/production/{jobId}/retries", jobId).principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.retryOf").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT action_code FROM platform.business_audit_event ORDER BY occurred_at,event_id")
                .query(String.class).list()).contains("IMPORT_JOB_COMPLETED");
    }

    private long importErrorDownloadAuditCount(UUID jobId) {
        return jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId.toString()).query(Long.class).single();
    }
}
