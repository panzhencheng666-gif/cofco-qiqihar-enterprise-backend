package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ReportingRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE workflow.work_item,platform.business_audit_event,reporting.report_audit_event,reporting.report_publication,reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,production.production_record,market.market_record,logistics.route_event,supply.calculation_run,supply.source_adoption_set,registry.sample_point RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope").update();
        jdbc.sql("DELETE FROM platform.security_user_role").update();
        jdbc.sql("DELETE FROM platform.security_user").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope").update();
        jdbc.sql("DELETE FROM platform.work_unit").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('QI','齐齐哈尔工作单位',9001),('HEI','黑河工作单位',9002)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('QI','230200'),('QI','230202'),('HEI','231100')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES ('reporter','报表专员','QI'),('publisher','报表发布员','QI'),
                       ('limited-reporter','区县报表专员','QI'),('outside-unit-reporter','外单位报表专员','HEI')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES ('reporter','REPORTER'),('publisher','REPORT_PUBLISHER'),
                       ('limited-reporter','REPORTER'),('outside-unit-reporter','REPORTER')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES ('reporter','230200'),('publisher','230200'),
                       ('limited-reporter','230202'),('outside-unit-reporter','231100')
                """).update();
    }

    @AfterEach void cleanAfterEach() {
        clean();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test void requiresApprovedDataThenPreviewsExportsAndPublishes() throws Exception {
        String body = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\",\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "limited-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "outside-unit-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',current_date,now(),100,20,'APPROVED','report-test')""").param("id", UUID.randomUUID().toString()).update();
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.dataCutoffLabel").value("2026年第三季度"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports",preview).principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export).principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("报告名称")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("核定数据条数")));
        mvc.perform(post("/api/v1/reports/previews/{id}/publications",preview).principal(() -> "publisher").contentType(MediaType.APPLICATION_JSON).content("{\"exportTaskId\":\""+export+"\",\"expectedVersion\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.previewId").value(preview));
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.report_audit_event").query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isEqualTo(4L);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform.business_audit_event").update())
                .hasMessageContaining("business audit events are immutable");
    }

    @Test void changesDatasetDigestWhenApprovedSourceChangesButRecordCountDoesNot() throws Exception {
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('digest-source-a','CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).update();

        String firstPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String firstDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", firstPreview).query(String.class).single();

        String replayedPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String replayedDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", replayedPreview).query(String.class).single();
        assertThat(replayedDigest).isEqualTo(firstDigest);

        jdbc.sql("DELETE FROM production.production_record WHERE record_id='digest-source-a'").update();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('digest-source-b','CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).update();

        String secondPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String secondDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", secondPreview).query(String.class).single();

        assertThat(secondDigest).isNotEqualTo(firstDigest);
        assertThat(jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", firstPreview).query(String.class).single()).isEqualTo(firstDigest);
    }

    @Test void exposesProductionBusinessMetricsFromApprovedEffectiveRowsOnly() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('report-production-approved','CORN','FARMER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test'),
                      ('report-production-pending','CORN','FARMER','230202',DATE '2026-08-10',
                         TIMESTAMPTZ '2026-08-10 12:34:56+08',900,900,'PENDING_REVIEW','report-test')
                """).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '核定播种面积')].value")
                        .value(org.hamcrest.Matchers.hasItem("100 亩")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '加权预计单产')].value")
                        .value(org.hamcrest.Matchers.hasItem("20 公斤/亩")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '预计总产')].value")
                        .value(org.hamcrest.Matchers.hasItem("2 吨")));
    }

    @Test void acceptsNaturalReportTimeWithoutBusinessPeriodMasterData() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('report-natural-date','CORN','FARMER','230202',DATE '2024-11-15',
                         TIMESTAMPTZ '2024-11-15 12:34:56+08',100,20,'APPROVED','report-test')
                """).update();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_period WHERE code='2024-11-15'")
                .query(Long.class).single()).isZero();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\","
                + "\"periodCode\":\"2024-11-15\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dataCutoffLabel").value("2024年11月15日"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '核定播种面积')].value")
                        .value(org.hamcrest.Matchers.hasItem("100 亩")));

        for (String[] scope : new String[][] {
                {"PRODUCTION_WEEKLY", "2024-W46", "2024年第46周"},
                {"PRODUCTION_MONTHLY", "2024-11", "2024年11月"}
        }) {
            String scopedRequest = "{\"definitionCode\":\"" + scope[0]
                    + "\",\"productCode\":\"CORN\",\"regionLevel\":\"PREFECTURE\","
                    + "\"regionCode\":\"230200\",\"periodCode\":\"" + scope[1] + "\"}";
            mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                            .contentType(MediaType.APPLICATION_JSON).content(scopedRequest))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.dataCutoffLabel").value(scope[2]))
                    .andExpect(jsonPath("$.data.lines[?(@.label == '核定播种面积')].value")
                            .value(org.hamcrest.Matchers.hasItem("100 亩")));
        }
    }

    @Test void exposesMarketBusinessMetricsFromApprovedEffectiveRowsOnly() throws Exception {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by,deletion_state,deleted_at,deleted_by)
                VALUES('f6200000-0000-0000-0000-000000000001','SURVEY_SITE','已删除历史样本',
                  '230202','RETURNED','MISSING',DATE '2026-01-01','reporter','reporter',
                  'DELETED',now(),'reporter')
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                    record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                    purchase_base_price,sale_base_price,trade_direction,packaging_form,
                    status_code,last_modified_by,sample_point_id)
                VALUES('report-market-approved','CORN','TRADER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',2200,2400,'BOTH','BULK',
                         'APPROVED','report-test',NULL),
                      ('report-market-historical-deep','CORN','DEEP_PROCESSOR','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 13:34:56+08',2300,9999,'BOTH','BULK',
                         'APPROVED','report-test','f6200000-0000-0000-0000-000000000001'),
                      ('report-market-pending','CORN','TRADER','230202',DATE '2026-08-10',
                         TIMESTAMPTZ '2026-08-10 12:34:56+08',9200,9400,'BOTH','BULK',
                         'PENDING_REVIEW','report-test',NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES('report-market-approved','PURCHASE_VOLUME',30,'CORN','TRADER'),
                      ('report-market-approved','SALES_VOLUME',20,'CORN','TRADER'),
                      ('report-market-approved','ENDING_INVENTORY',50,'CORN','TRADER'),
                      ('report-market-pending','PURCHASE_VOLUME',900,'CORN','TRADER'),
                      ('report-market-pending','SALES_VOLUME',900,'CORN','TRADER'),
                      ('report-market-pending','ENDING_INVENTORY',900,'CORN','TRADER')
                """).update();
        String request = "{\"definitionCode\":\"MARKET_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '平均采集对象收购价格')].value")
                        .value(org.hamcrest.Matchers.hasItem("2200 元/吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '平均采集对象销售价格')].value")
                        .value(org.hamcrest.Matchers.hasItem("2400 元/吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '平均实际成交价格')].value")
                        .value(org.hamcrest.Matchers.hasItem("2300 元/吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '采购量')].value")
                        .value(org.hamcrest.Matchers.hasItem("30 吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '销售量')].value")
                        .value(org.hamcrest.Matchers.hasItem("20 吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '期末库存')].value")
                        .value(org.hamcrest.Matchers.hasItem("50 吨")));
    }

    @Test void exposesSupplyBusinessMetricsFromPublishedConfirmedRunsOnly() throws Exception {
        jdbc.sql("""
                INSERT INTO supply.source_adoption_set(
                    input_set_id,version_no,product_code,region_code,marketing_year,
                    reason,created_by,created_at,legacy,period_code,survey_year,survey_quarter,
                    period_precision,temporal_governance_state)
                VALUES('20000000-0000-0000-0000-000000000001',1,'CORN','230202','2026/27',
                    '报表正式结果测试来源','report-test',TIMESTAMPTZ '2026-08-09 12:00:00+08',true,
                    '2026-Q3',2026,'Q3','QUARTER','CONFIRMED')
                """).update();
        jdbc.sql("""
                INSERT INTO supply.calculation_run(
                    calculation_run_id,product_code,region_code,marketing_year,formula_version_id,
                    result_state,validation_codes,total_supply,total_use,calculated_ending_inventory,
                    adopted_ending_inventory,inventory_reconciliation_difference,balanced,created_by,
                    created_at,version,formula_snapshot,period_code,survey_year,survey_quarter,
                    period_precision,temporal_governance_state,input_set_id)
                VALUES('10000000-0000-0000-0000-000000000003','CORN','230202','2026/27',
                         (SELECT formula_version_id FROM supply.formula_version ORDER BY formula_version_id LIMIT 1),
                         'PUBLISHED',ARRAY[]::text[],900,800,100,100,0,true,'report-test',
                         TIMESTAMPTZ '2026-08-08 12:34:56+08',0,'{}'::jsonb,'2026-Q3',2026,'Q3',
                         'QUARTER','CONFIRMED','20000000-0000-0000-0000-000000000001'),
                      ('10000000-0000-0000-0000-000000000001','CORN','230202','2026/27',
                         (SELECT formula_version_id FROM supply.formula_version ORDER BY formula_version_id LIMIT 1),
                         'PUBLISHED',ARRAY[]::text[],100,80,20,20,0,true,'report-test',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',1,'{}'::jsonb,'2026-Q3',2026,'Q3',
                         'QUARTER','CONFIRMED','20000000-0000-0000-0000-000000000001'),
                      ('10000000-0000-0000-0000-000000000002','CORN','230202','2026/27',
                         (SELECT formula_version_id FROM supply.formula_version ORDER BY formula_version_id LIMIT 1),
                         'DRAFT',ARRAY[]::text[],999,999,0,999,999,true,'report-test',
                         TIMESTAMPTZ '2026-08-10 12:34:56+08',2,'{}'::jsonb,'2026-Q3',2026,'Q3',
                         'QUARTER','CONFIRMED','20000000-0000-0000-0000-000000000001')
                """).update();
        String request = "{\"definitionCode\":\"SUPPLY_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '总供给')].value")
                        .value(org.hamcrest.Matchers.hasItem("100 吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '总消费')].value")
                        .value(org.hamcrest.Matchers.hasItem("80 吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '采用期末库存')].value")
                        .value(org.hamcrest.Matchers.hasItem("20 吨")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '库存核对差异')].value")
                        .value(org.hamcrest.Matchers.hasItem("0 吨")));
    }

    @Test void listsOnlyCombinedReportFrequenciesWithoutProductSelectors() throws Exception {
        mvc.perform(get("/api/v1/reports/parameter-options").principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitions.length()").value(3))
                .andExpect(jsonPath("$.data.definitions[*].code").value(org.hamcrest.Matchers.contains(
                        "COMPREHENSIVE_DAILY", "COMPREHENSIVE_WEEKLY", "COMPREHENSIVE_MONTHLY")))
                .andExpect(jsonPath("$.data.definitions[*].name").value(org.hamcrest.Matchers.contains(
                        "综合经营日报", "综合经营周报", "综合经营月报")))
                .andExpect(jsonPath("$.data.definitions[*].businessDomain").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("COMPREHENSIVE"))))
                .andExpect(jsonPath("$.data.products.length()").value(0))
                .andExpect(jsonPath("$.data.cultivars.length()").value(0));
    }

    @Test void createsAndExportsOneApprovedMultiProductSnapshotWithoutTreatingMissingDataAsZero() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('combined-corn-approved','CORN','FARMER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test'),
                      ('combined-rice-pending','RICE','FARMER','230202',DATE '2026-08-10',
                         TIMESTAMPTZ '2026-08-10 12:34:56+08',900,900,'PENDING_REVIEW','report-test')
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record(
                    record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                    purchase_base_price,sale_base_price,trade_direction,packaging_form,
                    status_code,last_modified_by)
                VALUES('combined-soybean-approved','SOYBEAN','TRADER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 13:34:56+08',4200,4400,'BOTH','BULK',
                         'APPROVED','report-test')
                """).update();
        String request = "{\"definitionCode\":\"COMPREHENSIVE_DAILY\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\","
                + "\"periodCode\":\"2026-Q3\"}";

        String response = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("齐齐哈尔市综合经营日报"))
                .andExpect(jsonPath("$.data.lines[0].value").value("2"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '报告范围')].value")
                        .value(org.hamcrest.Matchers.hasItem("齐齐哈尔市 / 玉米、大豆、稻谷 / 2026年第三季度")))
                .andExpect(jsonPath("$.data.products[*].code").value(
                        org.hamcrest.Matchers.contains("CORN", "SOYBEAN", "RICE")))
                .andExpect(jsonPath("$.data.products[0].domains[*].code").value(
                        org.hamcrest.Matchers.contains("PRODUCTION", "MARKET", "LOGISTICS", "SUPPLY")))
                .andExpect(jsonPath("$.data.products[0].domains[0].approvedRecordCount").value(1))
                .andExpect(jsonPath("$.data.products[0].domains[0].metrics"
                        + "[?(@.label == '核定播种面积')].value")
                        .value(org.hamcrest.Matchers.hasItem("100 亩")))
                .andExpect(jsonPath("$.data.products[1].domains[1].approvedRecordCount").value(1))
                .andExpect(jsonPath("$.data.products[1].domains[1].metrics"
                        + "[?(@.label == '平均采集对象收购价格')].value")
                        .value(org.hamcrest.Matchers.hasItem("4200 元/吨")))
                .andExpect(jsonPath("$.data.products[2].domains[0].approvedRecordCount").value(0))
                .andExpect(jsonPath("$.data.products[2].domains[0].metrics"
                        + "[?(@.label == '核定播种面积')].value")
                        .value(org.hamcrest.Matchers.hasItem("暂无审核数据")))
                .andReturn().getResponse().getContentAsString();
        String preview = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        String sourceSummary = jdbc.sql("""
                SELECT dataset.source_summary::text
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", preview).query(String.class).single();
        assertThat(sourceSummary)
                .contains("\"productScope\": [\"CORN\", \"SOYBEAN\", \"RICE\"]")
                .contains("\"domainScope\": [\"PRODUCTION\", \"MARKET\", \"LOGISTICS\", \"SUPPLY\"]")
                .contains("combined-corn-approved", "combined-soybean-approved")
                .doesNotContain("combined-rice-pending");
        assertThat(jdbc.sql("""
                SELECT jsonb_exists(parameter_snapshot,'productCode')
                  OR jsonb_exists(parameter_snapshot,'cultivarCode')
                FROM reporting.report_preview WHERE preview_id=CAST(:preview AS uuid)
                """).param("preview", preview).query(Boolean.class).single()).isFalse();

        for (String format : java.util.List.of("PDF", "DOCX", "XLSX")) {
            String extension = format.toLowerCase(java.util.Locale.ROOT);
            String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                            .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"formatCode\":\"" + format + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.filename")
                            .value("齐齐哈尔市综合经营日报." + extension))
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
            byte[] bytes = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                            .principal(() -> "reporter"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            if (format.equals("PDF")) {
                assertThat(new String(bytes, 0, 8, java.nio.charset.StandardCharsets.US_ASCII))
                        .startsWith("%PDF-1.4");
            } else {
                assertThat(bytes).startsWith(80, 75, 3, 4);
            }
        }
    }

    @Test void evaluatesEveryActiveReportDomainAgainstItsDatabaseSource() throws Exception {
        for (String definition : java.util.List.of("MARKET_DAILY", "LOGISTICS_WEEKLY", "SUPPLY_MONTHLY")) {
            String request = "{\"definitionCode\":\"" + definition + "\",\"productCode\":\"CORN\","
                    + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
            mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        }
    }

    @Test void exportsTheServerOwnedScopedPreviewAsAnXlsxWorkbook() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"XLSX\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("XLSX"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] workbook = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(workbook).startsWith(80, 75, 3, 4);
        StringBuilder xml = new StringBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) {
                    xml.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(xml).contains("齐齐哈尔市玉米产情日报", "核定数据条数", "2026年第三季度");
    }

    @Test void exportsTheServerOwnedScopedPreviewAsAPdfDocument() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"PDF\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("PDF"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] pdf = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)).startsWith("%PDF-1.4");
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test void exposesFormalBusinessContentAndExportsTheSameSnapshotAsDocx() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String response = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '报告范围')].value")
                        .value(org.hamcrest.Matchers.hasItem("齐齐哈尔市 / 玉米 / 2026年第三季度")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '数据截止时间')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '核定播种面积')].value")
                        .value(org.hamcrest.Matchers.hasItem("100 亩")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '加权预计单产')].value")
                        .value(org.hamcrest.Matchers.hasItem("20 公斤/亩")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '预计总产')].value")
                        .value(org.hamcrest.Matchers.hasItem("2 吨")))
                .andExpect(jsonPath("$.data.sections[?(@.code == 'ANALYSIS')].body")
                        .value(org.hamcrest.Matchers.hasItem("根据当前审核数据，核定播种面积：100亩。"
                                + "加权预计单产：20公斤/亩。预计总产：2吨。")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("审计编号", "数据分级", "计算口径", "口径版本", "UUID");
        String preview = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"DOCX\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("DOCX"))
                .andExpect(jsonPath("$.data.filename").value("齐齐哈尔市玉米产情日报.docx"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] document = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".docx")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(document).startsWith(80, 75, 3, 4);
        StringBuilder xml = new StringBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(document))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) {
                    xml.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(xml).contains("报告范围", "数据截止时间", "核定播种面积", "100 亩",
                        "加权预计单产", "20 公斤/亩", "预计总产", "2 吨",
                        "总体概览", "核定数据", "分析说明")
                .doesNotContain("审计编号", "数据分级", "内部", "production.production_record",
                        "SUM(cultivated_area_mu)", "2026-08-09T04:34:56Z", "预计将", "建议", "应当");
    }

    @Test void scopesProductionReportsToDescendantRegionsAndTheRequestedCultivar() throws Exception {
        String requested = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:requested,'CORN','FARMER','230202',DATE '2026-08-09',now(),100,20,'APPROVED','report-test'),
                      (:other,'CORN','FARMER','230202',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("requested", requested).param("other", other).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:requested,'PROD_CULTIVAR_NAME','龙单86'),
                      (:other,'PROD_CULTIVAR_NAME','德美亚3号')
                """).param("requested", requested).param("other", other).update();

        String broadRequest = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(broadRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("2"));

        String cultivarRequest = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"cultivarCode\":\"龙单86\",\"regionLevel\":\"PREFECTURE\","
                + "\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(cultivarRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"));
    }

    @Test void excludesApprovedRowsWhoseSurveyPeriodIsStillPendingGovernance() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                    survey_period_governance_state)
                VALUES('report-confirmed','CORN','FARMER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test','CONFIRMED'),
                      ('report-pending','CORN','FARMER','230202',DATE '2026-08-10',
                         TIMESTAMPTZ '2026-08-10 23:59:59+08',200,30,'APPROVED','report-test','PENDING_GOVERNANCE')
                """).update();
        jdbc.sql("""
                UPDATE production.production_record
                SET survey_period_governance_state='PENDING_GOVERNANCE'
                WHERE record_id='report-pending'
                """).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '数据截止时间')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")));
    }
}
