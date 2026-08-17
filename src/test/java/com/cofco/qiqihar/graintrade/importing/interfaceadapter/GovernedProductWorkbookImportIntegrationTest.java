package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class GovernedProductWorkbookImportIntegrationTest {
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.business_import_draft_evidence,platform.import_row_result,
                  platform.business_import_draft,platform.import_job_photo,platform.import_job,
                  platform.business_audit_event,production.production_record,market.market_record,
                  logistics.route_event,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void importsSparseRowsFromAllNineTemplateFamiliesAsIndependentDatabaseDrafts() throws Exception {
        importTwoRows("production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", "地区", "prod-draft-1");
        importTwoRows("market", "MARKET", "市场", "market-tester",
                "样本点名称", "地区", "market-draft-1");
        importTwoRows("logistics", "LOGISTICS", "物流", "logistics-tester",
                "物流样本点名称", "地区", "logistics-draft-1");

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft WHERE state_code='DRAFT'")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE outcome_code='IMPORTED'")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE warning_code IS NOT NULL")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE sample_name<>'' AND region_code='230208'
                  AND jsonb_exists(values_json,'surveyYear') = false
                """).query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event")
                .query(Long.class).single()).isZero();
    }

    @Test
    void importsRailwayAndRoadAsTheTwoGovernedLogisticsTransportModes() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/logistics/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "LOGISTICS");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "LOGISTICS", "物流", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> rail = sparse(labels, "物流样本点名称", "地区", "铁路运输样本", "");
        List<String> road = sparse(labels, "物流样本点名称", "地区", "公路运输样本", "");
        List<String> invalid = sparse(labels, "物流样本点名称", "地区", "无效运输样本", "");
        rail = withValue(rail, labels, "运输方式", "铁路");
        road = withValue(road, labels, "运输方式", "公路");
        invalid = withValue(invalid, labels, "运输方式", "航空");

        mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "物流-玉米-批量导入模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(template, List.of(rail, road, invalid))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-transport-modes")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        assertThat(jdbc.sql("""
                SELECT values_json->>'LOG_TRANSPORT_MODE' FROM platform.business_import_draft
                ORDER BY sample_name
                """).query(String.class).list()).containsExactlyInAnyOrder("RAIL", "ROAD");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result
                WHERE row_data::text LIKE '%铁路%' OR row_data::text LIKE '%公路%'
                """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result
                WHERE outcome_code='ERROR' AND error_message LIKE '%运输方式%'
                """).query(Long.class).single()).isOne();
    }

    @Test
    void publicProductWorkbooksRemainCompatibleWithOlderClientsThatSendAnObjectTypeParameter()
            throws Exception {
        importOnePublicWorkbookWithLegacyObjectTypeParameter(
                "production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", "旧客户端产情样本", "FARMER");
        importOnePublicWorkbookWithLegacyObjectTypeParameter(
                "market", "MARKET", "市场", "market-tester",
                "样本点名称", "旧客户端市场样本", "TRADER");

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void incompleteImportedRowRemainsADatabaseDraftAndCreatesNoFormalRecord() throws Exception {
        importTwoRows("production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", "地区", "incomplete-production");
        String draftId = jdbc.sql("""
                SELECT import_draft_id::text FROM platform.business_import_draft
                WHERE domain_code='PRODUCTION' ORDER BY source_row_number LIMIT 1
                """).query(String.class).single();
        String importJobId = jdbc.sql("""
                SELECT import_job_id::text FROM platform.business_import_draft WHERE import_draft_id=:id
                """).param("id", java.util.UUID.fromString(draftId)).query(String.class).single();

        mvc.perform(get("/api/v1/import-drafts").param("importJobId", importJobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].stateCode").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].sampleName").value("产情样本一"));

        mvc.perform(post("/api/v1/import-drafts/{id}/submit", draftId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_DRAFT_INCOMPLETE"));

        assertThat(jdbc.sql("""
                SELECT state_code FROM platform.business_import_draft WHERE import_draft_id=:id
                """).param("id", java.util.UUID.fromString(draftId)).query(String.class).single())
                .isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void submitsCompleteWorkbookRowsAndIndependentApprovalPublishesAllThreeDomains() throws Exception {
        String productionRecord = importSubmitAndApprove("production", "PRODUCTION", "产情",
                "production-tester", "market-tester", "production-records", "正式产情样本",
                "样本点名称", Map.ofEntries(
                        Map.entry("样本点类型", "农户"), Map.entry("数据年份", "2026"),
                        Map.entry("填报人联系方式", "13800000000"),
                        Map.entry("样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.354300"), Map.entry("经度（度）", "123.918200"),
                        Map.entry("播种面积（亩）", "100"),
                        Map.entry("预计单产（公斤/亩）", "500")));
        String marketRecord = importSubmitAndApprove("market", "MARKET", "市场",
                "market-tester", "production-tester", "market-records", "正式市场样本",
                "样本点名称", Map.ofEntries(
                        Map.entry("对象类型", "贸易商"), Map.entry("数据年份", "2026"),
                        Map.entry("数据月份", "8"),
                        Map.entry("填报人联系方式", "13800000000"),
                        Map.entry("样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.354300"), Map.entry("经度（度）", "123.918200"),
                        Map.entry("采集对象收购价格（元/吨）", "2300"),
                        Map.entry("采集对象销售价格（元/吨）", "2380"),
                        Map.entry("车板组成（元/吨）", "36"),
                        Map.entry("包装形态", "散粮"), Map.entry("运费组成（元/吨）", "72")));
        String logisticsRecord = importSubmitAndApprove("logistics", "LOGISTICS", "物流",
                "logistics-tester", "production-tester", "logistics-records", "正式物流样本",
                "物流样本点名称", Map.ofEntries(
                        Map.entry("数据年份", "2026"), Map.entry("数据月份", "8"),
                        Map.entry("填报人联系方式", "13800000000"),
                        Map.entry("物流样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.354300"), Map.entry("经度（度）", "123.918200"),
                        Map.entry("运输方式", "铁路"), Map.entry("运输方向", "流入"),
                        Map.entry("运输数量（吨）", "12.5000"),
                        Map.entry("物流运价（不含车板价）（元/吨）", "80.2500"),
                        Map.entry("车板价（元/吨）", "2650.0000")));

        assertThat(jdbc.sql("SELECT status_code FROM production.production_record WHERE record_id=:id")
                .param("id", productionRecord).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT status_code FROM market.market_record WHERE record_id=:id")
                .param("id", marketRecord).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT status_code FROM logistics.route_event WHERE event_id::text=:id")
                .param("id", logisticsRecord).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE (aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:production)
                   OR (aggregate_type='MARKET_RECORD' AND aggregate_id=:market)
                   OR (aggregate_type='LOGISTICS_RECORD' AND aggregate_id=:logistics)
                """).param("production", productionRecord).param("market", marketRecord)
                .param("logistics", logisticsRecord).query(Long.class).single()).isGreaterThanOrEqualTo(6);

        mvc.perform(get("/api/v1/observable-analysis/snapshots")
                        .param("productCode", "CORN").param("regionCode", "230208")
                        .param("surveyYear", "2026").principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.recordCount").value(3))
                .andExpect(jsonPath("$.data.production.metrics[?(@.code == 'EXPECTED_OUTPUT')].value")
                        .value(org.hamcrest.Matchers.hasItem("50.0000")))
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2300.0000")))
                .andExpect(jsonPath("$.data.logistics.metrics[?(@.code == 'INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("12.5000")))
                .andExpect(jsonPath("$.data.supply.calculation.expectedOutputTonnes").value("50.0000"))
                .andExpect(jsonPath("$.data.supply.calculation.inflowTonnes").value("12.5000"));

        mvc.perform(get("/api/v1/overview/dashboard")
                        .param("productCode", "CORN").param("regionCode", "230208")
                        .param("year", "2026").principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("100")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2300")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("12.5")));
    }

    private String importSubmitAndApprove(String route, String domainCode, String domainLabel,
            String operator, String reviewer, String canonicalRoute, String sampleName,
            String sampleLabel, Map<String, String> supplied) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", "CORN").principal(() -> operator))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, sampleLabel, "地区", sampleName, "");
        for (Map.Entry<String, String> value : supplied.entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", domainLabel + "-玉米-批量导入模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(template, List.of(row))))
                        .param("productCode", "CORN").header("Idempotency-Key", "formal-" + route)
                        .principal(() -> operator))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.importedRows").value(1));
        String draftId = jdbc.sql("""
                SELECT import_draft_id::text FROM platform.business_import_draft
                WHERE domain_code=:domain AND sample_name=:sample
                """).param("domain", domainCode).param("sample", sampleName).query(String.class).single();
        mvc.perform(post("/api/v1/import-drafts/{id}/submit", draftId).principal(() -> operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateCode").value("PROMOTED"))
                .andExpect(jsonPath("$.data.canonicalRecordId").isNotEmpty());
        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft WHERE import_draft_id=:id
                """).param("id", java.util.UUID.fromString(draftId)).query(String.class).single();
        mvc.perform(post("/api/v1/" + canonicalRoute + "/{id}/approve", recordId)
                        .principal(() -> reviewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        return recordId;
    }

    private void importTwoRows(String route, String domainCode, String domainLabel, String principal,
            String sampleLabel, String regionLabel, String key) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", "RICE")
                        .principal(() -> principal))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, "RICE", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> first = sparse(labels, sampleLabel, regionLabel, domainLabel + "样本一", "");
        List<String> second = sparse(labels, sampleLabel, regionLabel, domainLabel + "样本二", "不可用照片.jpg");
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(first, second));
        MockMultipartFile file = new MockMultipartFile("file", domainLabel + "-稻谷-批量导入模板.xlsx",
                XLSX, workbook);
        MockMultipartFile invalidPhoto = new MockMultipartFile(
                "photos", "不可用照片.jpg", "image/jpeg", "不是照片".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(file).file(invalidPhoto).param("productCode", "RICE")
                        .header("Idempotency-Key", key).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.failedRows").value(0))
                .andExpect(jsonPath("$.data.warningRows").value(1));

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(file).file(invalidPhoto).param("productCode", "RICE")
                        .header("Idempotency-Key", key).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft WHERE domain_code=:domain
                """).param("domain", domainCode).query(Long.class).single()).isEqualTo(2);
    }

    private void importOnePublicWorkbookWithLegacyObjectTypeParameter(
            String route, String domainCode, String domainLabel, String principal,
            String sampleLabel, String sampleName, String objectTypeCode) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .param("objectTypeCode", objectTypeCode).principal(() -> principal))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, sampleLabel, "地区", sampleName, "");

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", domainLabel + "-玉米-批量导入模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(template, List.of(row))))
                        .param("productCode", "CORN").param("objectTypeCode", objectTypeCode)
                        .header("Idempotency-Key", "legacy-client-" + route).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));
    }

    private static List<String> sparse(List<String> labels, String sampleLabel, String regionLabel,
            String sampleName, String photoName) {
        ArrayList<String> row = new ArrayList<>(Collections.nCopies(labels.size(), ""));
        row.set(labels.indexOf(sampleLabel), sampleName);
        row.set(labels.indexOf(regionLabel), "230208");
        row.set(labels.indexOf(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL), photoName);
        return List.copyOf(row);
    }

    private static List<String> withValue(List<String> source, List<String> labels,
            String label, String value) {
        ArrayList<String> row = new ArrayList<>(source);
        row.set(labels.indexOf(label), value);
        return List.copyOf(row);
    }

    private static List<String> withoutTrailingBlanks(List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return List.copyOf(values.subList(0, size));
    }
}
