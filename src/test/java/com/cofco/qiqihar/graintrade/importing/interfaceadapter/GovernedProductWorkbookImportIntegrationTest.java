package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.BusinessPeriodRecordGuard;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportTemplateCatalog;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.JdbcBusinessPeriodRecordGuard;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(GovernedProductWorkbookImportIntegrationTest.PeriodGuardTestConfiguration.class)
class GovernedProductWorkbookImportIntegrationTest {
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired CoordinatingBusinessPeriodRecordGuard coordinatingPeriodGuard;
    @Autowired BusinessImportTemplateCatalog templateCatalog;
    @Autowired MarketImportPort market;
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
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230208',ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)),
                  '受控验收边界','urn:qiqihar:acceptance-boundary','acceptance-1','内部验收数据',
                  '230208',DATE '2026-08-18',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2,47.9,4326)))),'hex'))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230207',ST_Multi(ST_MakeEnvelope(122.0,45.0,122.5,45.5,4326)),
                  '受控验收边界','urn:qiqihar:acceptance-boundary','acceptance-1','内部验收数据',
                  '230207',DATE '2026-08-18',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(122.0,45.0,122.5,45.5,4326)))),'hex'))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();
    }

    @AfterEach
    void cleanCrossPeriodSampleIdentityFixture() {
        jdbc.sql("""
                DELETE FROM production.production_record
                WHERE sample_point_id IN (
                  SELECT sample_point_id FROM registry.sample_point
                  WHERE canonical_name LIKE '期间守卫%'
                     OR sample_point_id::text LIKE '95200000-%'
                     OR sample_point_id::text LIKE '95300000-%')
                """).update();
        jdbc.sql("""
                DELETE FROM market.market_record
                WHERE sample_point_id IN (
                  SELECT sample_point_id FROM registry.sample_point
                  WHERE canonical_name LIKE '期间守卫%'
                     OR sample_point_id::text LIKE '95200000-%'
                     OR sample_point_id::text LIKE '95300000-%')
                """).update();
        jdbc.sql("""
                DELETE FROM logistics.route_event
                WHERE sample_point_id IN (
                  SELECT sample_point_id FROM registry.sample_point
                  WHERE canonical_name LIKE '期间守卫%'
                     OR sample_point_id::text LIKE '95200000-%'
                     OR sample_point_id::text LIKE '95300000-%')
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE canonical_name LIKE '期间守卫%'
                   OR sample_point_id::text LIKE '95200000-%'
                   OR sample_point_id::text LIKE '95300000-%'
                """).update();
    }

    @Test
    void rejectsSparseRowsFromAllThreeDomainTemplatesWithoutLeavingDatabaseDrafts() throws Exception {
        importTwoRows("production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", "地区", "prod-draft-1");
        importTwoRows("market", "MARKET", "市场", "market-tester",
                "样本点名称", "地区", "market-draft-1");
        importTwoRows("logistics", "LOGISTICS", "物流", "logistics-tester",
                "物流样本点名称", "地区", "logistics-draft-1");

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE outcome_code='ERROR'")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event")
                .query(Long.class).single()).isZero();
    }

    @Test
    void validatesDeclaredRegionAgainstCoordinatesOnceAtImportForAllThreeDomains() throws Exception {
        Map<String, String> outsideProduction = new LinkedHashMap<>(completeProductionValues());
        outsideProduction.put("纬度（度）", "47.000000");
        outsideProduction.put("经度（度）", "123.000000");
        Map<String, String> outsideMarket = new LinkedHashMap<>(completeMarketValues());
        outsideMarket.put("纬度（度）", "47.000000");
        outsideMarket.put("经度（度）", "123.000000");
        Map<String, String> outsideLogistics = new LinkedHashMap<>(completeLogisticsValues());
        outsideLogistics.put("纬度（度）", "47.000000");
        outsideLogistics.put("经度（度）", "123.000000");

        importWorkbook("production", "production-tester", "production-coordinate-region-mismatch",
                workbook(workbookFixture("production", "PRODUCTION", "产情",
                        "production-tester", "样本点名称"), "越界产情样本", outsideProduction), 0, 1);
        importWorkbook("market", "market-tester", "market-coordinate-region-mismatch",
                workbook(workbookFixture("market", "MARKET", "市场",
                        "market-tester", "样本点名称"), "越界市场样本", outsideMarket), 0, 1);
        importWorkbook("logistics", "logistics-tester", "logistics-coordinate-region-mismatch",
                workbook(workbookFixture("logistics", "LOGISTICS", "物流",
                        "logistics-tester", "物流样本点名称"), "越界物流样本", outsideLogistics), 0, 1);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key LIKE '%-coordinate-region-mismatch'
                  AND result.error_code='SAMPLE_COORDINATE_REGION_MISMATCH'
                """).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM production.production_record)
                     + (SELECT count(*) FROM market.market_record)
                     + (SELECT count(*) FROM logistics.route_event)
                """).query(Long.class).single()).isZero();
    }

    @Test
    void validatesTheSubmittedCoordinateBeforeStorageRoundingForAllThreeDomains() throws Exception {
        Map<String, String> production = new LinkedHashMap<>(completeProductionValues());
        production.put("纬度（度）", "47.55000012345");
        production.put("经度（度）", "124.2000004");
        Map<String, String> market = new LinkedHashMap<>(completeMarketValues());
        market.put("纬度（度）", "47.55000012345");
        market.put("经度（度）", "124.2000004");
        Map<String, String> logistics = new LinkedHashMap<>(completeLogisticsValues());
        logistics.put("纬度（度）", "47.55000012345");
        logistics.put("经度（度）", "124.2000004");

        importWorkbook("production", "production-tester", "production-rounding-boundary-mismatch",
                workbook(workbookFixture("production", "PRODUCTION", "产情",
                        "production-tester", "样本点名称"), "边界外产情样本", production), 0, 1);
        importWorkbook("market", "market-tester", "market-rounding-boundary-mismatch",
                workbook(workbookFixture("market", "MARKET", "市场",
                        "market-tester", "样本点名称"), "边界外市场样本", market), 0, 1);
        importWorkbook("logistics", "logistics-tester", "logistics-rounding-boundary-mismatch",
                workbook(workbookFixture("logistics", "LOGISTICS", "物流",
                        "logistics-tester", "物流样本点名称"), "边界外物流样本", logistics), 0, 1);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key LIKE '%-rounding-boundary-mismatch'
                  AND result.error_code='SAMPLE_COORDINATE_REGION_MISMATCH'
                """).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM production.production_record)
                     + (SELECT count(*) FROM market.market_record)
                     + (SELECT count(*) FROM logistics.route_event)
                """).query(Long.class).single()).isZero();
    }

    @Test
    void acceptsHighPrecisionCoordinatesByNormalizingToEachGovernedStorageResolution()
            throws Exception {
        Map<String, String> production = new LinkedHashMap<>(completeProductionValues());
        production.put("纬度（度）", "47.55000012345");
        production.put("经度（度）", "１２３．８０００００１２３４５");
        Map<String, String> market = new LinkedHashMap<>(completeMarketValues());
        market.put("纬度（度）", "47.55000012345");
        market.put("经度（度）", "123\u00a0.80000012345");
        Map<String, String> logistics = new LinkedHashMap<>(completeLogisticsValues());
        logistics.put("纬度（度）", "47.55000012345");
        logistics.put("经度（度）", "123.80000012345");

        importWorkbook("production", "production-tester", "production-high-precision-coordinate",
                workbook(workbookFixture("production", "PRODUCTION", "产情",
                        "production-tester", "样本点名称"), "高精度产情样本", production), 1, 0);
        importWorkbook("market", "market-tester", "market-high-precision-coordinate",
                workbook(workbookFixture("market", "MARKET", "市场",
                        "market-tester", "样本点名称"), "高精度市场样本", market), 1, 0);
        importWorkbook("logistics", "logistics-tester", "logistics-high-precision-coordinate",
                workbook(workbookFixture("logistics", "LOGISTICS", "物流",
                        "logistics-tester", "物流样本点名称"), "高精度物流样本", logistics), 1, 0);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE sample_name LIKE '高精度%样本' AND state_code='PROMOTED'
                """).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void rejectsWhenStorageRoundingWouldMoveTheFormalCoordinateOutsideTheRegion() throws Exception {
        jdbc.sql("""
                UPDATE overview.administrative_boundary
                SET geometry=ST_Multi(ST_MakeEnvelope(123.5,47.4,124.2000007,47.9,4326))
                WHERE region_code='230208'
                """).update();
        Map<String, String> production = new LinkedHashMap<>(completeProductionValues());
        production.put("纬度（度）", "47.55000012345");
        production.put("经度（度）", "124.2000006");

        importWorkbook("production", "production-tester", "production-rounded-coordinate-outside",
                workbook(workbookFixture("production", "PRODUCTION", "产情",
                        "production-tester", "样本点名称"), "舍入后越界产情样本", production), 0, 1);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key='production-rounded-coordinate-outside'
                  AND result.error_code='SAMPLE_COORDINATE_REGION_MISMATCH'
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
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
        for (Map.Entry<String, String> value : completeLogisticsValues().entrySet()) {
            if (!"运输方式".equals(value.getKey())) {
                rail = withValue(rail, labels, value.getKey(), value.getValue());
                road = withValue(road, labels, value.getKey(), value.getValue());
                invalid = withValue(invalid, labels, value.getKey(), value.getValue());
            }
        }
        rail = withValue(rail, labels, "运输方式", "铁路");
        road = withValue(road, labels, "运输方式", "公路");
        invalid = withValue(invalid, labels, "运输方式", "航空");

        mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "物流-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(rail, road))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-transport-modes")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "物流-玉米-无效运输方式.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(invalid))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-invalid-transport-mode")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
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

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft WHERE state_code='PROMOTED'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record WHERE status_code='PENDING_REVIEW'")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record WHERE status_code='PENDING_REVIEW'")
                .query(Long.class).single()).isOne();
    }

    @Test
    void previouslyDownloadedProductTemplatesRemainUsableAfterInputRulesBroaden() throws Exception {
        byte[] productionDownloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> productionLabels = withoutTrailingBlanks(
                XlsxTable.parseWorksheet(productionDownloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Template priorProduction = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, "2026.08.17-2",
                "sha256:6694ba15e979c57c01abf1151711f998e0e2e3826ec781af3b9cd61f18ff2544",
                productionLabels, productionLabels, List.of());
        List<String> productionRow = sparse(
                productionLabels, "样本点名称", "地区", "旧模板产情样本", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            productionRow = withValue(productionRow, productionLabels, value.getKey(), value.getValue());
        }
        productionRow = withValue(productionRow, productionLabels, "毒素（%）", "0.001");
        productionRow = withValue(productionRow, productionLabels, "地租（元/亩）", "733.33");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "旧版产情模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(priorProduction, List.of(productionRow))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "prior-production-template")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        var marketObjectTypes = templateCatalog.objectTypes("MARKET", "CORN");
        var marketDefinitions = marketObjectTypes.stream()
                .map(option -> market.definition("CORN", option.code())).toList();
        BusinessImportWorkbook.Template currentMarket = MarketImportTemplate.productWorkbook(
                "CORN", marketDefinitions, marketObjectTypes);
        List<String> marketLabels = currentMarket.labels();
        BusinessImportWorkbook.Template priorMarket = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null, "2026.08.17-2",
                "sha256:5fc9a7e9a33f66ca596e021c232d6f74da2dbd812e3e60bbb5f0a296f853ef70",
                currentMarket.headers(), marketLabels, currentMarket.rules());
        List<String> marketRow = sparse(marketLabels, "样本点名称", "地区", "旧模板市场样本", "");
        for (Map.Entry<String, String> value : completeMarketValues().entrySet()) {
            marketRow = withValue(marketRow, marketLabels, value.getKey(), value.getValue());
        }

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "旧版市场模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(priorMarket, List.of(marketRow))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "prior-market-template")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));
    }

    @Test
    void normalizesCommonMarketPackagingLabelsBeforeSubmittingRowsForReview() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "MARKET");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<List<String>> rows = new ArrayList<>();
        for (String packaging : List.of("散装", "吨包", "编织袋")) {
            List<String> row = sparse(labels, "样本点名称", "地区", packaging + "市场样本", "");
            for (Map.Entry<String, String> value : completeMarketValues().entrySet()) {
                if (!"包装形态".equals(value.getKey())) {
                    row = withValue(row, labels, value.getKey(), value.getValue());
                }
            }
            rows.add(withValue(row, labels, "包装形态", packaging));
        }

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, rows)))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "market-packaging-aliases")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(3))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("SELECT packaging_form FROM market.market_record ORDER BY packaging_form")
                .query(String.class).list()).containsExactly("BAGGED", "BAGGED", "BULK");
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record WHERE status_code='PENDING_REVIEW'")
                .query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void roundsProductionDecimalsToTheExactAllowedPrecision() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, "样本点名称", "地区", "小数位错误提示样本", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        row = withValue(row, labels, "杂质（%）", "1.23456");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-错误提示.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-precision-error-message")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("""
                SELECT value FROM production.production_record_quality WHERE quality_code='IMPURITY'
                """).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1.2346");
    }

    @Test
    void acceptsHumanEnteredBusinessDecimalsUpToTheFourDecimalStoragePrecision() throws Exception {
        byte[] productionWorkbook = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> productionLabels = withoutTrailingBlanks(
                XlsxTable.parseWorksheet(productionWorkbook, 1, 256).getFirst());
        BusinessImportWorkbook.Context productionContext =
                BusinessImportWorkbook.context(productionWorkbook, "PRODUCTION");
        BusinessImportWorkbook.Template productionTemplate = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null,
                productionContext.contractVersion(), productionContext.contractDigest(),
                productionLabels, productionLabels, List.of());
        List<String> productionRow = sparse(
                productionLabels, "样本点名称", "地区", "四位小数产情样本", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            productionRow = withValue(productionRow, productionLabels, value.getKey(), value.getValue());
        }
        productionRow = withValue(productionRow, productionLabels, "毒素（%）", "0.001");
        productionRow = withValue(productionRow, productionLabels, "杂质（%）", "1.2345");
        productionRow = withValue(productionRow, productionLabels, "地租（元/亩）", "１，２３４．５６７");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-业务小数.xlsx", XLSX,
                                BusinessImportWorkbook.create(productionTemplate, List.of(productionRow))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-human-decimals")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        byte[] marketWorkbook = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> marketLabels = withoutTrailingBlanks(
                XlsxTable.parseWorksheet(marketWorkbook, 1, 256).getFirst());
        BusinessImportWorkbook.Context marketContext = BusinessImportWorkbook.context(marketWorkbook, "MARKET");
        BusinessImportWorkbook.Template marketTemplate = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null, marketContext.contractVersion(), marketContext.contractDigest(),
                marketLabels, marketLabels, List.of());
        List<String> marketRow = sparse(marketLabels, "样本点名称", "地区", "四位小数市场样本", "");
        for (Map.Entry<String, String> value : completeMarketValues().entrySet()) {
            marketRow = withValue(marketRow, marketLabels, value.getKey(), value.getValue());
        }
        marketRow = withValue(marketRow, marketLabels, "采集对象收购价格（元/吨）", "2，300．5");
        marketRow = withValue(marketRow, marketLabels, "水分（%）", "14.6789");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-玉米-业务小数.xlsx", XLSX,
                                createCurrentWorkbook(marketTemplate, List.of(marketRow))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "market-human-decimals")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("""
                SELECT value::text FROM production.production_record_quality
                WHERE quality_code='IMPURITY'
                """).query(String.class).single()).isEqualTo("1.2345");
        assertThat(jdbc.sql("""
                SELECT value::text FROM production.production_record_cost
                WHERE cost_code='LAND_RENT'
                """).query(String.class).single()).isEqualTo("1234.5670");
        assertThat(jdbc.sql("""
                SELECT purchase_base_price::text FROM market.market_record
                """).query(String.class).single()).isEqualTo("2300.5000");
        assertThat(jdbc.sql("""
                SELECT value::text FROM market.market_record_fact WHERE fact_code='MOISTURE'
                """).query(String.class).single()).isEqualTo("14.6789");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record WHERE status_code='PENDING_REVIEW'
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record WHERE status_code='PENDING_REVIEW'
                """).query(Long.class).single()).isOne();
    }

    @Test
    void explainsAnOutOfScopeRegionWithoutHidingItAsARowWriteFailure() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('GOVERNED_IMPORT_LIMITED','批量填报受限测试单位',9902)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('GOVERNED_IMPORT_LIMITED','230200') ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('governed-limited-importer','批量填报受限测试员','GOVERNED_IMPORT_LIMITED')
                ON CONFLICT(subject_id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES('governed-limited-importer','BUSINESS_OPERATOR') ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('governed-limited-importer','230200') ON CONFLICT DO NOTHING
                """).update();

        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "governed-limited-importer"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, "样本点名称", "地区", "越界地区提示样本", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        row = withValue(row, labels, "地区", "231100");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-越界提示.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-out-of-scope-message")
                        .principal(() -> "governed-limited-importer"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        Map<String, Object> error = jdbc.sql("""
                SELECT result.error_code,result.error_message FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key='production-out-of-scope-message'
                """).query().singleRow();
        assertThat(error.get("error_code")).isEqualTo("ACCESS_REGION_DENIED");
        assertThat(error.get("error_message").toString()).contains("地区", "权限范围");
    }

    @Test
    void incompleteImportedRowsCreateOnlyActionableErrorsAndNoBusinessRecords() throws Exception {
        importTwoRows("production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", "地区", "incomplete-production");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE outcome_code='ERROR'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void automaticallySubmitsCompleteWorkbookRowsForReview() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> first = sparse(labels, "样本点名称", "地区", "批量产情样本一", "");
        List<String> second = sparse(labels, "样本点名称", "地区", "批量产情样本二", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            first = withValue(first, labels, value.getKey(), value.getValue());
            second = withValue(second, labels, value.getKey(), value.getValue());
        }
        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(first, second))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "persistent-corn-drafts")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2));
        String importJobId = jdbc.sql("""
                SELECT import_job_id::text FROM platform.business_import_draft
                WHERE created_by='production-tester' AND product_code='CORN' LIMIT 1
                """).query(String.class).single();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE import_job_id=:job AND state_code='PROMOTED'
                """).param("job", java.util.UUID.fromString(importJobId)).query(Long.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE product_code='CORN' AND status_code='PENDING_REVIEW'
                """).query(Long.class).single()).isEqualTo(2);

        mvc.perform(get("/api/v1/import-drafts")
                        .param("domainCode", "PRODUCTION")
                        .param("productCode", "CORN")
                        .param("stateCode", "DRAFT")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rejectsOneStableIdentityAtANewPeriodLocationWithoutCreatingADraftOrFormalRecord() throws Exception {
        String point = "95200000-0000-0000-0000-000000000001";
        String record = "95200000-0000-0000-0000-000000000101";
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(CAST(:point AS uuid),'SURVEY_SITE','待核验重名样本','230208','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.600000,47.500000),4326),DATE '2024-01-01',0,
                  'production-tester','production-tester')
                """).param("point", point).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,sample_point_id,last_modified_by)
                VALUES(:record,'CORN','FARMER','230208',DATE '2024-08-01',now(),
                  10,20,'APPROVED',CAST(:point AS uuid),'production-tester')
                """).param("record", record).param("point", point).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_NAME','待核验重名样本'),
                      (:record,'PROD_SAMPLE_CONTACT','13900000000')
                """).param("record", record).update();

        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, "样本点名称", "地区", "待核验重名样本", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        row = withValue(row, labels, "经度（度）", "123.700000");
        row = withValue(row, labels, "纬度（度）", "47.600000");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-身份待核验.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-identity-review-required")
                .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andExpect(jsonPath("$.data.warningRows").value(0));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft
                WHERE sample_name='待核验重名样本' AND state_code='DRAFT'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE status_code='PENDING_REVIEW' AND record_id<>:record
                """).param("record", record).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsATrulyAmbiguousIdentityWithoutCreatingADraftOrFormalRecord() throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES
                  ('95300000-0000-0000-0000-000000000001','SURVEY_SITE','真正歧义样本','230208',
                   'APPROVED','VALID',ST_SetSRID(ST_MakePoint(123.600000,47.500000),4326),
                   DATE '2024-01-01',0,'production-tester','production-tester'),
                  ('95300000-0000-0000-0000-000000000002','SURVEY_SITE','真正歧义样本','230208',
                   'APPROVED','VALID',ST_SetSRID(ST_MakePoint(123.650000,47.550000),4326),
                   DATE '2024-01-01',0,'production-tester','production-tester')
                """).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,sample_point_id,last_modified_by)
                VALUES
                  ('95300000-0000-0000-0000-000000000101','CORN','FARMER','230208',DATE '2024-07-01',
                   now(),10,20,'APPROVED','95300000-0000-0000-0000-000000000001','production-tester'),
                  ('95300000-0000-0000-0000-000000000102','CORN','FARMER','230208',DATE '2024-08-01',
                   now(),10,20,'APPROVED','95300000-0000-0000-0000-000000000002','production-tester')
                """).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES
                  ('95300000-0000-0000-0000-000000000101','PROD_SAMPLE_NAME','真正歧义样本'),
                  ('95300000-0000-0000-0000-000000000101','PROD_SAMPLE_CONTACT','13900000000'),
                  ('95300000-0000-0000-0000-000000000102','PROD_SAMPLE_NAME','真正歧义样本'),
                  ('95300000-0000-0000-0000-000000000102','PROD_SAMPLE_CONTACT','13900000000')
                """).update();

        WorkbookFixture fixture = workbookFixture("production", "PRODUCTION", "产情",
                "production-tester", "样本点名称");
        importWorkbook("production", "production-tester", "production-truly-ambiguous-identity",
                workbook(fixture, "真正歧义样本", completeProductionValues()), 0, 1);

        assertThat(jdbc.sql("""
                SELECT error_code FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key='production-truly-ambiguous-identity'
                """).query(String.class).single()).isEqualTo("SAMPLE_IDENTITY_MULTIPLE_MATCHES");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft WHERE sample_name='真正歧义样本'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record record
                JOIN production.production_record_submission_metadata metadata
                  ON metadata.record_id=record.record_id
                WHERE metadata.field_code='PROD_SAMPLE_NAME' AND metadata.value='真正歧义样本'
                  AND record.status_code='PENDING_REVIEW'
                """).query(Long.class).single()).isZero();
    }

    @Test
    void rollsBackEveryGovernedWorkbookWriteWhenOneRowIsAnIdenticalBatchDuplicate() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, "样本点名称", "地区", "批内重复身份样本", "原子批次现场.png");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-批内重复.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row, row))))
                        .file(new MockMultipartFile("photos", "原子批次现场.png", "image/png", pngBytes()))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-batch-local-duplicate")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft_evidence")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_DRAFT'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result
                WHERE error_code='NOT_IMPORTED_ATOMIC_BATCH'
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT error_code FROM platform.import_row_result
                WHERE error_code='IMPORT_DUPLICATE_ROW'
                """).query(String.class).single()).isEqualTo("IMPORT_DUPLICATE_ROW");

        String originalJobId = jdbc.sql("""
                SELECT import_job_id::text FROM platform.import_job
                WHERE idempotency_key='production-batch-local-duplicate'
                """).query(String.class).single();
        mvc.perform(post("/api/v1/imports/production/{id}/retries", originalJobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.retryOf").value(originalJobId))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void rejectsOneIdentityAndPeriodSplitAcrossTwoDeclaredRegionsInTheSameWorkbook() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> first = sparse(labels, "样本点名称", "地区", "批内跨地区身份样本", "梅里斯达斡尔族区");
        List<String> second = sparse(labels, "样本点名称", "地区", "批内跨地区身份样本", "碾子山区");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            first = withValue(first, labels, value.getKey(), value.getValue());
            second = withValue(second, labels, value.getKey(), value.getValue());
        }
        Map<String, Object> secondPoint = jdbc.sql("""
                SELECT ST_X(ST_PointOnSurface(geometry)) longitude,
                       ST_Y(ST_PointOnSurface(geometry)) latitude
                FROM overview.administrative_boundary WHERE region_code='230207'
                """).query().singleRow();
        second = withValue(second, labels, "经度（度）", secondPoint.get("longitude").toString());
        second = withValue(second, labels, "纬度（度）", secondPoint.get("latitude").toString());

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-批内跨地区身份.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(first, second))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-batch-cross-region-identity")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        assertThat(jdbc.sql("""
                SELECT error_code FROM platform.import_row_result
                WHERE error_code IN ('IMPORT_DUPLICATE_ROW','SAMPLE_IDENTITY_RECORD_CONFLICT')
                """).query(String.class).single())
                .isIn("IMPORT_DUPLICATE_ROW", "SAMPLE_IDENTITY_RECORD_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void preservesOneProductionSampleIdentityAcrossMonthsAndRejectsAGlobalSamePeriodDuplicate()
            throws Exception {
        WorkbookFixture fixture = workbookFixture("production", "PRODUCTION", "产情",
                "production-tester", "样本点名称");
        Map<String, String> julyValues = new LinkedHashMap<>(completeProductionValues());
        julyValues.put("数据月份", "7");
        Map<String, String> augustValues = new LinkedHashMap<>(completeProductionValues());
        augustValues.put("数据月份", "8");
        String sampleName = "期间守卫产情样本";
        byte[] julyWorkbook = workbook(fixture, sampleName, julyValues);
        byte[] augustWorkbook = workbook(fixture, sampleName, augustValues);

        importWorkbook("production", "production-tester", "production-period-2026-07", julyWorkbook, 1, 0);
        approveCanonicalRecord("production.production_record", "record_id", 7,
                "/api/v1/production-records/{id}/approve", "market-tester");
        importWorkbook("production", "production-tester", "production-period-2026-08", augustWorkbook, 1, 0);
        approveCanonicalRecord("production.production_record", "record_id", 8,
                "/api/v1/production-records/{id}/approve", "market-tester");
        String augustRecordId = jdbc.sql("""
                SELECT record_id FROM production.production_record WHERE survey_month=8
                """).query(String.class).single();

        assertThat(jdbc.sql("SELECT count(DISTINCT sample_point_id) FROM production.production_record")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(DISTINCT survey_month) FROM production.production_record")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isEqualTo(2);

        assertSameKeyReplayKeepsOriginalJobAndDraft("production", "production-tester",
                "production-period-2026-08", augustWorkbook);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isEqualTo(2);

        Map<String, String> changedAugustValues = new LinkedHashMap<>(augustValues);
        changedAugustValues.put("预计单产（公斤/亩）", "510");
        byte[] changedAugustWorkbook = workbook(fixture, sampleName, changedAugustValues);
        importWorkbook("production", "market-tester", "production-period-2026-08-correction",
                changedAugustWorkbook, 0, 1);

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isEqualTo(2);
        Map<String, Object> conflict = jdbc.sql("""
                SELECT error_code,error_message FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key='production-period-2026-08-correction'
                """).query().singleRow();
        assertThat(conflict.get("error_code")).isEqualTo("SAMPLE_PERIOD_RECORD_CONFLICT");
        assertThat(conflict.get("error_message").toString())
                .contains(augustRecordId, "退回补充", "修正流程");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft WHERE sample_name=:sample")
                .param("sample", sampleName).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void preservesOneMarketSampleIdentityAcrossMonthsAndRejectsAGlobalSamePeriodDuplicate()
            throws Exception {
        WorkbookFixture fixture = workbookFixture("market", "MARKET", "市场",
                "market-tester", "样本点名称");
        Map<String, String> julyValues = new LinkedHashMap<>(completeMarketValues());
        julyValues.put("数据月份", "7");
        Map<String, String> augustValues = new LinkedHashMap<>(completeMarketValues());
        augustValues.put("数据月份", "8");
        String sampleName = "期间守卫市场样本";
        byte[] julyWorkbook = workbook(fixture, sampleName, julyValues);
        byte[] augustWorkbook = workbook(fixture, sampleName, augustValues);

        importWorkbook("market", "market-tester", "market-period-2026-07", julyWorkbook, 1, 0);
        approveCanonicalRecord("market.market_record", "record_id", 7,
                "/api/v1/market-records/{id}/approve", "production-tester");
        importWorkbook("market", "market-tester", "market-period-2026-08", augustWorkbook, 1, 0);
        approveCanonicalRecord("market.market_record", "record_id", 8,
                "/api/v1/market-records/{id}/approve", "production-tester");

        assertThat(jdbc.sql("SELECT count(DISTINCT sample_point_id) FROM market.market_record")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(DISTINCT survey_month) FROM market.market_record")
                .query(Long.class).single()).isEqualTo(2);
        assertSameKeyReplayKeepsOriginalJobAndDraft("market", "market-tester",
                "market-period-2026-08", augustWorkbook);

        Map<String, String> changedAugustValues = new LinkedHashMap<>(augustValues);
        changedAugustValues.put("采集对象销售价格（元/吨）", "2390");
        importWorkbook("market", "production-tester", "market-period-2026-08-correction",
                workbook(fixture, sampleName, changedAugustValues), 0, 1);

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single()).isEqualTo(2);
        assertPeriodConflict("market-period-2026-08-correction");
    }

    @Test
    void linksTheApprovedLogisticsSampleToOneStableFormalLogisticsIdentityAcrossMonths()
            throws Exception {
        WorkbookFixture fixture = workbookFixture("logistics", "LOGISTICS", "物流",
                "logistics-tester", "物流样本点名称");
        Map<String, String> julyValues = new LinkedHashMap<>(completeLogisticsValues());
        julyValues.put("数据月份", "7");
        Map<String, String> augustValues = new LinkedHashMap<>(completeLogisticsValues());
        augustValues.put("数据月份", "8");
        String sampleName = "期间守卫物流样本";
        byte[] julyWorkbook = workbook(fixture, sampleName, julyValues);
        byte[] augustWorkbook = workbook(fixture, sampleName, augustValues);

        importWorkbook("logistics", "logistics-tester", "logistics-period-2026-07", julyWorkbook, 1, 0);
        approveCanonicalRecord("logistics.route_event", "event_id", 7,
                "/api/v1/logistics-records/{id}/approve", "production-tester");
        importWorkbook("logistics", "logistics-tester", "logistics-period-2026-08", augustWorkbook, 1, 0);
        approveCanonicalRecord("logistics.route_event", "event_id", 8,
                "/api/v1/logistics-records/{id}/approve", "production-tester");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM logistics.route_event
                WHERE source_organization=:sample AND sample_contact='13900000000'
                """).param("sample", sampleName).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT survey_month) FROM logistics.route_event")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT sample_point_id) FROM logistics.route_event")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_point
                WHERE kind_code='LOGISTICS_NODE' AND approval_state='APPROVED'
                  AND canonical_name=:sample
                """).param("sample", sampleName).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM overview.sample_point_query_source
                WHERE category_code='LOGISTICS' AND source_role='SURVEY'
                  AND canonical_name=:sample
                """).param("sample", sampleName).query(Long.class).single()).isEqualTo(2);
        assertSameKeyReplayKeepsOriginalJobAndDraft("logistics", "logistics-tester",
                "logistics-period-2026-08", augustWorkbook);

        Map<String, String> changedAugustValues = new LinkedHashMap<>(augustValues);
        changedAugustValues.put("物流运价（不含车板价）（元/吨）", "81.2500");
        importWorkbook("logistics", "production-tester", "logistics-period-2026-08-correction",
                workbook(fixture, sampleName, changedAugustValues), 0, 1);

        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event")
                .query(Long.class).single()).isEqualTo(2);
        assertPeriodConflict("logistics-period-2026-08-correction");
    }

    @Test
    void treatsPostgresCompatibilityAndAccentVariantsAsOneSequentialPeriodKey() throws Exception {
        WorkbookFixture fixture = workbookFixture("production", "PRODUCTION", "产情",
                "production-tester", "样本点名称");
        String canonicalName = "期间守卫CaféAi样本";
        String unicodeVariant = "期间守卫CafeＡİ\u2003样本";
        Map<String, String> canonicalValues = new LinkedHashMap<>(completeProductionValues());
        canonicalValues.put("数据月份", "8");
        byte[] canonical = workbook(fixture, canonicalName, canonicalValues);
        importWorkbook("production", "production-tester", "production-unicode-canonical",
                canonical, 1, 0);
        approveCanonicalRecord("production.production_record", "record_id", 8,
                "/api/v1/production-records/{id}/approve", "market-tester");

        Map<String, String> changedVariantValues = new LinkedHashMap<>(canonicalValues);
        changedVariantValues.put("样本点联系方式", "13900000000");
        changedVariantValues.put("预计单产（公斤/亩）", "510");
        importWorkbook("production", "market-tester", "production-unicode-variant",
                workbook(fixture, unicodeVariant, changedVariantValues), 0, 1);

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isOne();
        assertPeriodConflict("production-unicode-variant");
    }

    @Test
    void serializesConcurrentGlobalPeriodImportsFromDifferentOperators() throws Exception {
        WorkbookFixture fixture = workbookFixture("production", "PRODUCTION", "产情",
                "production-tester", "样本点名称");
        String canonicalName = "期间守卫并发Ai样本";
        String unicodeVariant = "期间守卫并发Ａİ\u2003样本";
        byte[] canonical = workbook(fixture, canonicalName, completeProductionValues());
        Map<String, String> variantValues = new LinkedHashMap<>(completeProductionValues());
        variantValues.put("样本点联系方式", "（１３９）\u2003００００-００００");
        byte[] variant = workbook(fixture, unicodeVariant, variantValues);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GuardCompetition competition = coordinatingPeriodGuard.arm();
        Future<MvcResult> first = executor.submit(() -> {
            return performImport("production", "production-tester",
                    "production-period-concurrent-a", canonical);
        });
        Future<MvcResult> second = null;
        try {
            assertThat(competition.firstLockHeld().await(5, TimeUnit.SECONDS)).isTrue();
            second = executor.submit(() -> performImport("production", "market-tester",
                    "production-period-concurrent-b", variant));
            assertThat(competition.secondLockAttempted().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observeAdvisoryLockWait(5, TimeUnit.SECONDS)).isTrue();
            competition.releaseFirst().countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            competition.releaseFirst().countDown();
            coordinatingPeriodGuard.disarm(competition);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record_submission_metadata
                WHERE field_code='PROD_SAMPLE_NAME'
                  AND value IN ('期间守卫并发Ai样本','期间守卫并发Ａİ\u2003样本')
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key IN ('production-period-concurrent-a','production-period-concurrent-b')
                  AND result.outcome_code='ERROR'
                  AND result.error_code='SAMPLE_PERIOD_RECORD_CONFLICT'
                """).query(Long.class).single()).isOne();
    }

    private boolean observeAdvisoryLockWait(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND wait_event_type='Lock'
                      AND wait_event='advisory'
                    """).query(Long.class).single();
            if (waiting > 0) return true;
            Thread.sleep(25);
        }
        return false;
    }

    @Test
    void supplementsMissingPhotosAgainstOriginalImportedRecordsWithoutCreatingBusinessDuplicates() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> imported = sparse(labels, "样本点名称", "地区", "照片补充成功样本", "成功现场.png");
        List<String> rejected = sparse(labels, "样本点名称", "地区", "照片补充失败样本", "失败现场.png");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            imported = withValue(imported, labels, value.getKey(), value.getValue());
            rejected = withValue(rejected, labels, value.getKey(), value.getValue());
        }
        rejected = withValue(rejected, labels, "纬度（度）", "95");

        String importResponse = mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-照片补充.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(imported))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-photo-supplement")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0))
                .andReturn().getResponse().getContentAsString();
        String jobId = importResponse.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        String rejectedResponse = mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-照片补充失败.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(rejected))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-photo-supplement-rejected")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String rejectedJobId = rejectedResponse.replaceFirst(
                "(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        Map<String, Object> before = jdbc.sql("""
                SELECT record_id,version,status_code FROM production.production_record
                """).query().singleRow();

        mvc.perform(get("/api/v1/imports/production/{jobId}/photo-manifest", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFileCount").value(1))
                .andExpect(jsonPath("$.data.eligibleFileCount").value(1))
                .andExpect(jsonPath("$.data.deferredFileCount").value(0))
                .andExpect(jsonPath("$.data.totalTargetAttachments").value(1))
                .andExpect(jsonPath("$.data.attachedTargetAttachments").value(0));

        mvc.perform(multipart("/api/v1/imports/production/{jobId}/photos", jobId)
                        .file(new MockMultipartFile("file", "成功现场.png", "image/png", pngBytes()))
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("ATTACHED"))
                .andExpect(jsonPath("$.data.targetRecords").value(1))
                .andExpect(jsonPath("$.data.newAttachments").value(1))
                .andExpect(jsonPath("$.data.alreadyAttached").value(0));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job_photo")
                .query(Long.class).single()).isZero();
        Map<String, Object> after = jdbc.sql("""
                SELECT record_id,version,status_code FROM production.production_record
                """).query().singleRow();
        assertThat(after).isEqualTo(before);

        mvc.perform(get("/api/v1/production-records/{id}", before.get("record_id"))
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidencePhotos.length()").value(1))
                .andExpect(jsonPath("$.data.evidencePhotos[0].originalFilename").value("成功现场.png"));

        mvc.perform(multipart("/api/v1/imports/production/{jobId}/photos", jobId)
                        .file(new MockMultipartFile("file", "成功现场.png", "image/png", pngBytes()))
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("ALREADY_ATTACHED"))
                .andExpect(jsonPath("$.data.newAttachments").value(0))
                .andExpect(jsonPath("$.data.alreadyAttached").value(1));

        mvc.perform(multipart("/api/v1/imports/production/{jobId}/photos", rejectedJobId)
                        .file(new MockMultipartFile("file", "失败现场.png", "image/png", pngBytes()))
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("DEFERRED_NO_RECORD"))
                .andExpect(jsonPath("$.data.failedRows").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo")
                .query(Long.class).single()).isOne();

        mvc.perform(get("/api/v1/imports/production/{jobId}/photo-manifest", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_PHOTO_SUPPLEMENT_NOT_ALLOWED"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:job
                  AND action_code='IMPORT_PHOTO_SUPPLEMENTED'
                """).param("job", jobId).query(Long.class).single()).isOne();
    }

    @Test
    void rejectsInvalidMarketMonthWithoutLeavingAVisibleDraftOrFormalRecord() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "RICE")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "MARKET");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "RICE", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, "样本点名称", "地区", "月份越界市场样本", "");
        for (Map.Entry<String, String> value : completeMarketValues().entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        row = withValue(row, labels, "数据月份", "13");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-稻谷-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row))))
                        .param("productCode", "RICE")
                        .header("Idempotency-Key", "invalid-market-month")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result
                WHERE outcome_code='ERROR' AND error_message LIKE '%月份%1%12%'
                """).query(Long.class).single()).isOne();
    }

    @Test
    void retriesTheCompleteAtomicProductionBatchWithoutPartialWrites() throws Exception {
        assertGovernedAtomicRetryReplaysCompleteBatch(
                "production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", completeProductionValues(), "数据年份", "2201",
                "governed-production-retry", "production.production_record", "INVALID_IMPORT_YEAR");
    }

    @Test
    void retriesTheCompleteAtomicMarketBatchWithoutPartialWrites() throws Exception {
        assertGovernedAtomicRetryReplaysCompleteBatch(
                "market", "MARKET", "市场", "market-tester",
                "样本点名称", completeMarketValues(), "数据月份", "13",
                "governed-market-retry", "market.market_record", "INVALID_IMPORT_MONTH");
    }

    @Test
    void retriesTheCompleteAtomicLogisticsBatchWithoutPartialWrites() throws Exception {
        assertGovernedAtomicRetryReplaysCompleteBatch(
                "logistics", "LOGISTICS", "物流", "logistics-tester",
                "物流样本点名称", completeLogisticsValues(), "运输方式", "航空",
                "governed-logistics-retry", "logistics.route_event", "IMPORT_VALUE_FORMAT_INVALID");
    }

    @Test
    void retriesLegacyGovernedSourceThatDoesNotContainAPhotoJobId() throws Exception {
        assertGovernedAtomicRetryReplaysCompleteBatch(
                "production", "PRODUCTION", "产情", "production-tester",
                "样本点名称", completeProductionValues(), "数据年份", "2201",
                "legacy-governed-production-retry", "production.production_record",
                "INVALID_IMPORT_YEAR", true);
    }

    @Test
    void batchApprovalPublishesEveryImportedRowInTheSelectedScope() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "PRODUCTION");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "PRODUCTION", "产情", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> first = sparse(labels, "样本点名称", "地区", "一键审核产情样本一", "");
        List<String> second = sparse(labels, "样本点名称", "地区", "一键审核产情样本二", "");
        for (Map.Entry<String, String> value : completeProductionValues().entrySet()) {
            first = withValue(first, labels, value.getKey(), value.getValue());
            second = withValue(second, labels, value.getKey(), value.getValue());
        }
        first = withValue(first, labels, "纬度（度）", "47.501001");
        first = withValue(first, labels, "经度（度）", "123.601001");
        second = withValue(second, labels, "纬度（度）", "47.501002");
        second = withValue(second, labels, "经度（度）", "123.601002");

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "产情-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(first, second))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "batch-review-corn")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2));

        mvc.perform(post("/api/v1/work-items/batch-approve")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"PRODUCTION\",\"productCode\":\"CORN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedCount").value(2))
                .andExpect(jsonPath("$.data.approvedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.production_record
                WHERE product_code='CORN' AND status_code='APPROVED'
                """).query(Long.class).single()).isEqualTo(2);
        mvc.perform(get("/api/v1/overview/dashboard")
                        .param("productCode", "CORN").param("regionCode", "230208")
                        .param("year", "2026").principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("200")));
    }

    @Test
    void batchApprovalPublishesImportedMarketInventoryWithoutHiddenGovernance() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, "MARKET");
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> first = sparse(labels, "样本点名称", "地区", "一键审核市场样本一", "");
        List<String> second = sparse(labels, "样本点名称", "地区", "一键审核市场样本二", "");
        for (Map.Entry<String, String> value : completeMarketValues().entrySet()) {
            first = withValue(first, labels, value.getKey(), value.getValue());
            second = withValue(second, labels, value.getKey(), value.getValue());
        }
        first = withValue(first, labels, "纬度（度）", "47.7256536");
        first = withValue(first, labels, "经度（度）", "124.0361733");
        second = withValue(second, labels, "纬度（度）", "47.5329964");
        second = withValue(second, labels, "经度（度）", "123.6450040");
        first = withValue(first, labels, "现有库存（吨）", "20");
        second = withValue(second, labels, "样本点联系方式", "13900000001");
        second = withValue(second, labels, "现有库存（吨）", "30");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "市场-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(first, second))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "batch-review-market-inventory")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        mvc.perform(post("/api/v1/work-items/batch-approve")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"MARKET\",\"productCode\":\"CORN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedCount").value(2))
                .andExpect(jsonPath("$.data.approvedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record
                WHERE product_code='CORN' AND status_code='APPROVED'
                """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record
                WHERE product_code='CORN' AND status_code='APPROVED' AND sample_point_id IS NOT NULL
                """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM overview.approved_sample_point_source
                WHERE source_domain='MARKET' AND product_code='CORN'
                """).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_inventory_governance")
                .query(Long.class).single()).isZero();
    }

    @Test
    void oneProductionSampleAcrossProductsUsesOneMapPointAndKeepsBothProductRecordsEffective()
            throws Exception {
        String corn = importSubmitAndApprove("CORN", "production", "PRODUCTION", "产情",
                "production-tester", "market-tester", "production-records", "跨品种产情样本",
                "样本点名称", completeProductionValues());
        String soybean = importSubmitAndApprove("SOYBEAN", "production", "PRODUCTION", "产情",
                "production-tester", "market-tester", "production-records", "跨品种产情样本",
                "样本点名称", completeProductionValues());

        assertThat(jdbc.sql("""
                SELECT count(DISTINCT sample_point_id) FROM production.production_record
                WHERE record_id IN (:corn,:soybean) AND sample_point_id IS NOT NULL
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT business_identity)
                FROM production.production_record_business_identity
                WHERE record_id IN (:corn,:soybean)
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.effective_approved_production_record
                WHERE record_id IN (:corn,:soybean)
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void oneMarketSampleAcrossProductsUsesOneMapPointAndKeepsBothProductRecordsEffective()
            throws Exception {
        String corn = importSubmitAndApprove("CORN", "market", "MARKET", "市场",
                "market-tester", "production-tester", "market-records", "跨品种市场样本",
                "样本点名称", completeMarketValues());
        String soybean = importSubmitAndApprove("SOYBEAN", "market", "MARKET", "市场",
                "market-tester", "production-tester", "market-records", "跨品种市场样本",
                "样本点名称", completeMarketValues());

        assertThat(jdbc.sql("""
                SELECT count(DISTINCT sample_point_id) FROM market.market_record
                WHERE record_id IN (:corn,:soybean) AND sample_point_id IS NOT NULL
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT business_identity)
                FROM market.market_record_business_identity
                WHERE record_id IN (:corn,:soybean)
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.effective_approved_market_record
                WHERE record_id IN (:corn,:soybean)
                """).param("corn", corn).param("soybean", soybean)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void submitsCompleteWorkbookRowsAndIndependentApprovalPublishesAllThreeDomains() throws Exception {
        String productionRecord = importSubmitAndApprove("production", "PRODUCTION", "产情",
                "production-tester", "market-tester", "production-records", "正式产情样本",
                "样本点名称", Map.ofEntries(
                        Map.entry("样本点类型", "农户"), Map.entry("数据年份", "2026"),
                        Map.entry("调研人", "王雷"), Map.entry("调研人联系方式", "13800000000"),
                        Map.entry("样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
                        Map.entry("播种面积（亩）", "100"),
                        Map.entry("预计单产（公斤/亩）", "500")));
        String marketRecord = importSubmitAndApprove("market", "MARKET", "市场",
                "market-tester", "production-tester", "market-records", "正式市场样本",
                "样本点名称", Map.ofEntries(
                        Map.entry("样本点类型", "贸易商"), Map.entry("数据年份", "2026"),
                        Map.entry("数据月份", "8"),
                        Map.entry("调研人", "王雷"), Map.entry("调研人联系方式", "13800000000"),
                        Map.entry("样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
                        Map.entry("采集对象收购价格（元/吨）", "2300"),
                        Map.entry("采集对象销售价格（元/吨）", "2380"),
                        Map.entry("车板组成（元/吨）", "36"),
                        Map.entry("包装形态", "散粮"), Map.entry("运费组成（元/吨）", "72")));
        String logisticsRecord = importSubmitAndApprove("logistics", "LOGISTICS", "物流",
                "logistics-tester", "production-tester", "logistics-records", "正式物流样本",
                "物流样本点名称", Map.ofEntries(
                        Map.entry("数据年份", "2026"), Map.entry("数据月份", "8"),
                        Map.entry("调研人", "王雷"), Map.entry("调研人联系方式", "13800000000"),
                        Map.entry("物流样本点联系方式", "13900000000"),
                        Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
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
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[?(@.regionCode == '230208')].values.LOG_ROUTE_VOLUME.value")
                        .value(org.hamcrest.Matchers.hasItem("12.5")))
                .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").isEmpty());
    }

    @Test
    void submitsAndIndependentlyApprovesAllNinePublicProductDomainTemplates() throws Exception {
        for (Map.Entry<String, String> product : Map.of(
                "CORN", "玉米", "SOYBEAN", "大豆", "RICE", "稻谷").entrySet()) {
            String productCode = product.getKey();
            String productLabel = product.getValue();
            String productionRecord = importSubmitAndApprove(productCode, "production", "PRODUCTION", "产情",
                    "production-tester", "market-tester", "production-records", productLabel + "正式产情样本",
                    "样本点名称", completeProductionValues());
            String marketRecord = importSubmitAndApprove(productCode, "market", "MARKET", "市场",
                    "market-tester", "production-tester", "market-records", productLabel + "正式市场样本",
                    "样本点名称", completeMarketValues());
            String logisticsRecord = importSubmitAndApprove(productCode, "logistics", "LOGISTICS", "物流",
                    "logistics-tester", "production-tester", "logistics-records", productLabel + "正式物流样本",
                    "物流样本点名称", completeLogisticsValues());

            assertThat(jdbc.sql("SELECT status_code FROM production.production_record WHERE record_id=:id")
                    .param("id", productionRecord).query(String.class).single()).isEqualTo("APPROVED");
            assertThat(jdbc.sql("SELECT status_code FROM market.market_record WHERE record_id=:id")
                    .param("id", marketRecord).query(String.class).single()).isEqualTo("APPROVED");
            assertThat(jdbc.sql("SELECT status_code FROM logistics.route_event WHERE event_id::text=:id")
                    .param("id", logisticsRecord).query(String.class).single()).isEqualTo("APPROVED");

            mvc.perform(get("/api/v1/observable-analysis/snapshots")
                            .param("productCode", productCode).param("regionCode", "230208")
                            .param("surveyYear", "2026").principal(() -> "production-tester"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.coverage.recordCount").value(3));
            mvc.perform(get("/api/v1/overview/dashboard")
                            .param("productCode", productCode).param("regionCode", "230208")
                            .param("year", "2026").principal(() -> "production-tester"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                            .value(org.hamcrest.Matchers.hasItem("100")))
                    .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value")
                            .value(org.hamcrest.Matchers.hasItem("2300")))
                    .andExpect(jsonPath(
                            "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[?(@.regionCode == '230208')].values.LOG_ROUTE_VOLUME.value")
                            .value(org.hamcrest.Matchers.hasItem("12.5")))
                    .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").isEmpty());
        }

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record WHERE status_code='APPROVED'")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record WHERE status_code='APPROVED'")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event WHERE status_code='APPROVED'")
                .query(Long.class).single()).isEqualTo(3);
    }

    private void assertGovernedAtomicRetryReplaysCompleteBatch(
            String route, String domainCode, String domainLabel, String principal,
            String sampleLabel, Map<String, String> completeValues,
            String invalidLabel, String invalidValue, String key,
            String formalTable, String expectedErrorCode) throws Exception {
        assertGovernedAtomicRetryReplaysCompleteBatch(route, domainCode, domainLabel, principal,
                sampleLabel, completeValues, invalidLabel, invalidValue, key,
                formalTable, expectedErrorCode, false);
    }

    private void assertGovernedAtomicRetryReplaysCompleteBatch(
            String route, String domainCode, String domainLabel, String principal,
            String sampleLabel, Map<String, String> completeValues,
            String invalidLabel, String invalidValue, String key,
            String formalTable, String expectedErrorCode, boolean simulateLegacySource) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> principal))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> valid = sparse(labels, sampleLabel, "地区", domainLabel + "有效重试样本", "");
        List<String> invalid = sparse(labels, sampleLabel, "地区", domainLabel + "无效重试样本", "");
        for (Map.Entry<String, String> value : completeValues.entrySet()) {
            valid = withValue(valid, labels, value.getKey(), value.getValue());
            invalid = withValue(invalid, labels, value.getKey(), value.getValue());
        }
        invalid = withValue(invalid, labels, invalidLabel, invalidValue);

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", domainLabel + "-玉米-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(valid, invalid))))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", key).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        String originalJobId = jdbc.sql("""
                SELECT import_job_id::text FROM platform.import_job WHERE idempotency_key=:key
                """).param("key", key).query(String.class).single();
        String errorReceipt = mvc.perform(get("/api/v1/imports/" + route + "/{id}/errors", originalJobId)
                        .principal(() -> principal))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(errorReceipt).contains("errorCode,errorMessage", expectedErrorCode)
                .doesNotContain("Exception");

        if (simulateLegacySource) {
            String prefix = "GOVERNED-DRAFT-V1:";
            String source = jdbc.sql("""
                    SELECT source_content FROM platform.import_job WHERE import_job_id=:id
                    """).param("id", java.util.UUID.fromString(originalJobId)).query(String.class).single();
            String sourceJson = new String(java.util.Base64.getDecoder().decode(source.substring(prefix.length())),
                    java.nio.charset.StandardCharsets.UTF_8);
            String legacyJson = sourceJson.replaceFirst(
                    ",\\\"photoJobId\\\":\\\"[^\\\"]+\\\"", "");
            assertThat(legacyJson).isNotEqualTo(sourceJson).doesNotContain("\"photoJobId\"");
            String legacySource = prefix + java.util.Base64.getEncoder().encodeToString(
                    legacyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.sql("""
                    UPDATE platform.import_job SET source_content=:source WHERE import_job_id=:id
                    """).param("source", legacySource).param("id", java.util.UUID.fromString(originalJobId))
                    .update();
        }

        mvc.perform(post("/api/v1/imports/" + route + "/{id}/retries", originalJobId)
                        .principal(() -> principal))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.retryOf").value(originalJobId))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        if (simulateLegacySource) {
            String migratedSource = jdbc.sql("""
                    SELECT source_content FROM platform.import_job
                    WHERE retry_of_import_job_id=:id
                    """).param("id", java.util.UUID.fromString(originalJobId)).query(String.class).single();
            String migratedJson = new String(java.util.Base64.getDecoder().decode(
                    migratedSource.substring("GOVERNED-DRAFT-V1:".length())),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(migratedJson).contains("\"photoJobId\":\"" + originalJobId + "\"");
        }

        assertThat(jdbc.sql("SELECT count(*) FROM " + formalTable).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft WHERE state_code='PROMOTED'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single())
                .isEqualTo(2);
    }

    private String importSubmitAndApprove(String route, String domainCode, String domainLabel,
            String operator, String reviewer, String canonicalRoute, String sampleName,
            String sampleLabel, Map<String, String> supplied) throws Exception {
        return importSubmitAndApprove("CORN", route, domainCode, domainLabel, operator, reviewer,
                canonicalRoute, sampleName, sampleLabel, supplied);
    }

    private String importSubmitAndApprove(String productCode, String route, String domainCode,
            String domainLabel, String operator, String reviewer, String canonicalRoute,
            String sampleName, String sampleLabel, Map<String, String> supplied) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", productCode).principal(() -> operator))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, productCode, null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        List<String> row = sparse(labels, sampleLabel, "地区", sampleName, "");
        for (Map.Entry<String, String> value : supplied.entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }
        if ("47.550000".equals(supplied.get("纬度（度）"))
                && "123.800000".equals(supplied.get("经度（度）"))) {
            row = withValue(row, labels, "纬度（度）", fixtureLatitude(sampleName));
            row = withValue(row, labels, "经度（度）", fixtureLongitude(sampleName));
        }
        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", domainLabel + "-"
                                + BusinessImportWorkbook.businessLabel(productCode) + "-批量导入模板.xlsx", XLSX,
                                createCurrentWorkbook(template, List.of(row))))
                        .param("productCode", productCode)
                        .header("Idempotency-Key", "formal-" + route + "-" + productCode)
                        .principal(() -> operator))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.importedRows").value(1));
        String draftId = jdbc.sql("""
                SELECT import_draft_id::text FROM platform.business_import_draft
                WHERE domain_code=:domain AND product_code=:product AND sample_name=:sample
                """).param("domain", domainCode).param("product", productCode)
                .param("sample", sampleName).query(String.class).single();
        String recordId = jdbc.sql("""
                SELECT canonical_record_id FROM platform.business_import_draft
                WHERE import_draft_id=:id AND state_code='PROMOTED'
                """).param("id", java.util.UUID.fromString(draftId)).query(String.class).single();
        mvc.perform(post("/api/v1/" + canonicalRoute + "/{id}/approve", recordId)
                        .principal(() -> reviewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        return recordId;
    }

    private WorkbookFixture workbookFixture(String route, String domainCode, String domainLabel,
            String operator, String sampleLabel) throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> operator))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> labels = withoutTrailingBlanks(XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
        BusinessImportWorkbook.Template template = new BusinessImportWorkbook.Template(
                domainCode, domainLabel, "CORN", null, context.contractVersion(), context.contractDigest(),
                labels, labels, List.of());
        return new WorkbookFixture(template, labels, sampleLabel);
    }

    private byte[] workbook(WorkbookFixture fixture, String sampleName, Map<String, String> supplied) {
        List<String> row = sparse(fixture.labels(), fixture.sampleLabel(), "地区", sampleName, "");
        for (Map.Entry<String, String> value : supplied.entrySet()) {
            row = withValue(row, fixture.labels(), value.getKey(), value.getValue());
        }
        return createCurrentWorkbook(fixture.template(), List.of(row));
    }

    private MvcResult performImport(String route, String principal, String idempotencyKey, byte[] workbook)
            throws Exception {
        return mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", route + "-玉米-期间守卫.xlsx", XLSX, workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", idempotencyKey)
                        .principal(() -> principal))
                .andExpect(status().isCreated()).andReturn();
    }

    private void importWorkbook(String route, String principal, String idempotencyKey, byte[] workbook,
            int importedRows, int failedRows) throws Exception {
        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", route + "-玉米-期间守卫.xlsx", XLSX, workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", idempotencyKey)
                        .principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(importedRows))
                .andExpect(jsonPath("$.data.failedRows").value(failedRows));
    }

    private void approveCanonicalRecord(String table, String idColumn, int surveyMonth,
            String route, String reviewer) throws Exception {
        Map<String, Object> record = jdbc.sql("""
                SELECT %s AS record_id,version FROM %s
                WHERE product_code='CORN' AND survey_year=2026 AND survey_month=:month
                """.formatted(idColumn, table)).param("month", surveyMonth).query().singleRow();
        mvc.perform(post(route, record.get("record_id"))
                        .principal(() -> reviewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + record.get("version") + "}"))
                .andExpect(status().isOk());
    }

    private void assertSameKeyReplayKeepsOriginalJobAndDraft(
            String route, String principal, String key, byte[] workbook) throws Exception {
        Map<String, Object> before = jdbc.sql("""
                SELECT job.import_job_id,draft.import_draft_id
                FROM platform.import_job job
                JOIN platform.business_import_draft draft ON draft.import_job_id=job.import_job_id
                WHERE job.requested_by=:principal AND job.domain_code=upper(:route)
                  AND job.idempotency_key=:key
                """).param("principal", principal).param("route", route).param("key", key)
                .query().singleRow();
        importWorkbook(route, principal, key, workbook, 1, 0);
        Map<String, Object> after = jdbc.sql("""
                SELECT job.import_job_id,draft.import_draft_id
                FROM platform.import_job job
                JOIN platform.business_import_draft draft ON draft.import_job_id=job.import_job_id
                WHERE job.requested_by=:principal AND job.domain_code=upper(:route)
                  AND job.idempotency_key=:key
                """).param("principal", principal).param("route", route).param("key", key)
                .query().singleRow();
        assertThat(after).isEqualTo(before);
    }

    private void assertPeriodConflict(String key) {
        assertThat(jdbc.sql("""
                SELECT error_code FROM platform.import_row_result result
                JOIN platform.import_job job ON job.import_job_id=result.import_job_id
                WHERE job.idempotency_key=:key
                """).param("key", key).query(String.class).single())
                .isEqualTo("SAMPLE_PERIOD_RECORD_CONFLICT");
    }

    private record WorkbookFixture(BusinessImportWorkbook.Template template,
            List<String> labels, String sampleLabel) {}

    @TestConfiguration
    static class PeriodGuardTestConfiguration {
        @Bean
        @Primary
        CoordinatingBusinessPeriodRecordGuard coordinatingBusinessPeriodRecordGuard(
                JdbcBusinessPeriodRecordGuard delegate) {
            return new CoordinatingBusinessPeriodRecordGuard(delegate);
        }
    }

    static final class CoordinatingBusinessPeriodRecordGuard implements BusinessPeriodRecordGuard {
        private final JdbcBusinessPeriodRecordGuard delegate;
        private final AtomicReference<GuardCompetition> active = new AtomicReference<>();

        CoordinatingBusinessPeriodRecordGuard(JdbcBusinessPeriodRecordGuard delegate) {
            this.delegate = delegate;
        }

        GuardCompetition arm() {
            GuardCompetition competition = new GuardCompetition(new AtomicInteger(),
                    new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
            assertThat(active.compareAndSet(null, competition)).isTrue();
            return competition;
        }

        void disarm(GuardCompetition competition) {
            active.compareAndSet(competition, null);
        }

        @Override
        public void lockAndRequireAvailable(ImportDraft draft) {
            GuardCompetition competition = active.get();
            if (competition == null) {
                delegate.lockAndRequireAvailable(draft);
                return;
            }
            int attempt = competition.attempts().incrementAndGet();
            if (attempt == 2) competition.secondLockAttempted().countDown();
            delegate.lockAndRequireAvailable(draft);
            if (attempt == 1) {
                competition.firstLockHeld().countDown();
                await(competition.releaseFirst());
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PERIOD_GUARD_TEST_RELEASE_TIMEOUT");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PERIOD_GUARD_TEST_INTERRUPTED", exception);
            }
        }
    }

    private record GuardCompetition(AtomicInteger attempts, CountDownLatch firstLockHeld,
            CountDownLatch secondLockAttempted, CountDownLatch releaseFirst) {}

    private static Map<String, String> completeProductionValues() {
        return Map.ofEntries(
                Map.entry("样本点类型", "农户"), Map.entry("数据年份", "2026"),
                Map.entry("调研人", "王雷"), Map.entry("调研人联系方式", "13800000000"),
                Map.entry("样本点联系方式", "13900000000"),
                Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
                Map.entry("播种面积（亩）", "100"), Map.entry("预计单产（公斤/亩）", "500"));
    }

    private static Map<String, String> completeMarketValues() {
        return Map.ofEntries(
                Map.entry("样本点类型", "贸易商"), Map.entry("数据年份", "2026"),
                Map.entry("数据月份", "8"), Map.entry("调研人", "王雷"),
                Map.entry("调研人联系方式", "13800000000"),
                Map.entry("样本点联系方式", "13900000000"),
                Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
                Map.entry("采集对象收购价格（元/吨）", "2300"),
                Map.entry("采集对象销售价格（元/吨）", "2380"),
                Map.entry("车板组成（元/吨）", "36"), Map.entry("包装形态", "散粮"),
                Map.entry("运费组成（元/吨）", "72"));
    }

    private static Map<String, String> completeLogisticsValues() {
        return Map.ofEntries(
                Map.entry("数据年份", "2026"), Map.entry("数据月份", "8"),
                Map.entry("调研人", "王雷"), Map.entry("调研人联系方式", "13800000000"),
                Map.entry("物流样本点联系方式", "13900000000"),
                Map.entry("纬度（度）", "47.550000"), Map.entry("经度（度）", "123.800000"),
                Map.entry("运输方式", "铁路"), Map.entry("运输方向", "流入"),
                Map.entry("运输数量（吨）", "12.5000"),
                Map.entry("物流运价（不含车板价）（元/吨）", "80.2500"),
                Map.entry("车板价（元/吨）", "2650.0000"));
    }

    private static String fixtureLatitude(String sampleName) {
        long hash = Integer.toUnsignedLong(sampleName.hashCode());
        return String.format(Locale.ROOT, "%.6f", 47.42 + ((hash / 60_000) % 60_000) / 1_000_000d);
    }

    private static String fixtureLongitude(String sampleName) {
        long hash = Integer.toUnsignedLong(sampleName.hashCode());
        return String.format(Locale.ROOT, "%.6f", 123.52 + (hash % 60_000) / 1_000_000d);
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(40, 120, 80));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
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
        byte[] workbook = createCurrentWorkbook(template, List.of(first, second));
        MockMultipartFile file = new MockMultipartFile("file", domainLabel + "-稻谷-批量导入模板.xlsx",
                XLSX, workbook);
        MockMultipartFile invalidPhoto = new MockMultipartFile(
                "photos", "不可用照片.jpg", "image/jpeg", "不是照片".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(file).file(invalidPhoto).param("productCode", "RICE")
                        .header("Idempotency-Key", key).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        mvc.perform(multipart("/api/v1/imports/" + route)
                .file(file).file(invalidPhoto).param("productCode", "RICE")
                .header("Idempotency-Key", key).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_import_draft WHERE domain_code=:domain
                """).param("domain", domainCode).query(Long.class).single()).isZero();
    }

    private void importOnePublicWorkbookWithLegacyObjectTypeParameter(
            String route, String domainCode, String domainLabel, String principal,
            String sampleLabel, String sampleName, String objectTypeCode) throws Exception {
        BusinessImportWorkbook.Template template;
        if ("MARKET".equals(domainCode)) {
            var objectTypes = templateCatalog.objectTypes("MARKET", "CORN");
            var definitions = objectTypes.stream()
                    .map(option -> market.definition("CORN", option.code())).toList();
            template = MarketImportTemplate.productWorkbook("CORN", definitions, objectTypes);
        } else {
            byte[] downloaded = mvc.perform(get("/api/v1/imports/" + route + "/template")
                            .param("format", "xlsx").param("productCode", "CORN")
                            .param("objectTypeCode", objectTypeCode).principal(() -> principal))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            List<String> downloadedLabels = withoutTrailingBlanks(
                    XlsxTable.parseWorksheet(downloaded, 1, 256).getFirst());
            BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(downloaded, domainCode);
            template = new BusinessImportWorkbook.Template(
                    domainCode, domainLabel, "CORN", null,
                    context.contractVersion(), context.contractDigest(),
                    downloadedLabels, downloadedLabels, List.of());
        }
        List<String> labels = template.labels();
        List<String> row = sparse(labels, sampleLabel, "地区", sampleName, "");
        Map<String, String> supplied = "PRODUCTION".equals(domainCode)
                ? completeProductionValues() : completeMarketValues();
        for (Map.Entry<String, String> value : supplied.entrySet()) {
            row = withValue(row, labels, value.getKey(), value.getValue());
        }

        mvc.perform(multipart("/api/v1/imports/" + route)
                        .file(new MockMultipartFile("file", domainLabel + "-玉米-批量导入模板.xlsx", XLSX,
                                BusinessImportWorkbook.create(template, List.of(row))))
                        .param("productCode", "CORN").param("objectTypeCode", objectTypeCode)
                        .header("Idempotency-Key", "legacy-client-" + route).principal(() -> principal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));
    }

    private byte[] createCurrentWorkbook(
            BusinessImportWorkbook.Template template, List<List<String>> rows) {
        if (!"MARKET".equals(template.domainCode())
                || !template.contractVersion().endsWith("-multi")) {
            return BusinessImportWorkbook.create(template, rows);
        }
        String targetObjectType = template.objectTypeCode() == null
                ? "TRADER" : template.objectTypeCode();
        List<BusinessImportWorkbook.WorkbookSheet> sheets = templateCatalog
                .objectTypes("MARKET", template.productCode()).stream()
                .map(option -> new BusinessImportWorkbook.WorkbookSheet(option.label(),
                        MarketImportTemplate.workbook(
                                market.definition(template.productCode(), option.code())),
                        option.code().equals(targetObjectType) ? rows : List.of()))
                .toList();
        byte[] workbook = BusinessImportWorkbook.createSheets(sheets);
        BusinessImportWorkbook.readDraftSheets(workbook, sheets, 5_000);
        return workbook;
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
        if ("样本点类型".equals(label) && !labels.contains(label)) return source;
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
