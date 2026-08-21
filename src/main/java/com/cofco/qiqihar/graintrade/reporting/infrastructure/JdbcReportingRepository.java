package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.*;
import com.cofco.qiqihar.graintrade.reporting.domain.ReportExportContent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.SqlParameterValue;
import java.sql.Types;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class JdbcReportingRepository implements ReportingRepository {
    private static final String COMPREHENSIVE_STORAGE_DEFINITION = "COMPREHENSIVE_MONTHLY";
    private static final List<String> REPORT_PRODUCTS = List.of("CORN", "SOYBEAN", "RICE");
    private static final List<DomainScope> REPORT_DOMAINS = List.of(
            new DomainScope("PRODUCTION", "产情"),
            new DomainScope("MARKET", "市场"),
            new DomainScope("LOGISTICS", "物流"),
            new DomainScope("SUPPLY", "供需"));
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public JdbcReportingRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public ReportParameterOptionsView options() {
        return new ReportParameterOptionsView(comprehensiveDefinitions(), List.of(), List.of(),
                options("SELECT DISTINCT administrative_level,administrative_level FROM platform.region ORDER BY administrative_level"),
                options("SELECT code,name FROM platform.region ORDER BY sort_order"), options("SELECT code,name FROM platform.business_period ORDER BY sort_order DESC"),
                options("SELECT format_code,label FROM reporting.report_output_format WHERE enabled ORDER BY sort_order"));
    }

    @Override public ReportPreviewMaterial loadPreviewMaterial(ReportPreviewCommand command) {
        if (isComprehensive(command.definitionCode())) return loadComprehensivePreviewMaterial(command);
        DefinitionRow definition = jdbc.sql("""
                SELECT report_definition_id AS id,code,name,business_domain,business_subtype,frequency_code,version_no
                FROM reporting.report_definition WHERE code=:code AND active""").param("code", command.definitionCode()).query((row, index) -> new DefinitionRow(
                        row.getLong("id"), row.getString("code"), row.getString("name"), row.getString("business_domain"),
                        row.getString("business_subtype"), row.getString("frequency_code"), row.getInt("version_no"))).optional().orElse(null);
        if (definition == null || !exists("SELECT 1 FROM platform.product WHERE code=:code",command.productCode())
                || !exists("SELECT 1 FROM platform.region WHERE code=:code AND administrative_level=:level",command.regionCode(),command.regionLevel())
                ) return null;
        ReportPeriodScope reportPeriod = reportPeriodScope(definition.frequencyCode(), command.periodCode());
        if (reportPeriod == null) return null;
        ApprovedDatasetSnapshot dataset = approvedDatasetSnapshot(definition.businessDomain(), command, reportPeriod);
        long count = dataset.approvedRecordCount();
        Instant cutoff = dataset.dataCutoff();
        ObjectNode summaryNode = json.createObjectNode();
        summaryNode.put("businessDomain", definition.businessDomain());
        summaryNode.put("approvedRecordCount", count);
        if (cutoff == null) summaryNode.putNull("dataCutoff");
        else summaryNode.put("dataCutoff", cutoff.toString());
        summaryNode.set("sources", json.readTree(dataset.sourceManifestJson()));
        String summary = summaryNode.toString();
        return new ReportPreviewMaterial(new ReportDefinitionView(definition.code(),definition.name(),definition.businessDomain(),definition.businessSubtype(),definition.frequencyCode(),definition.versionNo(), sections(definition.id())),
                label("SELECT name FROM platform.product WHERE code=:code",command.productCode()), label("SELECT name FROM platform.region WHERE code=:code",command.regionCode()),
                reportPeriod.label(),summary,count,cutoff,
                dataset.businessMetrics(), List.of());
    }

    private ReportPreviewMaterial loadComprehensivePreviewMaterial(ReportPreviewCommand command) {
        ReportDefinitionView definition = comprehensiveDefinition(command.definitionCode());
        if (definition == null || !exists(
                "SELECT 1 FROM platform.region WHERE code=:code AND administrative_level=:level",
                command.regionCode(), command.regionLevel())) return null;
        ReportPeriodScope reportPeriod = reportPeriodScope(definition.frequencyCode(), command.periodCode());
        if (reportPeriod == null) return null;

        ObjectNode summary = json.createObjectNode();
        ArrayNode productScope = summary.putArray("productScope");
        REPORT_PRODUCTS.forEach(productScope::add);
        ArrayNode domainScope = summary.putArray("domainScope");
        REPORT_DOMAINS.forEach(domain -> domainScope.add(domain.code()));
        ObjectNode compatibility = summary.putObject("persistenceCompatibilityAnchor");
        compatibility.put("definitionCode", COMPREHENSIVE_STORAGE_DEFINITION);
        compatibility.put("productCode", REPORT_PRODUCTS.getFirst());
        ArrayNode sourceScopes = summary.putArray("sourceScopes");

        long approvedRecordCount = 0;
        Instant dataCutoff = null;
        List<ReportBusinessMetric> combinedMetrics = new ArrayList<>();
        List<ReportProductSnapshot> products = new ArrayList<>();
        for (String productCode : REPORT_PRODUCTS) {
            String productLabel = label("SELECT name FROM platform.product WHERE code=:code", productCode);
            List<ReportProductSnapshot.DomainSnapshot> domains = new ArrayList<>();
            for (DomainScope domain : REPORT_DOMAINS) {
                ApprovedDatasetSnapshot dataset = approvedDatasetSnapshot(domain.code(),
                        new ReportPreviewCommand(command.definitionCode(), productCode, null,
                                command.regionLevel(), command.regionCode(), command.periodCode()),
                        reportPeriod);
                approvedRecordCount += dataset.approvedRecordCount();
                if (dataset.dataCutoff() != null
                        && (dataCutoff == null || dataset.dataCutoff().isAfter(dataCutoff))) {
                    dataCutoff = dataset.dataCutoff();
                }
                ObjectNode sourceScope = sourceScopes.addObject();
                sourceScope.put("productCode", productCode);
                sourceScope.put("domainCode", domain.code());
                sourceScope.set("records", json.readTree(dataset.sourceManifestJson()));

                domains.add(new ReportProductSnapshot.DomainSnapshot(
                        domain.code(), domain.label(), dataset.approvedRecordCount(),
                        dataset.dataCutoff(), dataset.businessMetrics()));
                combinedMetrics.add(scopedCountMetric(
                        productCode, productLabel, domain, dataset.approvedRecordCount()));
                for (ReportBusinessMetric metric : dataset.businessMetrics()) {
                    combinedMetrics.add(new ReportBusinessMetric(
                            productCode + "_" + domain.code() + "_" + metric.code(),
                            productLabel + " · " + domain.label() + " · " + metric.label(),
                            metric.value(), metric.unit(), metric.aggregation(), metric.sourceCount(),
                            metric.missingReason()));
                }
            }
            products.add(new ReportProductSnapshot(productCode, productLabel, domains));
        }
        summary.put("businessDomain", "COMPREHENSIVE");
        summary.put("approvedRecordCount", approvedRecordCount);
        if (dataCutoff == null) summary.putNull("dataCutoff");
        else summary.put("dataCutoff", dataCutoff.toString());
        return new ReportPreviewMaterial(definition, "玉米、大豆、稻谷",
                label("SELECT name FROM platform.region WHERE code=:code", command.regionCode()),
                reportPeriod.label(), summary.toString(), approvedRecordCount, dataCutoff,
                List.copyOf(combinedMetrics), List.copyOf(products));
    }

    private static ReportBusinessMetric scopedCountMetric(
            String productCode, String productLabel, DomainScope domain, long count) {
        return count < 1
                ? new ReportBusinessMetric(productCode + "_" + domain.code() + "_APPROVED_RECORDS",
                        productLabel + " · " + domain.label() + " · 核定记录数",
                        null, "条", "计数", 0, "暂无审核数据")
                : new ReportBusinessMetric(productCode + "_" + domain.code() + "_APPROVED_RECORDS",
                        productLabel + " · " + domain.label() + " · 核定记录数",
                        Long.toString(count), "条", "计数", count, null);
    }

    @Override public ReportPreviewView persistPreview(ReportPreviewPersistence value) {
        String previewId = UUID.randomUUID().toString();
        boolean comprehensive = isComprehensive(value.command().definitionCode());
        String storageDefinition = comprehensive
                ? COMPREHENSIVE_STORAGE_DEFINITION : value.command().definitionCode();
        String storageProduct = comprehensive
                ? REPORT_PRODUCTS.getFirst() : value.command().productCode();
        String storageCultivar = comprehensive ? null : value.command().cultivarCode();
        jdbc.sql("""
                INSERT INTO reporting.approved_dataset(dataset_id,report_definition_id,product_code,cultivar_code,region_level,region_code,period_code,frequency_code,source_state,source_summary,immutable_digest,captured_at,captured_by)
                SELECT CAST(:dataset AS uuid),report_definition_id,:product,:cultivar,:level,:region,:period,:frequency,'APPROVED',CAST(:summary AS jsonb),:digest,:now,:actor
                FROM reporting.report_definition WHERE code=:definition""")
            .param("dataset", value.datasetId()).param("product", storageProduct)
            .param("cultivar", storageCultivar).param("level", value.command().regionLevel())
            .param("region", value.command().regionCode()).param("period", value.command().periodCode())
            .param("frequency", value.material().definition().frequencyCode())
            .param("summary", value.material().approvedSummaryJson()).param("digest", value.datasetDigest())
            .param("now", Timestamp.from(value.now())).param("actor", value.actor())
            .param("definition", storageDefinition).update();
        jdbc.sql("""
                INSERT INTO reporting.report_preview(preview_id,report_definition_id,dataset_id,parameter_snapshot,content_snapshot,content_digest,created_by,created_at,expires_at)
                SELECT CAST(:preview AS uuid),report_definition_id,CAST(:dataset AS uuid),CAST(:parameters AS jsonb),CAST(:content AS jsonb),:digest,:actor,:now,:expires
                FROM reporting.report_definition WHERE code=:definition""")
            .param("preview", previewId).param("dataset", value.datasetId())
            .param("parameters", parameters(value.command())).param("content", value.contentJson())
            .param("digest", value.contentDigest()).param("actor", value.actor())
            .param("now", Timestamp.from(value.now())).param("expires", Timestamp.from(value.expiresAt()))
            .param("definition", storageDefinition).update();
        audit("PREVIEW",previewId,"PREVIEWED",value.actor(),value.now(),"{}");
        return view(previewId,value.command().definitionCode(),value.datasetId(),value.contentJson(),value.expiresAt(),0);
    }

    @Override public ReportPreviewView findPreview(String id) { return jdbc.sql("""
            SELECT preview.preview_id::text,
              COALESCE(preview.parameter_snapshot->>'definitionCode',definition.code),
              preview.dataset_id::text,preview.content_snapshot::text,preview.expires_at,preview.version
            FROM reporting.report_preview preview
            JOIN reporting.report_definition definition
              ON definition.report_definition_id=preview.report_definition_id
            WHERE preview.preview_id=CAST(:id AS uuid)""").param("id",id)
            .query((r,n)->view(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                    r.getTimestamp(5).toInstant(),r.getLong(6))).optional().orElse(null); }
    @Override public String findPreviewRegion(String previewId) {
        return jdbc.sql("""
                SELECT dataset.region_code FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id = preview.dataset_id
                WHERE preview.preview_id = CAST(:id AS uuid)
                """).param("id", previewId).query(String.class).optional().orElse(null);
    }
    private List<ReportParameterOptionsView.Option> options(String sql){ return jdbc.sql(sql).query((r,n)->new ReportParameterOptionsView.Option(r.getString(1),r.getString(2))).list(); }
    private List<ReportDefinitionView.Section> sections(long id){ return jdbc.sql("SELECT section_code,title,sort_order FROM reporting.report_definition_section WHERE report_definition_id=:id ORDER BY sort_order").param("id",id).query((r,n)->new ReportDefinitionView.Section(r.getString(1),r.getString(2),r.getInt(3))).list(); }
    private boolean exists(String sql,String code){return jdbc.sql(sql).param("code",code).query(Integer.class).optional().isPresent();}
    private boolean exists(String sql,String code,String level){return jdbc.sql(sql).param("code",code).param("level",level).query(Integer.class).optional().isPresent();}
    private String label(String sql,String code){return jdbc.sql(sql).param("code",code).query(String.class).single();}

    private ReportPeriodScope reportPeriodScope(String frequencyCode, String periodCode) {
        ReportPeriodScope governed = jdbc.sql("""
                SELECT starts_on,ends_on,name FROM platform.business_period WHERE code=:code
                """).param("code", periodCode).query((row, index) -> new ReportPeriodScope(
                        row.getObject("starts_on", LocalDate.class),
                        row.getObject("ends_on", LocalDate.class),
                        row.getString("name"))).optional().orElse(null);
        if (governed != null) return governed;
        try {
            return switch (frequencyCode) {
                case "DAILY" -> {
                    LocalDate date = LocalDate.parse(periodCode, DateTimeFormatter.ISO_LOCAL_DATE);
                    yield new ReportPeriodScope(date, date,
                            date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日");
                }
                case "WEEKLY" -> {
                    if (!periodCode.matches("\\d{4}-W\\d{2}")) yield null;
                    LocalDate startsOn = LocalDate.parse(periodCode + "-1", DateTimeFormatter.ISO_WEEK_DATE);
                    yield new ReportPeriodScope(startsOn, startsOn.plusDays(6),
                            periodCode.substring(0, 4) + "年第" + periodCode.substring(6) + "周");
                }
                case "MONTHLY" -> {
                    YearMonth month = YearMonth.parse(periodCode);
                    yield new ReportPeriodScope(month.atDay(1), month.atEndOfMonth(),
                            month.getYear() + "年" + month.getMonthValue() + "月");
                }
                default -> null;
            };
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private ApprovedDatasetSnapshot approvedDatasetSnapshot(
            String domain, ReportPreviewCommand c, ReportPeriodScope reportPeriod) {
        String regionScope = """
                WITH RECURSIVE selected_regions(code) AS (
                    SELECT code FROM platform.region WHERE code=:region
                    UNION
                    SELECT child.code FROM platform.region child
                    JOIN selected_regions parent ON child.parent_code=parent.code
                )
                """;
        String period = ":periodStart AND :periodEnd";
        String production = regionScope + """
                SELECT count(*) AS approved_count,max(record.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',record.record_id,'sourceVersion',record.version,
                  'reportedAt',record.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(record)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(metadata) ORDER BY metadata.field_code)::text
                      FROM production.production_record_submission_metadata metadata
                      WHERE metadata.record_id=record.record_id),'[]')
                    || jsonb_build_object(
                      'quality',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.quality_code)
                        FROM production.production_record_quality fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'cost',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.cost_code)
                        FROM production.production_record_cost fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'insurance',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.insurance_code)
                        FROM production.production_record_insurance fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'subsidy',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.subsidy_code)
                        FROM production.production_record_subsidy fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb))::text,'UTF8')),'hex'))
                  ORDER BY record.record_id),'[]'::jsonb)::text AS source_manifest,
                  sum(record.cultivated_area_mu) AS cultivated_area_mu,
                  count(record.cultivated_area_mu) AS cultivated_area_count,
                  sum(record.cultivated_area_mu * record.yield_per_mu_kg) / 1000 AS expected_output_tonnes,
                  count(record.yield_per_mu_kg) AS expected_output_count,
                  sum(record.cultivated_area_mu * record.yield_per_mu_kg)
                    / nullif(sum(record.cultivated_area_mu),0) AS weighted_yield_per_mu_kg
                FROM production.production_record record
                JOIN production.effective_approved_production_record effective
                  ON effective.record_id=record.record_id
                WHERE record.product_code=:product
                  AND record.region_code IN (SELECT code FROM selected_regions)
                  AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.survey_date BETWEEN %s
                  AND (CAST(:cultivar AS varchar) IS NULL OR record.cultivar_code=:cultivar OR EXISTS (
                    SELECT 1 FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id AND metadata.field_code='PROD_CULTIVAR_NAME'
                      AND (metadata.value=:cultivar OR metadata.value=(SELECT cultivar.name
                        FROM platform.cultivar cultivar WHERE cultivar.code=:cultivar
                          AND cultivar.product_code=record.product_code))))
                """.formatted(period);
        String market = regionScope + """
                SELECT count(*) AS approved_count,max(record.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',record.record_id,'sourceVersion',record.version,
                  'reportedAt',record.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(record)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(value) ORDER BY value.field_code)::text
                      FROM market.market_record_core_value value
                      WHERE value.record_id=record.record_id),'[]')
                    || COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.fact_code)::text
                      FROM market.market_record_fact fact
                      WHERE fact.record_id=record.record_id),'[]'),'UTF8')),'hex'))
                  ORDER BY record.record_id),'[]'::jsonb)::text AS source_manifest,
                  avg(record.purchase_base_price) AS average_purchase_price,
                  count(record.purchase_base_price) AS purchase_price_count,
                  avg(record.sale_base_price) AS average_sale_price,
                  count(record.sale_base_price) AS sale_price_count,
                  avg(record.actual_trade_price) AS average_trade_price,
                  count(record.actual_trade_price) AS trade_price_count,
                  sum((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='PURCHASE_VOLUME')) AS purchase_volume,
                  count((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='PURCHASE_VOLUME')) AS purchase_volume_count,
                  sum((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='SALES_VOLUME')) AS sales_volume,
                  count((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='SALES_VOLUME')) AS sales_volume_count,
                  sum((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='ENDING_INVENTORY')) AS ending_inventory,
                  count((SELECT fact.value FROM market.market_record_fact fact
                    WHERE fact.record_id=record.record_id AND fact.fact_code='ENDING_INVENTORY')) AS ending_inventory_count
                FROM market.market_record record
                JOIN market.effective_approved_market_record effective
                  ON effective.record_id=record.record_id
                WHERE record.product_code=:product
                  AND record.region_code IN (SELECT code FROM selected_regions)
                  AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.trade_date BETWEEN %s
                  AND (CAST(:cultivar AS varchar) IS NULL OR EXISTS (
                    SELECT 1 FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id AND value.field_code='MKT_CULTIVAR_NAME'
                      AND (value.value=:cultivar OR value.value=(SELECT cultivar.name
                        FROM platform.cultivar cultivar WHERE cultivar.code=:cultivar
                          AND cultivar.product_code=record.product_code))))
                """.formatted(period);
        String logistics = regionScope + """
                SELECT count(*) AS approved_count,max(event.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',event.event_id,'sourceVersion',event.version,
                  'reportedAt',event.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(event)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.fact_code)::text
                      FROM logistics.route_fact fact WHERE fact.event_id=event.event_id),'[]'),
                    'UTF8')),'hex')) ORDER BY event.event_id),'[]'::jsonb)::text AS source_manifest
                FROM logistics.route_event event
                WHERE event.product_code=:product
                  AND (event.origin_region_code IN (SELECT code FROM selected_regions)
                    OR event.destination_region_code IN (SELECT code FROM selected_regions))
                  AND event.status_code='APPROVED'
                  AND event.survey_period_governance_state='CONFIRMED'
                  AND event.collection_date BETWEEN %s
                  AND CAST(:cultivar AS varchar) IS NULL
                """.formatted(period);
        String supply = regionScope + """
                , ranked_runs AS (
                  SELECT candidate.*,
                    row_number() OVER (
                      PARTITION BY candidate.product_code,candidate.region_code,candidate.period_code
                      ORDER BY candidate.version DESC,candidate.created_at DESC,candidate.calculation_run_id DESC
                    ) AS report_rank
                  FROM supply.calculation_run candidate
                  WHERE candidate.product_code=:product
                    AND candidate.region_code IN (SELECT code FROM selected_regions)
                    AND candidate.result_state='PUBLISHED'
                    AND candidate.temporal_governance_state='CONFIRMED'
                    AND candidate.created_at::date BETWEEN %s
                )
                SELECT count(*) AS approved_count,max(run.created_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',run.calculation_run_id,'sourceVersion',run.version,
                  'reportedAt',run.created_at,
                  'contentSha256',encode(sha256(convert_to(to_jsonb(run)::text,'UTF8')),'hex'))
                  ORDER BY run.calculation_run_id),'[]'::jsonb)::text AS source_manifest,
                  sum(run.total_supply) AS total_supply,
                  count(run.total_supply) AS total_supply_count,
                  sum(run.total_use) AS total_use,
                  count(run.total_use) AS total_use_count,
                  sum(run.adopted_ending_inventory) AS adopted_ending_inventory,
                  count(run.adopted_ending_inventory) AS adopted_ending_inventory_count,
                  sum(run.inventory_reconciliation_difference) AS reconciliation_difference,
                  count(run.inventory_reconciliation_difference) AS reconciliation_difference_count
                FROM ranked_runs run
                WHERE run.report_rank=1
                  AND CAST(:cultivar AS varchar) IS NULL
                """.formatted(period);
        String sql = switch (domain) {
            case "PRODUCTION" -> production;
            case "MARKET" -> market;
            case "LOGISTICS" -> logistics;
            case "SUPPLY" -> supply;
            default -> throw new IllegalArgumentException("unsupported report domain");
        };
        String cultivar = c.cultivarCode() == null || c.cultivarCode().isBlank()
                ? null : c.cultivarCode().strip();
        return jdbc.sql(sql).param("product", c.productCode()).param("region", c.regionCode())
                .param("periodStart", reportPeriod.startsOn()).param("periodEnd", reportPeriod.endsOn())
                .param("cultivar", cultivar)
                .query((row, index) -> new ApprovedDatasetSnapshot(
                        row.getLong("approved_count"),
                        row.getTimestamp("data_cutoff") == null
                                ? null : row.getTimestamp("data_cutoff").toInstant(),
                        row.getString("source_manifest"),
                        businessMetrics(domain, row)))
                .single();
    }
    private record ReportPeriodScope(LocalDate startsOn, LocalDate endsOn, String label) {}
    private record ApprovedDatasetSnapshot(
            long approvedRecordCount, Instant dataCutoff, String sourceManifestJson,
            List<ReportBusinessMetric> businessMetrics) {}

    private static List<ReportBusinessMetric> businessMetrics(String domain, java.sql.ResultSet row)
            throws java.sql.SQLException {
        return switch (domain) {
            case "PRODUCTION" -> List.of(
                    metric("CULTIVATED_AREA", "核定播种面积", row.getBigDecimal("cultivated_area_mu"),
                            "亩", "合计", row.getLong("cultivated_area_count")),
                    metric("WEIGHTED_YIELD_PER_MU", "加权预计单产", row.getBigDecimal("weighted_yield_per_mu_kg"),
                            "公斤/亩", "加权平均", row.getLong("expected_output_count")),
                    metric("EXPECTED_OUTPUT", "预计总产", row.getBigDecimal("expected_output_tonnes"),
                            "吨", "合计", row.getLong("expected_output_count")));
            case "MARKET" -> List.of(
                    metric("AVERAGE_PURCHASE_PRICE", "平均采集对象收购价格", row.getBigDecimal("average_purchase_price"),
                            "元/吨", "平均", row.getLong("purchase_price_count")),
                    metric("AVERAGE_SALE_PRICE", "平均采集对象销售价格", row.getBigDecimal("average_sale_price"),
                            "元/吨", "平均", row.getLong("sale_price_count")),
                    metric("AVERAGE_TRADE_PRICE", "平均实际成交价格", row.getBigDecimal("average_trade_price"),
                            "元/吨", "平均", row.getLong("trade_price_count")),
                    metric("PURCHASE_VOLUME", "采购量", row.getBigDecimal("purchase_volume"),
                            "吨", "合计", row.getLong("purchase_volume_count")),
                    metric("SALES_VOLUME", "销售量", row.getBigDecimal("sales_volume"),
                            "吨", "合计", row.getLong("sales_volume_count")),
                    metric("ENDING_INVENTORY", "期末库存", row.getBigDecimal("ending_inventory"),
                            "吨", "合计", row.getLong("ending_inventory_count")));
            case "SUPPLY" -> List.of(
                    metric("TOTAL_SUPPLY", "总供给", row.getBigDecimal("total_supply"),
                            "吨", "合计", row.getLong("total_supply_count")),
                    metric("TOTAL_USE", "总消费", row.getBigDecimal("total_use"),
                            "吨", "合计", row.getLong("total_use_count")),
                    metric("ADOPTED_ENDING_INVENTORY", "采用期末库存", row.getBigDecimal("adopted_ending_inventory"),
                            "吨", "合计", row.getLong("adopted_ending_inventory_count")),
                    metric("RECONCILIATION_DIFFERENCE", "库存核对差异", row.getBigDecimal("reconciliation_difference"),
                            "吨", "合计", row.getLong("reconciliation_difference_count")));
            default -> List.of();
        };
    }

    private static ReportBusinessMetric metric(
            String code, String label, BigDecimal value, String unit, String aggregation, long sourceCount) {
        if (value == null || sourceCount < 1) {
            return new ReportBusinessMetric(code, label, null, unit, aggregation, 0, "暂无审核数据");
        }
        return new ReportBusinessMetric(code, label, decimal(value), unit, aggregation, sourceCount, null);
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }
    private static List<ReportDefinitionView> comprehensiveDefinitions() {
        return List.of(
                comprehensiveDefinition("COMPREHENSIVE_DAILY"),
                comprehensiveDefinition("COMPREHENSIVE_WEEKLY"),
                comprehensiveDefinition("COMPREHENSIVE_MONTHLY"));
    }

    private static ReportDefinitionView comprehensiveDefinition(String code) {
        String frequency = switch (code) {
            case "COMPREHENSIVE_DAILY" -> "DAILY";
            case "COMPREHENSIVE_WEEKLY" -> "WEEKLY";
            case "COMPREHENSIVE_MONTHLY" -> "MONTHLY";
            default -> null;
        };
        if (frequency == null) return null;
        String name = switch (frequency) {
            case "DAILY" -> "综合经营日报";
            case "WEEKLY" -> "综合经营周报";
            default -> "综合经营月报";
        };
        return new ReportDefinitionView(code, name, "COMPREHENSIVE", "MANAGEMENT", frequency, 1,
                List.of(
                        new ReportDefinitionView.Section("REGIONAL_SNAPSHOT", "区域行情快照", 10),
                        new ReportDefinitionView.Section("MARKET_DETAILS", "市场价格与购销", 20),
                        new ReportDefinitionView.Section("PRODUCTION_CONDITIONS", "产情与作物对比", 30),
                        new ReportDefinitionView.Section("LOGISTICS_FLOW", "物流流向与运力", 40),
                        new ReportDefinitionView.Section("SUPPLY_BALANCE", "供需与库存", 50),
                        new ReportDefinitionView.Section("DATA_GAPS", "数据覆盖与风险", 60),
                        new ReportDefinitionView.Section("TODAY_FOCUS", "今日关注与追溯", 70)));
    }

    private static boolean isComprehensive(String definitionCode) {
        return comprehensiveDefinition(definitionCode) != null;
    }

    private String parameters(ReportPreviewCommand c) {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("definitionCode", c.definitionCode());
        if (isComprehensive(c.definitionCode())) {
            ArrayNode productScope = parameters.putArray("productScope");
            REPORT_PRODUCTS.forEach(productScope::add);
            ArrayNode domainScope = parameters.putArray("domainScope");
            REPORT_DOMAINS.forEach(domain -> domainScope.add(domain.code()));
        } else {
            parameters.put("productCode", c.productCode());
            if (c.cultivarCode() != null && !c.cultivarCode().isBlank()) {
                parameters.put("cultivarCode", c.cultivarCode().strip());
            }
        }
        parameters.put("regionLevel", c.regionLevel());
        parameters.put("regionCode", c.regionCode());
        parameters.put("periodCode", c.periodCode());
        return parameters.toString();
    }
    private void audit(String type,String id,String action,String actor,Instant now,String detail){jdbc.sql("INSERT INTO reporting.report_audit_event(audit_event_id,aggregate_type,aggregate_id,action_code,actor,occurred_at,detail) VALUES(CAST(:event AS uuid),:type,CAST(:id AS uuid),:action,:actor,:now,CAST(:detail AS jsonb))").param("event",UUID.randomUUID().toString()).param("type",type).param("id",id).param("action",action).param("actor",actor).param("now",Timestamp.from(now)).param("detail",detail).update();}
    private ReportPreviewView view(
            String id, String definition, String dataset, String content, Instant expires, long version) {
        try {
            JsonNode root = json.readTree(content);
            List<ReportPreviewView.Section> sections = new ArrayList<>();
            for (JsonNode node : root.path("sections")) {
                sections.add(new ReportPreviewView.Section(
                        node.path("code").asText(), node.path("title").asText(), node.path("body").asText()));
            }
            List<ReportPreviewView.Line> lines = new ArrayList<>();
            lines.add(new ReportPreviewView.Line("核定数据条数",
                    root.path("approvedRecordCount").asText(), "审核通过且当前有效"));
            lines.add(new ReportPreviewView.Line("报告范围",
                    root.path("scopeLabel").asText(), "当前所选业务范围"));
            lines.add(new ReportPreviewView.Line("数据截止时间",
                    root.path("dataCutoff").asText(), "所采用审核数据的最晚时间"));
            for (JsonNode metric : root.path("businessMetrics")) {
                String value = metric.path("value").isNull()
                        ? "暂无审核数据"
                        : metric.path("value").asText() + " " + metric.path("unit").asText();
                String note = metric.path("value").isNull()
                        ? metric.path("missingReason").asText("暂无审核数据")
                        : "采用 " + metric.path("sourceCount").asText() + " 条审核数据";
                lines.add(new ReportPreviewView.Line(metric.path("label").asText(), value, note));
            }
            List<ReportPreviewView.Product> products = new ArrayList<>();
            for (JsonNode product : root.path("products")) {
                List<ReportPreviewView.Domain> domains = new ArrayList<>();
                for (JsonNode domain : product.path("domains")) {
                    List<ReportPreviewView.Line> domainMetrics = new ArrayList<>();
                    for (JsonNode metric : domain.path("metrics")) {
                        domainMetrics.add(new ReportPreviewView.Line(
                                metric.path("label").asText(), metric.path("value").asText(),
                                metric.path("note").asText()));
                    }
                    domains.add(new ReportPreviewView.Domain(
                            domain.path("code").asText(), domain.path("label").asText(),
                            domain.path("approvedRecordCount").asLong(),
                            domain.path("dataCutoff").isNull()
                                    ? "暂无审核数据" : domain.path("dataCutoff").asText(),
                            List.copyOf(domainMetrics)));
                }
                products.add(new ReportPreviewView.Product(
                        product.path("code").asText(), product.path("label").asText(), List.copyOf(domains)));
            }
            return new ReportPreviewView(id, definition, dataset, root.path("title").asText(),
                    root.path("dataCutoffLabel").asText(), List.copyOf(lines), List.copyOf(sections),
                    List.copyOf(products), expires, version, false);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
    record DefinitionRow(long id,String code,String name,String businessDomain,String businessSubtype,String frequencyCode,int versionNo){}
    private record DomainScope(String code, String label) {}
    @Override public ReportExportView persistExport(ReportExportPersistence e){
        if (!exists("SELECT 1 FROM reporting.report_output_format WHERE format_code=:code AND enabled", e.formatCode())) throw new IllegalArgumentException("format");
        String id=UUID.randomUUID().toString();
        int written;
        try { written=jdbc.sql("""
                INSERT INTO reporting.report_export_task(export_task_id,preview_id,format_code,status_code,filename,content_type,content_digest,content,requested_by,requested_at)
                VALUES(CAST(:id AS uuid),CAST(:preview AS uuid),:format,'COMPLETED',:filename,:type,:digest,:content,:actor,:now)""")
            .param("id",id).param("preview",e.previewId()).param("format",e.formatCode()).param("filename",e.filename()).param("type",e.contentType()).param("digest",e.contentDigest()).param("content",new SqlParameterValue(Types.BINARY,e.content())).param("actor",e.actor()).param("now",Timestamp.from(e.now())).update(); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) { throw new IllegalStateException("report export persistence failed", exception); }
        if(written!=1) throw new IllegalArgumentException("preview");
        audit("EXPORT",id,"EXPORTED",e.actor(),e.now(),"{\"previewId\":\""+e.previewId()+"\",\"formatCode\":\""+e.formatCode()+"\"}");
        return new ReportExportView(id,e.previewId(),e.formatCode(),e.filename(),e.contentType(),e.now());
    }
    @Override public ReportExportContent findExportContent(String exportTaskId) {
        return jdbc.sql("""
                SELECT export_task_id::text, filename, content_type, content
                FROM reporting.report_export_task
                WHERE export_task_id=CAST(:id AS uuid) AND status_code='COMPLETED'
                """).param("id", exportTaskId).query((row, index) -> new ReportExportContent(
                row.getString(1), row.getString(2), row.getString(3), row.getBytes(4))).optional().orElse(null);
    }
    @Override public String findExportRegion(String exportTaskId) {
        return jdbc.sql("""
                SELECT dataset.region_code FROM reporting.report_export_task export
                JOIN reporting.report_preview preview ON preview.preview_id = export.preview_id
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id = preview.dataset_id
                WHERE export.export_task_id = CAST(:id AS uuid)
                """).param("id", exportTaskId).query(String.class).optional().orElse(null);
    }
    @Override public ReportPublicationView persistPublication(ReportPublicationPersistence p){
        Long current=jdbc.sql("SELECT version FROM reporting.report_preview WHERE preview_id=CAST(:id AS uuid) FOR UPDATE").param("id",p.previewId()).query(Long.class).optional().orElse(null);
        if(current==null || current!=p.expectedVersion()) throw new IllegalStateException("version");
        boolean exportMatches=jdbc.sql("SELECT 1 FROM reporting.report_export_task WHERE export_task_id=CAST(:id AS uuid) AND preview_id=CAST(:preview AS uuid) AND status_code='COMPLETED'").param("id",p.exportTaskId()).param("preview",p.previewId()).query(Integer.class).optional().isPresent();
        if(!exportMatches) throw new IllegalArgumentException("export");
        String id=UUID.randomUUID().toString();
        try { jdbc.sql("INSERT INTO reporting.report_publication(publication_id,preview_id,export_task_id,published_by,published_at,version) VALUES(CAST(:id AS uuid),CAST(:preview AS uuid),CAST(:export AS uuid),:actor,:now,:version)").param("id",id).param("preview",p.previewId()).param("export",p.exportTaskId()).param("actor",p.actor()).param("now",Timestamp.from(p.now())).param("version",current+1).update(); }
        catch (RuntimeException exception) { throw new IllegalStateException("publication",exception); }
        audit("PUBLICATION",id,"PUBLISHED",p.actor(),p.now(),"{\"previewId\":\""+p.previewId()+"\",\"exportTaskId\":\""+p.exportTaskId()+"\"}");
        return new ReportPublicationView(id,p.previewId(),p.exportTaskId(),p.now(),current+1);
    }
}
