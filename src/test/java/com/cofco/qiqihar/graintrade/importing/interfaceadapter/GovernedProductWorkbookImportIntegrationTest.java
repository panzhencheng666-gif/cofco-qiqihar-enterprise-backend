package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
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
