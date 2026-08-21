package com.cofco.qiqihar.graintrade.analysis.infrastructure;

import com.cofco.qiqihar.graintrade.analysis.application.AnalysisCoverage;
import com.cofco.qiqihar.graintrade.analysis.application.AnalysisLineage;
import com.cofco.qiqihar.graintrade.analysis.application.LogisticsAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.application.MarketAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisRepository;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisSnapshot;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableInventoryBreakdown;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableMetric;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableSupplyView;
import com.cofco.qiqihar.graintrade.analysis.application.ProductionAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import com.cofco.qiqihar.graintrade.analysis.domain.InventoryPositionObservation;
import com.cofco.qiqihar.graintrade.analysis.domain.InventoryPositionSelection;
import com.cofco.qiqihar.graintrade.analysis.domain.InventoryPositionSelector;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableQuantityInput;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculation;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculator;
import com.cofco.qiqihar.graintrade.analysis.domain.ProductionSourceBalance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcObservableAnalysisRepository implements ObservableAnalysisRepository {
    static final String METHODOLOGY_VERSION = "OBSERVABLE_ANALYSIS_V3";
    private static final int SCALE = 4;
    private static final List<FactMetricDefinition> QUALITY_FACTS = List.of(
            new FactMetricDefinition("MOISTURE", "水分", "%"),
            new FactMetricDefinition("TEST_WEIGHT", "容重", "克/升"),
            new FactMetricDefinition("IMPURITY", "杂质", "%"),
            new FactMetricDefinition("IMPERFECT_GRAIN", "不完善粒", "%"),
            new FactMetricDefinition("MILDEW", "霉变", "%"),
            new FactMetricDefinition("TOXIN", "毒素", "%"),
            new FactMetricDefinition("PROTEIN", "蛋白", "%"),
            new FactMetricDefinition("OIL_YIELD", "出油率", "%"),
            new FactMetricDefinition("MILLING_YIELD", "出米率", "%"),
            new FactMetricDefinition("BROWN_RICE_YIELD", "出糙率", "%"));
    private static final List<FactMetricDefinition> COST_FACTS = List.of(
            new FactMetricDefinition("LAND_RENT", "地租", "元/亩"),
            new FactMetricDefinition("SEED_COST", "种子费用", "元/亩"),
            new FactMetricDefinition("PESTICIDE_COST", "农药费用", "元/亩"),
            new FactMetricDefinition("FERTILIZER_COST", "化肥费用", "元/亩"),
            new FactMetricDefinition("IRRIGATION_COST", "灌溉费用", "元/亩"),
            new FactMetricDefinition("LABOR_COST", "人工费用", "元/亩"),
            new FactMetricDefinition("MACHINERY_COST", "机耕费用", "元/亩"),
            new FactMetricDefinition("OTHER_COST", "其他成本", "元/亩"));
    private static final String SCOPE = """
            WITH RECURSIVE requested_scope(code) AS (
              SELECT code FROM platform.region
              WHERE :region=:allAuthorizedRegions OR code=:region
              UNION
              SELECT child.code FROM platform.region child
              JOIN requested_scope parent ON child.parent_code=parent.code
            ), authorized_scope(code) AS (
              SELECT code FROM platform.region
              WHERE :unrestricted OR code IN (:authorizedRegions)
              UNION
              SELECT child.code FROM platform.region child
              JOIN authorized_scope parent ON child.parent_code=parent.code
            ), scope(code) AS (
              SELECT member.region_code
              FROM platform.monitoring_scope_region member
              JOIN requested_scope requested ON requested.code=member.region_code
              JOIN authorized_scope authorized ON authorized.code=member.region_code
              WHERE member.scope_code='FORMAL_BUSINESS' AND member.included
            )
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public JdbcObservableAnalysisRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    JdbcObservableAnalysisRepository(DataSource dataSource, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
    }

    @Override
    public boolean knownProduct(String productCode) {
        return exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)", productCode);
    }

    @Override
    public boolean knownRegion(String regionCode) {
        return exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)", regionCode);
    }

    @Override
    public boolean knownCultivar(String productCode, String cultivarCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM platform.cultivar
                          WHERE product_code=:product AND code=:cultivar)
                        """).param("product", productCode).param("cultivar", cultivarCode)
                .query(Boolean.class).single());
    }

    @Override
    public boolean knownSubjectType(String domain, String subjectTypeCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM platform.object_type
                          WHERE business_domain=:domain AND code=:subjectType)
                        """).param("domain", domain).param("subjectType", subjectTypeCode)
                .query(Boolean.class).single());
    }

    @Override
    public boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes) {
        if (authorizedRegionCodes.contains("*")) return knownRegion(regionCode);
        return Boolean.TRUE.equals(jdbc.sql("""
                        WITH RECURSIVE descendants(code,parent_code) AS (
                          SELECT code,parent_code FROM platform.region WHERE code IN (:authorizedRegions)
                          UNION
                          SELECT child.code,child.parent_code FROM platform.region child
                          JOIN descendants parent ON child.parent_code=parent.code
                        ), ancestors(code,parent_code) AS (
                          SELECT code,parent_code FROM platform.region WHERE code IN (:authorizedRegions)
                          UNION
                          SELECT parent.code,parent.parent_code FROM platform.region parent
                          JOIN ancestors child ON child.parent_code=parent.code
                        )
                        SELECT EXISTS(
                          SELECT 1 FROM descendants WHERE code=:region
                          UNION ALL
                          SELECT 1 FROM ancestors WHERE code=:region)
                        """).param("authorizedRegions", authorizedRegionCodes).param("region", regionCode)
                .query(Boolean.class).single());
    }

    @Override
    public ObservableAnalysisSnapshot load(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes) {
        Set<String> effectiveAuthorization = normalizeAuthorization(authorizedRegionCodes);
        List<ProductionRow> production = selectLatestProduction(
                productionRows(scope, effectiveAuthorization), scope.surveyMonth());
        List<MarketRow> rawMarket = marketRows(scope, effectiveAuthorization);
        List<MarketRow> market = selectLatestMarket(
                rawMarket);
        List<LogisticsRow> logistics = selectLatestLogistics(
                logisticsRows(scope, effectiveAuthorization));

        List<ProductionSourceBalance> sourceBalances = production.stream()
                .map(this::productionBalance).toList();
        SupplyAssembly supply = supply(
                scope, effectiveAuthorization, production, rawMarket, logistics);
        List<ObservableMetric> productionMetrics = productionMetrics(production);
        List<ObservableMetric> marketMetrics = marketMetrics(
                market, supply.view().inventory());
        List<ObservableMetric> logisticsMetrics = logisticsMetrics(logistics);

        Map<String, AnalysisLineage> adoptedLineage = new LinkedHashMap<>();
        production.forEach(row -> addLineage(adoptedLineage, row.lineage()));
        market.forEach(row -> addLineage(adoptedLineage, row.lineage()));
        logistics.forEach(row -> addLineage(adoptedLineage, row.lineage()));
        supply.inventoryLineage().forEach(item -> addLineage(adoptedLineage, item));
        List<AnalysisLineage> lineage = new ArrayList<>(adoptedLineage.values());
        lineage.sort(Comparator.comparing(AnalysisLineage::sourceDomain)
                .thenComparing(AnalysisLineage::recordId));

        AnalysisQualityState quality = combinedQuality(
                lineage, sourceBalances, supply.view().calculation());
        List<String> collectedIssues = new ArrayList<>(supply.view().calculation().issues());
        sourceBalances.forEach(balance -> collectedIssues.addAll(balance.issues()));
        List<String> issues = collectedIssues.stream().distinct().sorted().toList();
        List<String> blocking = quality == AnalysisQualityState.BLOCKED ? issues : List.of();
        List<String> warnings = quality == AnalysisQualityState.BLOCKED ? List.of() : issues;
        OffsetDateTime cutoff = lineage.stream().map(AnalysisLineage::approvedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        Set<String> subjectLabels = new LinkedHashSet<>();
        Set<String> regionLabels = new LinkedHashSet<>();
        lineage.forEach(item -> {
            subjectLabels.add(item.subjectLabel());
            regionLabels.add(item.regionLabel());
        });
        int excluded = excludedRecordCount(scope, effectiveAuthorization);
        AnalysisCoverage coverage = new AnalysisCoverage(
                lineage.size(), subjectLabels.size(), regionLabels.size(), excluded,
                pendingReviewRecordCount(scope, effectiveAuthorization));

        return ObservableAnalysisSnapshot.create(
                scope, METHODOLOGY_VERSION, cutoff, OffsetDateTime.now(clock), quality,
                blocking, warnings, coverage,
                new ProductionAnalysisView(productionMetrics, sourceBalances),
                new MarketAnalysisView(marketMetrics),
                new LogisticsAnalysisView(logisticsMetrics),
                supply.view(), lineage);
    }

    private static void addLineage(
            Map<String, AnalysisLineage> target, AnalysisLineage lineage) {
        String key = lineage.sourceDomain() + "|" + lineage.recordId();
        AnalysisLineage existing = target.get(key);
        if (existing == null) {
            target.put(key, lineage);
            return;
        }
        LinkedHashSet<String> factCodes = new LinkedHashSet<>(existing.factCodes());
        factCodes.addAll(lineage.factCodes());
        target.put(key, new AnalysisLineage(
                existing.sourceDomain(), existing.recordId(), existing.recordVersion(),
                List.copyOf(factCodes), existing.subjectLabel(), existing.regionLabel(),
                existing.periodLabel(), existing.approvedAt()));
    }

    private List<ProductionRow> productionRows(
            ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        Map<String, ProductionRowBuilder> rows = new LinkedHashMap<>();
        scoped(SCOPE + """
                SELECT record.record_id,record.version,record.region_code,region.name region_name,
                       record.object_type_code,object_type.name object_type_name,
                       record.cultivar_code,identity.business_identity,
                       record.survey_date,record.survey_year,record.survey_month,
                       record.survey_period_precision,record.sample_point_id,
                       record.cultivated_area_mu,record.yield_per_mu_kg,record.estimated_output_kg,
                       COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at,
                       fact.fact_code,fact.fact_value
                FROM production.production_record record
                JOIN production.production_record_business_identity identity
                  ON identity.record_id=record.record_id
                JOIN platform.region region ON region.code=record.region_code
                JOIN platform.object_type object_type ON object_type.code=record.object_type_code
                LEFT JOIN LATERAL (
                  SELECT metadata.field_code fact_code,metadata.value fact_value
                  FROM production.production_record_submission_metadata metadata
                  WHERE metadata.record_id=record.record_id
                  UNION ALL
                  SELECT quality.quality_code,quality.value::text
                  FROM production.production_record_quality quality
                  WHERE quality.record_id=record.record_id
                  UNION ALL
                  SELECT cost.cost_code,cost.value::text
                  FROM production.production_record_cost cost
                  WHERE cost.record_id=record.record_id
                  UNION ALL
                  SELECT insurance.insurance_code,insurance.value::text
                  FROM production.production_record_insurance insurance
                  WHERE insurance.record_id=record.record_id
                  UNION ALL
                  SELECT subsidy.subsidy_code,subsidy.value::text
                  FROM production.production_record_subsidy subsidy
                  WHERE subsidy.record_id=record.record_id
                ) fact ON true
                LEFT JOIN LATERAL (
                  SELECT max(event.occurred_at) approved_at
                  FROM platform.business_event_outbox event
                  WHERE event.aggregate_type='PRODUCTION_RECORD'
                    AND event.aggregate_id=record.record_id
                    AND event.action_code='PRODUCTION_RECORD_APPROVED'
                ) approval ON true
                WHERE record.product_code=:product AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.survey_year=:year
                  AND (:month IS NULL OR record.survey_month=:month)
                  AND (:cultivar IS NULL OR record.cultivar_code=:cultivar)
                  AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                  AND record.region_code IN(SELECT code FROM scope)
                ORDER BY record.record_id,fact.fact_code
                """, scope, authorizedRegions).query((result, ignored) -> {
                    String id = result.getString("record_id");
                    ProductionRowBuilder row = rows.get(id);
                    if (row == null) {
                        row = new ProductionRowBuilder(
                                id, result.getLong("version"), result.getString("region_code"),
                                result.getString("region_name"), result.getString("object_type_code"),
                                result.getString("object_type_name"), result.getString("cultivar_code"),
                                result.getString("business_identity"),
                                result.getObject("survey_date", LocalDate.class),
                                result.getInt("survey_year"), (Integer) result.getObject("survey_month"),
                                result.getString("survey_period_precision"), result.getString("sample_point_id"),
                                result.getBigDecimal("cultivated_area_mu"), result.getBigDecimal("yield_per_mu_kg"),
                                result.getBigDecimal("estimated_output_kg"),
                                result.getObject("approved_at", OffsetDateTime.class));
                        rows.put(id, row);
                    }
                    String code = result.getString("fact_code");
                    if (code != null) row.fact(code, result.getString("fact_value"));
                    return id;
                }).list();
        return rows.values().stream().map(ProductionRowBuilder::build).toList();
    }

    private List<MarketRow> marketRows(
            ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        Map<String, MarketRowBuilder> rows = new LinkedHashMap<>();
        scoped(SCOPE + """
                SELECT record.record_id,record.version,record.region_code,region.name region_name,
                       record.object_type_code,object_type.name object_type_name,
                       record.party_id,party.current_name party_name,record.sample_point_id,
                       identity.business_identity,location.sample_name,location.sample_contact,
                       location.latitude,location.longitude,
                       record.trade_date,record.survey_year,record.survey_month,record.survey_period_precision,
                       record.trade_direction,record.purchase_base_price,record.sale_base_price,
                       record.actual_trade_price,record.carriage_board_amount,
                       record.packaging_amount,record.freight_amount,record.packaging_form,
                       COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at,
                       fact.fact_code,fact.value fact_value
                FROM market.market_record record
                JOIN market.market_record_business_identity identity
                  ON identity.record_id=record.record_id
                JOIN platform.region region ON region.code=record.region_code
                JOIN platform.object_type object_type ON object_type.code=record.object_type_code
                LEFT JOIN market.business_party party ON party.party_id=record.party_id
                LEFT JOIN market.market_record_fact fact ON fact.record_id=record.record_id
                LEFT JOIN LATERAL (
                  SELECT
                    max(value.value) FILTER (WHERE value.field_code='MKT_SAMPLE_NAME') sample_name,
                    max(value.value) FILTER (WHERE value.field_code='MKT_SAMPLE_CONTACT') sample_contact,
                    max(value.value) FILTER (WHERE value.field_code='MKT_SAMPLE_LATITUDE') latitude,
                    max(value.value) FILTER (WHERE value.field_code='MKT_SAMPLE_LONGITUDE') longitude
                  FROM market.market_record_core_value value
                  WHERE value.record_id=record.record_id
                ) location ON true
                LEFT JOIN LATERAL (
                  SELECT max(event.occurred_at) approved_at
                  FROM platform.business_event_outbox event
                  WHERE event.aggregate_type='MARKET_RECORD'
                    AND event.aggregate_id=record.record_id
                    AND event.action_code='MARKET_RECORD_APPROVED'
                ) approval ON true
                WHERE record.product_code=:product AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.survey_year=:year
                  AND (:month IS NULL OR record.survey_month=:month)
                  AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                  AND record.region_code IN(SELECT code FROM scope)
                ORDER BY record.record_id,fact.fact_code
                """, scope, authorizedRegions).query((result, ignored) -> {
                    String id = result.getString("record_id");
                    MarketRowBuilder row = rows.get(id);
                    if (row == null) {
                        row = new MarketRowBuilder(
                                id, result.getLong("version"), result.getString("region_code"),
                                result.getString("region_name"), result.getString("object_type_code"),
                                result.getString("object_type_name"), result.getString("party_id"),
                                result.getString("party_name"), result.getString("sample_point_id"),
                                result.getString("business_identity"), result.getString("sample_name"),
                                result.getString("sample_contact"), result.getBigDecimal("latitude"),
                                result.getBigDecimal("longitude"),
                                result.getObject("trade_date", LocalDate.class), result.getInt("survey_year"),
                                (Integer) result.getObject("survey_month"), result.getString("survey_period_precision"),
                                result.getString("trade_direction"), result.getBigDecimal("purchase_base_price"),
                                result.getBigDecimal("sale_base_price"), result.getBigDecimal("actual_trade_price"),
                                result.getBigDecimal("carriage_board_amount"),
                                result.getBigDecimal("packaging_amount"),
                                result.getBigDecimal("freight_amount"), result.getString("packaging_form"),
                                result.getObject("approved_at", OffsetDateTime.class));
                        rows.put(id, row);
                    }
                    String code = result.getString("fact_code");
                    if (code != null) row.fact(code, result.getBigDecimal("fact_value"));
                    return id;
                }).list();
        return rows.values().stream().map(MarketRowBuilder::build).toList();
    }

    private List<LogisticsRow> logisticsRows(
            ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        Map<String, LogisticsRowBuilder> rows = new LinkedHashMap<>();
        scoped(SCOPE + """
                SELECT event.event_id::text record_id,event.version,event.business_region_code region_code,
                       region.name region_name,event.collection_date,event.survey_year,event.survey_month,
                       event.survey_period_precision,event.direction_code,event.origin_node_code,
                       event.destination_node_code,event.source_organization,
                       COALESCE(approval.approved_at,event.updated_at,event.reported_at) approved_at,
                       fact.fact_code,fact.value fact_value,fact.unit_code
                FROM logistics.route_event event
                JOIN platform.region region ON region.code=event.business_region_code
                LEFT JOIN logistics.route_fact fact ON fact.event_id=event.event_id
                LEFT JOIN LATERAL (
                  SELECT max(outbox.occurred_at) approved_at
                  FROM platform.business_event_outbox outbox
                  WHERE outbox.aggregate_type='LOGISTICS_RECORD'
                    AND outbox.aggregate_id=event.event_id::text
                    AND outbox.action_code='LOGISTICS_RECORD_APPROVED'
                ) approval ON true
                WHERE event.product_code=:product AND event.status_code='APPROVED'
                  AND event.survey_period_governance_state='CONFIRMED'
                  AND event.survey_year=:year
                  AND (:month IS NULL OR event.survey_month=:month)
                  AND event.direction_code IN('INFLOW','OUTFLOW')
                  AND event.business_region_code IN(SELECT code FROM scope)
                ORDER BY event.event_id,fact.fact_code
                """, scope, authorizedRegions).query((result, ignored) -> {
                    String id = result.getString("record_id");
                    LogisticsRowBuilder row = rows.get(id);
                    if (row == null) {
                        row = new LogisticsRowBuilder(
                                id, result.getLong("version"), result.getString("region_code"),
                                result.getString("region_name"), result.getObject("collection_date", LocalDate.class),
                                result.getInt("survey_year"), (Integer) result.getObject("survey_month"),
                                result.getString("survey_period_precision"), result.getString("direction_code"),
                                result.getString("origin_node_code"), result.getString("destination_node_code"),
                                result.getString("source_organization"),
                                result.getObject("approved_at", OffsetDateTime.class));
                        rows.put(id, row);
                    }
                    String code = result.getString("fact_code");
                    if (code != null) row.fact(code, result.getBigDecimal("fact_value"));
                    return id;
                }).list();
        return rows.values().stream().map(LogisticsRowBuilder::build).toList();
    }

    private JdbcClient.StatementSpec scoped(
            String sql, ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        return jdbc.sql(sql).param("region", scope.regionCode())
                .param("allAuthorizedRegions", ObservableAnalysisScope.ALL_AUTHORIZED_REGIONS)
                .param("unrestricted", authorizedRegions.contains("*"))
                .param("authorizedRegions", authorizedRegions)
                .param("product", scope.productCode()).param("year", scope.surveyYear())
                .param("month", scope.surveyMonth(), Types.INTEGER)
                .param("cultivar", scope.cultivarCode(), Types.VARCHAR)
                .param("subjectType", scope.subjectTypeCode(), Types.VARCHAR);
    }

    private List<ProductionRow> selectLatestProduction(List<ProductionRow> rows, Integer month) {
        Map<String, ProductionRow> selected = new LinkedHashMap<>();
        rows.forEach(row -> selected.merge(row.businessKey(), row,
                (left, right) -> productionOrder(month).compare(left, right) >= 0 ? left : right));
        return selected.values().stream().sorted(Comparator.comparing(ProductionRow::recordId)).toList();
    }

    private static Comparator<ProductionRow> productionOrder(Integer month) {
        return Comparator.comparingInt((ProductionRow row) ->
                        month == null && "YEAR".equals(row.periodPrecision()) ? 1 : 0)
                .thenComparing(ProductionRow::surveyDate)
                .thenComparingLong(ProductionRow::version)
                .thenComparing(ProductionRow::recordId);
    }

    private List<MarketRow> selectLatestMarket(List<MarketRow> rows) {
        return latest(rows, MarketRow::businessKey,
                Comparator.comparingLong(MarketRow::version).thenComparing(MarketRow::recordId));
    }

    private List<LogisticsRow> selectLatestLogistics(List<LogisticsRow> rows) {
        return latest(rows, LogisticsRow::businessKey,
                Comparator.comparingLong(LogisticsRow::version).thenComparing(LogisticsRow::recordId));
    }

    private static <T> List<T> latest(
            List<T> rows, Function<T, String> key, Comparator<T> order) {
        Map<String, T> selected = new LinkedHashMap<>();
        rows.forEach(row -> selected.merge(key.apply(row), row,
                (left, right) -> order.compare(left, right) >= 0 ? left : right));
        return List.copyOf(selected.values());
    }

    private ProductionSourceBalance productionBalance(ProductionRow row) {
        return ObservableSupplyCalculator.productionSourceBalance(
                row.decimal("PROD_OPENING_INVENTORY"), row.areaMu(), row.yieldPerMuKg(),
                row.decimal("PROD_SALES_VOLUME"), row.decimal("PROD_SELF_USE"),
                row.decimal("PROD_ENDING_INVENTORY"));
    }

    private List<ObservableMetric> productionMetrics(List<ProductionRow> rows) {
        BigDecimal area = sum(rows, ProductionRow::areaMu);
        BigDecimal output = sum(rows, row -> tonnes(row.outputKg()));
        BigDecimal weightedYield = area == null || area.signum() == 0 || output == null ? null
                : output.multiply(new BigDecimal("1000")).divide(area, SCALE, RoundingMode.HALF_UP);
        BigDecimal harvestArea = sumFact(rows, "PROD_HARVEST_AREA_MU");
        BigDecimal affectedArea = sumFact(rows, "PROD_AFFECTED_AREA_MU");
        BigDecimal intendedArea = sumFact(rows, "PROD_INTENDED_AREA_MU");
        List<ObservableMetric> metrics = new ArrayList<>();
        addMetric(metrics, "CULTIVATED_AREA", "核定播种面积", area, "亩", "SUM", rows.size());
        addMetric(metrics, "HARVEST_AREA", "预计收获面积", harvestArea, "亩", "SUM",
                countFact(rows, "PROD_HARVEST_AREA_MU"));
        addMetric(metrics, "WEIGHTED_YIELD_PER_MU", "加权预计单产", weightedYield,
                "公斤/亩", "WEIGHTED_AVERAGE", rows.size());
        addMetric(metrics, "EXPECTED_OUTPUT", "预计总产", output, "吨", "SUM", rows.size());
        addMetric(metrics, "EXPECTED_HARVEST_RATE", "预计收获率",
                percentage(harvestArea, area), "%", "RATIO",
                pairedProductionCount(rows, "PROD_HARVEST_AREA_MU"));
        addMetric(metrics, "AFFECTED_AREA", "灾损面积", affectedArea, "亩", "SUM",
                countFact(rows, "PROD_AFFECTED_AREA_MU"));
        addMetric(metrics, "AFFECTED_AREA_RATE", "灾损面积占比",
                percentage(affectedArea, area), "%", "RATIO",
                pairedProductionCount(rows, "PROD_AFFECTED_AREA_MU"));
        addProductionQualityMetrics(metrics, rows);
        addProductionCostMetrics(metrics, rows);
        addMetric(metrics, "INSURANCE_AMOUNT", "保险金额", sumFact(rows, "INSURANCE_AMOUNT"),
                "元", "SUM", countFact(rows, "INSURANCE_AMOUNT"));
        addMetric(metrics, "SUBSIDY_AMOUNT", "补贴金额", sumFact(rows, "SUBSIDY_AMOUNT"),
                "元", "SUM", countFact(rows, "SUBSIDY_AMOUNT"));
        addMetric(metrics, "INTENDED_AREA", "下年度意向面积", intendedArea, "亩", "SUM",
                countFact(rows, "PROD_INTENDED_AREA_MU"));
        addMetric(metrics, "INTENDED_AREA_CHANGE", "面积调整量",
                difference(intendedArea, area), "亩", "DIFFERENCE",
                pairedProductionCount(rows, "PROD_INTENDED_AREA_MU"));
        addMetric(metrics, "INTENDED_AREA_CHANGE_RATE", "面积调整比例",
                percentage(difference(intendedArea, area), area), "%", "RATIO",
                pairedProductionCount(rows, "PROD_INTENDED_AREA_MU"));
        return List.copyOf(metrics);
    }

    private List<ObservableMetric> marketMetrics(
            List<MarketRow> rows, ObservableInventoryBreakdown inventory) {
        List<ObservableMetric> metrics = new ArrayList<>();
        addMetric(metrics, "AVERAGE_PURCHASE_PRICE", "平均采集对象收购价格",
                average(rows, MarketRow::purchaseBasePrice), "元/吨", "AVERAGE",
                count(rows, MarketRow::purchaseBasePrice));
        addMetric(metrics, "AVERAGE_SALE_PRICE", "平均采集对象销售价格",
                average(rows, MarketRow::saleBasePrice), "元/吨", "AVERAGE",
                count(rows, MarketRow::saleBasePrice));
        addMetric(metrics, "AVERAGE_PURCHASE_SALE_SPREAD", "平均购销价差",
                averagePurchaseSaleSpread(rows), "元/吨", "AVERAGE",
                pairedMarketPriceCount(rows));
        addMetric(metrics, "AVERAGE_CARRIAGE_BOARD_AMOUNT", "平均车板组成",
                average(rows, MarketRow::carriageBoardAmount), "元/吨", "AVERAGE",
                count(rows, MarketRow::carriageBoardAmount));
        addMetric(metrics, "AVERAGE_PACKAGING_AMOUNT", "平均包装组成",
                average(rows, MarketRow::packagingAmount), "元/吨", "AVERAGE",
                count(rows, MarketRow::packagingAmount));
        addMetric(metrics, "AVERAGE_FREIGHT_AMOUNT", "平均运费组成",
                average(rows, MarketRow::freightAmount), "元/吨", "AVERAGE",
                count(rows, MarketRow::freightAmount));
        addMetric(metrics, "PURCHASE_VOLUME", "采购量", sumMarketFact(rows, "PURCHASE_VOLUME"),
                "吨", "SUM", countMarketFact(rows, "PURCHASE_VOLUME"));
        addMetric(metrics, "SALES_VOLUME", "销售量", sumMarketFact(rows, "SALES_VOLUME"),
                "吨", "SUM", countMarketFact(rows, "SALES_VOLUME"));
        BigDecimal currentInventory = inventory.enterpriseEndingTonnes();
        BigDecimal previousInventory = inventory.enterpriseOpeningTonnes();
        BigDecimal inventoryChange = difference(currentInventory, previousInventory);
        int enterpriseInventorySourceCount = countMarketFact(rows, "ENDING_INVENTORY");
        addMetric(metrics, "CURRENT_INVENTORY", "当前企业库存", currentInventory,
                "吨", "LATEST_EFFECTIVE", enterpriseInventorySourceCount);
        addMetric(metrics, "INVENTORY_CHANGE", "企业库存变化量", inventoryChange,
                "吨", "DIFFERENCE", enterpriseInventorySourceCount);
        addMetric(metrics, "INVENTORY_CHANGE_RATE", "企业库存变化率",
                percentage(inventoryChange, previousInventory), "%", "RATIO",
                enterpriseInventorySourceCount);
        addPackagingMetrics(metrics, rows);
        addMarketQualityMetrics(metrics, rows);
        return List.copyOf(metrics);
    }

    private List<ObservableMetric> logisticsMetrics(List<LogisticsRow> rows) {
        List<LogisticsRow> inflow = rows.stream().filter(row -> "INFLOW".equals(row.direction())).toList();
        List<LogisticsRow> outflow = rows.stream().filter(row -> "OUTFLOW".equals(row.direction())).toList();
        List<ObservableMetric> metrics = new ArrayList<>();
        addMetric(metrics, "INFLOW_VOLUME", "区域流入量", sumLogisticsFact(inflow, "ROUTE_VOLUME"),
                "吨", "SUM", countLogisticsFact(inflow, "ROUTE_VOLUME"));
        addMetric(metrics, "OUTFLOW_VOLUME", "区域流出量", sumLogisticsFact(outflow, "ROUTE_VOLUME"),
                "吨", "SUM", countLogisticsFact(outflow, "ROUTE_VOLUME"));
        addMetric(metrics, "AVERAGE_FREIGHT_RATE", "平均物流运价", averageFact(rows, "FREIGHT_RATE"),
                "元/吨", "AVERAGE", countLogisticsFact(rows, "FREIGHT_RATE"));
        return List.copyOf(metrics);
    }

    private SupplyAssembly supply(
            ObservableAnalysisScope scope,
            Set<String> authorizedRegions,
            List<ProductionRow> production,
            List<MarketRow> market,
            List<LogisticsRow> logistics) {
        MarketInventorySelection enterpriseEnding = selectMarketInventory(
                market, "ENDING_INVENTORY");
        List<MarketRow> previousMarket = marketRows(
                previousPeriod(scope), authorizedRegions);
        MarketInventorySelection enterpriseOpening = selectMarketInventory(
                previousMarket, "ENDING_INVENTORY");

        BigDecimal productionOpening = sumFact(production, "PROD_OPENING_INVENTORY");
        BigDecimal productionEnding = sumFact(production, "PROD_ENDING_INVENTORY");
        BigDecimal enterpriseOpeningTonnes = enterpriseOpening.selection().totalTonnes();
        BigDecimal enterpriseEndingTonnes = enterpriseEnding.selection().totalTonnes();
        BigDecimal opening = addAvailable(productionOpening, enterpriseOpeningTonnes);
        BigDecimal ending = addAvailable(productionEnding, enterpriseEndingTonnes);

        int reviewGroupCount = enterpriseOpening.selection().reviewGroupCount()
                + enterpriseEnding.selection().reviewGroupCount();
        boolean openingComplete = completeProductionFact(production, "PROD_OPENING_INVENTORY")
                && completeEnterpriseInventory(enterpriseOpening.selection());
        boolean endingComplete = completeProductionFact(production, "PROD_ENDING_INVENTORY")
                && completeEnterpriseInventory(enterpriseEnding.selection());

        Set<String> adoptedInventoryRecordIds = new LinkedHashSet<>();
        production.stream()
                .filter(row -> row.decimal("PROD_OPENING_INVENTORY") != null
                        || row.decimal("PROD_ENDING_INVENTORY") != null)
                .map(ProductionRow::recordId)
                .forEach(adoptedInventoryRecordIds::add);
        enterpriseOpening.rows().stream().map(MarketRow::recordId)
                .forEach(adoptedInventoryRecordIds::add);
        enterpriseEnding.rows().stream().map(MarketRow::recordId)
                .forEach(adoptedInventoryRecordIds::add);

        BigDecimal expectedOutput = production.isEmpty() ? null
                : sum(production, row -> tonnes(row.outputKg()));
        BigDecimal selfUse = production.isEmpty() ? null
                : requiredFactSum(production, "PROD_SELF_USE");
        List<LogisticsRow> inflows = logistics.stream()
                .filter(row -> "INFLOW".equals(row.direction())).toList();
        List<LogisticsRow> outflows = logistics.stream()
                .filter(row -> "OUTFLOW".equals(row.direction())).toList();
        BigDecimal inflow = inflows.isEmpty() ? null : requiredLogisticsFactSum(inflows, "ROUTE_VOLUME");
        BigDecimal outflow = outflows.isEmpty() ? null : requiredLogisticsFactSum(outflows, "ROUTE_VOLUME");
        ObservableSupplyCalculation calculation = ObservableSupplyCalculator.calculate(
                new ObservableQuantityInput(
                opening, expectedOutput, inflow, selfUse, outflow, ending,
                openingComplete, endingComplete, reviewGroupCount,
                production.size() + market.size() + previousMarket.size() + logistics.size()));
        ObservableInventoryBreakdown inventory = new ObservableInventoryBreakdown(
                productionOpening, enterpriseOpeningTonnes,
                productionEnding, enterpriseEndingTonnes,
                openingComplete, endingComplete,
                adoptedInventoryRecordIds.size(), reviewGroupCount,
                enterpriseOpening.selection().earliestObservedOn(),
                enterpriseOpening.selection().latestObservedOn(),
                enterpriseEnding.selection().earliestObservedOn(),
                enterpriseEnding.selection().latestObservedOn());
        List<AnalysisLineage> inventoryLineage = new ArrayList<>();
        enterpriseOpening.rows().forEach(row -> inventoryLineage.add(
                row.inventoryLineage("ENDING_INVENTORY")));
        enterpriseEnding.rows().forEach(row -> inventoryLineage.add(
                row.inventoryLineage("ENDING_INVENTORY")));
        return new SupplyAssembly(
                new ObservableSupplyView(calculation, inventory), inventoryLineage);
    }

    private MarketInventorySelection selectMarketInventory(
            List<MarketRow> rows, String factCode) {
        List<InventoryPositionObservation> observations = rows.stream()
                .map(row -> row.inventoryObservation(factCode))
                .filter(java.util.Objects::nonNull)
                .toList();
        InventoryPositionSelection selection = InventoryPositionSelector.select(observations);
        Set<String> adoptedIds = selection.adoptedRecordIds();
        List<MarketRow> adoptedRows = rows.stream()
                .filter(row -> adoptedIds.contains(row.recordId()))
                .sorted(Comparator.comparing(MarketRow::recordId))
                .toList();
        return new MarketInventorySelection(selection, adoptedRows);
    }

    private static ObservableAnalysisScope previousPeriod(ObservableAnalysisScope scope) {
        if (scope.surveyMonth() == null) {
            return new ObservableAnalysisScope(
                    scope.productCode(), scope.regionCode(), scope.surveyYear() - 1, null,
                    scope.cultivarCode(), scope.subjectTypeCode());
        }
        YearMonth previous = YearMonth.of(scope.surveyYear(), scope.surveyMonth()).minusMonths(1);
        return new ObservableAnalysisScope(
                scope.productCode(), scope.regionCode(), previous.getYear(), previous.getMonthValue(),
                scope.cultivarCode(), scope.subjectTypeCode());
    }

    private static BigDecimal addAvailable(BigDecimal first, BigDecimal second) {
        if (first == null) return second;
        if (second == null) return first;
        return normalize(first.add(second));
    }

    private static boolean completeProductionFact(List<ProductionRow> rows, String factCode) {
        return !rows.isEmpty() && rows.stream().allMatch(row -> row.decimal(factCode) != null);
    }

    private static boolean completeEnterpriseInventory(InventoryPositionSelection selection) {
        return selection.totalTonnes() != null && selection.reviewGroupCount() == 0;
    }

    private AnalysisQualityState combinedQuality(
            List<AnalysisLineage> lineage,
            List<ProductionSourceBalance> balances,
            ObservableSupplyCalculation supply) {
        if (lineage.isEmpty()) return AnalysisQualityState.NO_APPROVED_DATA;
        List<AnalysisQualityState> states = new ArrayList<>();
        states.add(supply.qualityState());
        balances.forEach(balance -> states.add(balance.qualityState()));
        if (states.contains(AnalysisQualityState.BLOCKED)) return AnalysisQualityState.BLOCKED;
        if (states.contains(AnalysisQualityState.COVERAGE_REVIEW_REQUIRED)) {
            return AnalysisQualityState.COVERAGE_REVIEW_REQUIRED;
        }
        if (states.contains(AnalysisQualityState.PARTIAL)) return AnalysisQualityState.PARTIAL;
        return AnalysisQualityState.AVAILABLE;
    }

    private int excludedRecordCount(
            ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        Integer value = scoped(SCOPE + """
                SELECT count(*)::integer FROM (
                  SELECT record.record_id
                  FROM production.production_record record
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND (:month IS NULL OR record.survey_month=:month)
                    AND (:cultivar IS NULL OR record.cultivar_code=:cultivar)
                    AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                    AND record.region_code IN(SELECT code FROM scope)
                    AND (record.status_code<>'APPROVED'
                      OR record.survey_period_governance_state<>'CONFIRMED')
                  UNION ALL
                  SELECT record.record_id
                  FROM market.market_record record
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND (:month IS NULL OR record.survey_month=:month)
                    AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                    AND record.region_code IN(SELECT code FROM scope)
                    AND (record.status_code<>'APPROVED'
                      OR record.survey_period_governance_state<>'CONFIRMED')
                  UNION ALL
                  SELECT event.event_id::text
                  FROM logistics.route_event event
                  WHERE event.product_code=:product AND event.survey_year=:year
                    AND (:month IS NULL OR event.survey_month=:month)
                    AND event.business_region_code IN(SELECT code FROM scope)
                    AND (event.status_code<>'APPROVED'
                      OR event.survey_period_governance_state<>'CONFIRMED'
                      OR event.direction_code='TRANSIT')
                ) excluded
                """, scope, authorizedRegions).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private int pendingReviewRecordCount(
            ObservableAnalysisScope scope, Set<String> authorizedRegions) {
        Integer value = scoped(SCOPE + """
                SELECT count(*)::integer FROM (
                  SELECT record.record_id
                  FROM production.production_record record
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND (:month IS NULL OR record.survey_month=:month)
                    AND (:cultivar IS NULL OR record.cultivar_code=:cultivar)
                    AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                    AND record.region_code IN(SELECT code FROM scope)
                    AND record.status_code='PENDING_REVIEW'
                  UNION ALL
                  SELECT record.record_id
                  FROM market.market_record record
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND (:month IS NULL OR record.survey_month=:month)
                    AND (:subjectType IS NULL OR record.object_type_code=:subjectType)
                    AND record.region_code IN(SELECT code FROM scope)
                    AND record.status_code='PENDING_REVIEW'
                  UNION ALL
                  SELECT event.event_id::text
                  FROM logistics.route_event event
                  WHERE event.product_code=:product AND event.survey_year=:year
                    AND (:month IS NULL OR event.survey_month=:month)
                    AND event.business_region_code IN(SELECT code FROM scope)
                    AND event.direction_code IN('INFLOW','OUTFLOW')
                    AND event.status_code='PENDING_REVIEW'
                ) pending
                """, scope, authorizedRegions).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private static Set<String> normalizeAuthorization(Set<String> authorizedRegions) {
        if (authorizedRegions == null || authorizedRegions.isEmpty()) return Set.of("__NONE__");
        return Set.copyOf(authorizedRegions);
    }

    private boolean exists(String sql, String value) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("value", value).query(Boolean.class).single());
    }

    private static void addMetric(
            List<ObservableMetric> metrics,
            String code,
            String label,
            BigDecimal value,
            String unit,
            String aggregation,
            int sources) {
        if (value == null || sources <= 0) return;
        metrics.add(new ObservableMetric(
                code, label, decimal(value), unit, aggregation, sources, null));
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal difference(BigDecimal current, BigDecimal baseline) {
        return current == null || baseline == null ? null : normalize(current.subtract(baseline));
    }

    private static int pairedProductionCount(List<ProductionRow> rows, String factCode) {
        return (int) rows.stream()
                .filter(row -> row.areaMu() != null && row.decimal(factCode) != null)
                .count();
    }

    private static int pairedMarketPriceCount(List<MarketRow> rows) {
        return (int) rows.stream()
                .filter(row -> row.purchaseBasePrice() != null && row.saleBasePrice() != null)
                .count();
    }

    private static BigDecimal averagePurchaseSaleSpread(List<MarketRow> rows) {
        return average(rows, row -> row.purchaseBasePrice() == null || row.saleBasePrice() == null
                ? null : row.saleBasePrice().subtract(row.purchaseBasePrice()));
    }

    private static void addProductionQualityMetrics(
            List<ObservableMetric> metrics, List<ProductionRow> rows) {
        QUALITY_FACTS.forEach(definition -> {
            int sources = countFact(rows, definition.code());
            addMetric(metrics, "QUALITY_" + definition.code() + "_AVERAGE",
                    definition.label() + "平均值", averageProductionFact(rows, definition.code()),
                    definition.unit(), "AVERAGE", sources);
            addMetric(metrics, "QUALITY_" + definition.code() + "_MINIMUM",
                    definition.label() + "最低值", minimumProductionFact(rows, definition.code()),
                    definition.unit(), "MINIMUM", sources);
            addMetric(metrics, "QUALITY_" + definition.code() + "_MAXIMUM",
                    definition.label() + "最高值", maximumProductionFact(rows, definition.code()),
                    definition.unit(), "MAXIMUM", sources);
        });
    }

    private static void addProductionCostMetrics(
            List<ObservableMetric> metrics, List<ProductionRow> rows) {
        COST_FACTS.forEach(definition -> addMetric(
                metrics, "COST_" + definition.code(), definition.label(),
                weightedProductionFact(rows, definition.code()), definition.unit(),
                "AREA_WEIGHTED_AVERAGE", countFact(rows, definition.code())));
        addMetric(metrics, "COMPLETE_COST_PER_MU", "完整亩均成本合计",
                completeCostPerMu(rows), "元/亩", "AREA_WEIGHTED_AVERAGE",
                completeCostSourceCount(rows));
    }

    private static BigDecimal averageProductionFact(List<ProductionRow> rows, String code) {
        return average(rows, row -> row.decimal(code));
    }

    private static BigDecimal minimumProductionFact(List<ProductionRow> rows, String code) {
        return rows.stream().map(row -> row.decimal(code))
                .filter(java.util.Objects::nonNull).min(BigDecimal::compareTo)
                .map(JdbcObservableAnalysisRepository::normalize).orElse(null);
    }

    private static BigDecimal maximumProductionFact(List<ProductionRow> rows, String code) {
        return rows.stream().map(row -> row.decimal(code))
                .filter(java.util.Objects::nonNull).max(BigDecimal::compareTo)
                .map(JdbcObservableAnalysisRepository::normalize).orElse(null);
    }

    private static BigDecimal weightedProductionFact(List<ProductionRow> rows, String code) {
        BigDecimal weightedTotal = BigDecimal.ZERO;
        BigDecimal areaTotal = BigDecimal.ZERO;
        for (ProductionRow row : rows) {
            BigDecimal value = row.decimal(code);
            if (value != null && row.areaMu() != null) {
                weightedTotal = weightedTotal.add(value.multiply(row.areaMu()));
                areaTotal = areaTotal.add(row.areaMu());
            }
        }
        return areaTotal.signum() == 0 ? null
                : weightedTotal.divide(areaTotal, SCALE, RoundingMode.HALF_UP);
    }

    private static int completeCostSourceCount(List<ProductionRow> rows) {
        return (int) rows.stream().filter(row -> COST_FACTS.stream()
                .allMatch(definition -> row.decimal(definition.code()) != null)).count();
    }

    private static BigDecimal completeCostPerMu(List<ProductionRow> rows) {
        if (rows.isEmpty() || completeCostSourceCount(rows) != rows.size()) return null;
        BigDecimal weightedTotal = BigDecimal.ZERO;
        BigDecimal areaTotal = BigDecimal.ZERO;
        for (ProductionRow row : rows) {
            BigDecimal rowTotal = COST_FACTS.stream()
                    .map(definition -> row.decimal(definition.code()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            weightedTotal = weightedTotal.add(rowTotal.multiply(row.areaMu()));
            areaTotal = areaTotal.add(row.areaMu());
        }
        return areaTotal.signum() == 0 ? null
                : weightedTotal.divide(areaTotal, SCALE, RoundingMode.HALF_UP);
    }

    private static void addPackagingMetrics(
            List<ObservableMetric> metrics, List<MarketRow> rows) {
        int total = (int) rows.stream().map(MarketRow::packagingForm)
                .filter(value -> value != null && !value.isBlank()).count();
        if (total == 0) return;
        int bulk = (int) rows.stream().filter(row -> "BULK".equals(row.packagingForm())).count();
        int bagged = (int) rows.stream().filter(row -> "BAGGED".equals(row.packagingForm())).count();
        addMetric(metrics, "PACKAGING_BULK_COUNT", "散粮记录数", BigDecimal.valueOf(bulk),
                "条", "COUNT", bulk);
        addMetric(metrics, "PACKAGING_BAGGED_COUNT", "包粮记录数", BigDecimal.valueOf(bagged),
                "条", "COUNT", bagged);
        addMetric(metrics, "PACKAGING_BULK_SHARE", "散粮占比",
                percentage(BigDecimal.valueOf(bulk), BigDecimal.valueOf(total)),
                "%", "RATIO", bulk);
        addMetric(metrics, "PACKAGING_BAGGED_SHARE", "包粮占比",
                percentage(BigDecimal.valueOf(bagged), BigDecimal.valueOf(total)),
                "%", "RATIO", bagged);
    }

    private static void addMarketQualityMetrics(
            List<ObservableMetric> metrics, List<MarketRow> rows) {
        QUALITY_FACTS.forEach(definition -> {
            int sources = countMarketFact(rows, definition.code());
            addMetric(metrics, "MARKET_QUALITY_" + definition.code() + "_AVERAGE",
                    definition.label() + "平均值", averageMarketFact(rows, definition.code()),
                    definition.unit(), "AVERAGE", sources);
            addMetric(metrics, "MARKET_QUALITY_" + definition.code() + "_MINIMUM",
                    definition.label() + "最低值", minimumMarketFact(rows, definition.code()),
                    definition.unit(), "MINIMUM", sources);
            addMetric(metrics, "MARKET_QUALITY_" + definition.code() + "_MAXIMUM",
                    definition.label() + "最高值", maximumMarketFact(rows, definition.code()),
                    definition.unit(), "MAXIMUM", sources);
        });
    }

    private static BigDecimal averageMarketFact(List<MarketRow> rows, String code) {
        return average(rows, row -> row.fact(code));
    }

    private static BigDecimal minimumMarketFact(List<MarketRow> rows, String code) {
        return rows.stream().map(row -> row.fact(code))
                .filter(java.util.Objects::nonNull).min(BigDecimal::compareTo)
                .map(JdbcObservableAnalysisRepository::normalize).orElse(null);
    }

    private static BigDecimal maximumMarketFact(List<MarketRow> rows, String code) {
        return rows.stream().map(row -> row.fact(code))
                .filter(java.util.Objects::nonNull).max(BigDecimal::compareTo)
                .map(JdbcObservableAnalysisRepository::normalize).orElse(null);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal tonnes(BigDecimal kilograms) {
        return kilograms == null ? null
                : kilograms.divide(new BigDecimal("1000"), SCALE, RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> value) {
        BigDecimal total = null;
        for (T row : rows) {
            BigDecimal next = value.apply(row);
            if (next != null) total = total == null ? next : total.add(next);
        }
        return normalize(total);
    }

    private static <T> BigDecimal average(Collection<T> rows, Function<T, BigDecimal> value) {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (T row : rows) {
            BigDecimal next = value.apply(row);
            if (next != null) {
                total = total.add(next);
                count++;
            }
        }
        return count == 0 ? null : total.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    private static <T> int count(Collection<T> rows, Function<T, BigDecimal> value) {
        return (int) rows.stream().map(value).filter(java.util.Objects::nonNull).count();
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumFact(List<ProductionRow> rows, String code) {
        return sum(rows, row -> row.decimal(code));
    }

    private static int countFact(List<ProductionRow> rows, String code) {
        return (int) rows.stream().filter(row -> row.decimal(code) != null).count();
    }

    private static BigDecimal sumMarketFact(List<MarketRow> rows, String code) {
        return sum(rows, row -> row.fact(code));
    }

    private static int countMarketFact(List<MarketRow> rows, String code) {
        return (int) rows.stream().filter(row -> row.fact(code) != null).count();
    }

    private static BigDecimal sumLogisticsFact(List<LogisticsRow> rows, String code) {
        return sum(rows, row -> row.fact(code));
    }

    private static BigDecimal averageFact(List<LogisticsRow> rows, String code) {
        return average(rows, row -> row.fact(code));
    }

    private static int countLogisticsFact(List<LogisticsRow> rows, String code) {
        return (int) rows.stream().filter(row -> row.fact(code) != null).count();
    }

    private static BigDecimal requiredFactSum(List<ProductionRow> rows, String code) {
        return rows.stream().allMatch(row -> row.decimal(code) != null) ? sumFact(rows, code) : null;
    }

    private static BigDecimal requiredMarketFactSum(List<MarketRow> rows, String code) {
        return rows.stream().allMatch(row -> row.fact(code) != null) ? sumMarketFact(rows, code) : null;
    }

    private static BigDecimal requiredLogisticsFactSum(List<LogisticsRow> rows, String code) {
        return rows.stream().allMatch(row -> row.fact(code) != null) ? sumLogisticsFact(rows, code) : null;
    }

    private record ProductionRow(
            String recordId, long version, String regionCode, String regionLabel,
            String objectTypeCode, String objectTypeLabel, String cultivarCode,
            String businessIdentity,
            LocalDate surveyDate, int surveyYear, Integer surveyMonth, String periodPrecision,
            String samplePointId, BigDecimal areaMu, BigDecimal yieldPerMuKg,
            BigDecimal outputKg, OffsetDateTime approvedAt, Map<String, String> facts) {
        String businessKey() {
            return businessIdentity;
        }
        BigDecimal decimal(String code) {
            String value = facts.get(code);
            if (value == null || !value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) return null;
            return new BigDecimal(value);
        }
        boolean has(String code) { return facts.containsKey(code); }
        AnalysisLineage lineage() {
            LinkedHashSet<String> codes = new LinkedHashSet<>(facts.keySet());
            codes.add("PROD_AREA_MU");
            codes.add("PROD_YIELD_PER_MU");
            codes.add("PROD_ESTIMATED_OUTPUT");
            return new AnalysisLineage("PRODUCTION", recordId, version, List.copyOf(codes),
                    facts.getOrDefault("PROD_SAMPLE_NAME", objectTypeLabel), regionLabel,
                    periodLabel(surveyYear, surveyMonth, periodPrecision), approvedAt);
        }
    }

    private static final class ProductionRowBuilder {
        private final String recordId;
        private final long version;
        private final String regionCode;
        private final String regionLabel;
        private final String objectTypeCode;
        private final String objectTypeLabel;
        private final String cultivarCode;
        private final String businessIdentity;
        private final LocalDate surveyDate;
        private final int surveyYear;
        private final Integer surveyMonth;
        private final String periodPrecision;
        private final String samplePointId;
        private final BigDecimal areaMu;
        private final BigDecimal yieldPerMuKg;
        private final BigDecimal outputKg;
        private final OffsetDateTime approvedAt;
        private final Map<String, String> facts = new LinkedHashMap<>();

        ProductionRowBuilder(String recordId, long version, String regionCode, String regionLabel,
                String objectTypeCode, String objectTypeLabel, String cultivarCode,
                String businessIdentity, LocalDate surveyDate,
                int surveyYear, Integer surveyMonth, String periodPrecision, String samplePointId,
                BigDecimal areaMu, BigDecimal yieldPerMuKg, BigDecimal outputKg,
                OffsetDateTime approvedAt) {
            this.recordId = recordId;
            this.version = version;
            this.regionCode = regionCode;
            this.regionLabel = regionLabel;
            this.objectTypeCode = objectTypeCode;
            this.objectTypeLabel = objectTypeLabel;
            this.cultivarCode = cultivarCode;
            this.businessIdentity = businessIdentity;
            this.surveyDate = surveyDate;
            this.surveyYear = surveyYear;
            this.surveyMonth = surveyMonth;
            this.periodPrecision = periodPrecision;
            this.samplePointId = samplePointId;
            this.areaMu = areaMu;
            this.yieldPerMuKg = yieldPerMuKg;
            this.outputKg = outputKg;
            this.approvedAt = approvedAt;
        }
        void fact(String code, String value) { facts.put(code, value); }
        ProductionRow build() {
            return new ProductionRow(recordId, version, regionCode, regionLabel, objectTypeCode,
                    objectTypeLabel, cultivarCode, businessIdentity, surveyDate, surveyYear, surveyMonth, periodPrecision,
                    samplePointId, areaMu, yieldPerMuKg, outputKg, approvedAt, Map.copyOf(facts));
        }
    }

    private record MarketRow(
            String recordId, long version, String regionCode, String regionLabel,
            String objectTypeCode, String objectTypeLabel, String partyId, String partyLabel,
            String samplePointId, String businessIdentity, String sampleName, String sampleContact,
            BigDecimal latitude, BigDecimal longitude,
            LocalDate tradeDate, int surveyYear, Integer surveyMonth,
            String periodPrecision, String direction, BigDecimal purchaseBasePrice,
            BigDecimal saleBasePrice, BigDecimal actualTradePrice,
            BigDecimal carriageBoardAmount, BigDecimal packagingAmount,
            BigDecimal freightAmount, String packagingForm, OffsetDateTime approvedAt,
            Map<String, BigDecimal> facts) {
        String subjectKey() {
            return businessIdentity;
        }
        String businessKey() {
            return String.join("|", regionCode, subjectKey(), tradeDate.toString(), direction);
        }
        BigDecimal fact(String code) { return facts.get(code); }
        boolean has(String code) { return facts.containsKey(code); }
        InventoryPositionObservation inventoryObservation(String factCode) {
            BigDecimal value = fact(factCode);
            if (value == null) return null;
            String normalizedName = normalizeVisibleValue(sampleName, businessIdentity);
            String normalizedContact = normalizeVisibleValue(sampleContact, businessIdentity);
            return new InventoryPositionObservation(
                    recordId, businessIdentity, normalizedName, normalizedContact,
                    regionCode, latitude, longitude, tradeDate, version, approvedAt, value);
        }
        AnalysisLineage lineage() {
            LinkedHashSet<String> codes = facts.keySet().stream()
                    .filter(code -> !"OPENING_INVENTORY".equals(code))
                    .filter(code -> !"ENDING_INVENTORY".equals(code))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (purchaseBasePrice != null) codes.add("MKT_PURCHASE_BASE_PRICE");
            if (saleBasePrice != null) codes.add("MKT_SALE_BASE_PRICE");
            if (carriageBoardAmount != null) codes.add("MKT_CARRIAGE_BOARD_AMOUNT");
            if (packagingAmount != null) codes.add("MKT_PACKAGING_AMOUNT");
            if (freightAmount != null) codes.add("MKT_FREIGHT_AMOUNT");
            if (packagingForm != null) codes.add("MKT_PACKAGING_FORM");
            return lineage(codes);
        }
        AnalysisLineage inventoryLineage(String factCode) {
            return lineage(List.of(factCode));
        }
        private AnalysisLineage lineage(java.util.Collection<String> codes) {
            return new AnalysisLineage("MARKET", recordId, version, List.copyOf(codes),
                    partyLabel != null ? partyLabel
                            : sampleName == null || sampleName.isBlank() ? objectTypeLabel : sampleName,
                    regionLabel,
                    periodLabel(surveyYear, surveyMonth, periodPrecision), approvedAt);
        }
    }

    private static final class MarketRowBuilder {
        private final String recordId;
        private final long version;
        private final String regionCode;
        private final String regionLabel;
        private final String objectTypeCode;
        private final String objectTypeLabel;
        private final String partyId;
        private final String partyLabel;
        private final String samplePointId;
        private final String businessIdentity;
        private final String sampleName;
        private final String sampleContact;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final LocalDate tradeDate;
        private final int surveyYear;
        private final Integer surveyMonth;
        private final String periodPrecision;
        private final String direction;
        private final BigDecimal purchaseBasePrice;
        private final BigDecimal saleBasePrice;
        private final BigDecimal actualTradePrice;
        private final BigDecimal carriageBoardAmount;
        private final BigDecimal packagingAmount;
        private final BigDecimal freightAmount;
        private final String packagingForm;
        private final OffsetDateTime approvedAt;
        private final Map<String, BigDecimal> facts = new LinkedHashMap<>();

        MarketRowBuilder(String recordId, long version, String regionCode, String regionLabel,
                String objectTypeCode, String objectTypeLabel, String partyId, String partyLabel,
                String samplePointId, String businessIdentity, String sampleName, String sampleContact,
                BigDecimal latitude, BigDecimal longitude,
                LocalDate tradeDate, int surveyYear, Integer surveyMonth,
                String periodPrecision, String direction, BigDecimal purchaseBasePrice,
                BigDecimal saleBasePrice, BigDecimal actualTradePrice,
                BigDecimal carriageBoardAmount, BigDecimal packagingAmount,
                BigDecimal freightAmount, String packagingForm, OffsetDateTime approvedAt) {
            this.recordId = recordId;
            this.version = version;
            this.regionCode = regionCode;
            this.regionLabel = regionLabel;
            this.objectTypeCode = objectTypeCode;
            this.objectTypeLabel = objectTypeLabel;
            this.partyId = partyId;
            this.partyLabel = partyLabel;
            this.samplePointId = samplePointId;
            this.businessIdentity = businessIdentity;
            this.sampleName = sampleName;
            this.sampleContact = sampleContact;
            this.latitude = latitude;
            this.longitude = longitude;
            this.tradeDate = tradeDate;
            this.surveyYear = surveyYear;
            this.surveyMonth = surveyMonth;
            this.periodPrecision = periodPrecision;
            this.direction = direction;
            this.purchaseBasePrice = purchaseBasePrice;
            this.saleBasePrice = saleBasePrice;
            this.actualTradePrice = actualTradePrice;
            this.carriageBoardAmount = carriageBoardAmount;
            this.packagingAmount = packagingAmount;
            this.freightAmount = freightAmount;
            this.packagingForm = packagingForm;
            this.approvedAt = approvedAt;
        }
        void fact(String code, BigDecimal value) { facts.put(code, value); }
        MarketRow build() {
            return new MarketRow(recordId, version, regionCode, regionLabel, objectTypeCode,
                    objectTypeLabel, partyId, partyLabel, samplePointId,
                    businessIdentity, sampleName, sampleContact, latitude, longitude,
                    tradeDate, surveyYear,
                    surveyMonth, periodPrecision, direction, purchaseBasePrice, saleBasePrice,
                    actualTradePrice, carriageBoardAmount, packagingAmount,
                    freightAmount, packagingForm, approvedAt, Map.copyOf(facts));
        }
    }

    private record FactMetricDefinition(String code, String label, String unit) { }

    private record MarketInventorySelection(
            InventoryPositionSelection selection,
            List<MarketRow> rows) { }

    private record SupplyAssembly(
            ObservableSupplyView view,
            List<AnalysisLineage> inventoryLineage) { }

    private record LogisticsRow(
            String recordId, long version, String regionCode, String regionLabel,
            LocalDate collectionDate, int surveyYear, Integer surveyMonth, String periodPrecision,
            String direction, String originNode, String destinationNode, String sourceLabel,
            OffsetDateTime approvedAt, Map<String, BigDecimal> facts) {
        String businessKey() {
            return String.join("|", regionCode, collectionDate.toString(), direction,
                    originNode == null ? "*" : originNode,
                    destinationNode == null ? "*" : destinationNode, sourceLabel);
        }
        BigDecimal fact(String code) { return facts.get(code); }
        AnalysisLineage lineage() {
            return new AnalysisLineage("LOGISTICS", recordId, version, List.copyOf(facts.keySet()),
                    sourceLabel, regionLabel, periodLabel(surveyYear, surveyMonth, periodPrecision),
                    approvedAt);
        }
    }

    private static final class LogisticsRowBuilder {
        private final String recordId;
        private final long version;
        private final String regionCode;
        private final String regionLabel;
        private final LocalDate collectionDate;
        private final int surveyYear;
        private final Integer surveyMonth;
        private final String periodPrecision;
        private final String direction;
        private final String originNode;
        private final String destinationNode;
        private final String sourceLabel;
        private final OffsetDateTime approvedAt;
        private final Map<String, BigDecimal> facts = new LinkedHashMap<>();

        LogisticsRowBuilder(String recordId, long version, String regionCode, String regionLabel,
                LocalDate collectionDate, int surveyYear, Integer surveyMonth, String periodPrecision,
                String direction, String originNode, String destinationNode, String sourceLabel,
                OffsetDateTime approvedAt) {
            this.recordId = recordId;
            this.version = version;
            this.regionCode = regionCode;
            this.regionLabel = regionLabel;
            this.collectionDate = collectionDate;
            this.surveyYear = surveyYear;
            this.surveyMonth = surveyMonth;
            this.periodPrecision = periodPrecision;
            this.direction = direction;
            this.originNode = originNode;
            this.destinationNode = destinationNode;
            this.sourceLabel = sourceLabel;
            this.approvedAt = approvedAt;
        }
        void fact(String code, BigDecimal value) { facts.put(code, value); }
        LogisticsRow build() {
            return new LogisticsRow(recordId, version, regionCode, regionLabel, collectionDate,
                    surveyYear, surveyMonth, periodPrecision, direction, originNode, destinationNode,
                    sourceLabel, approvedAt, Map.copyOf(facts));
        }
    }

    private static String periodLabel(int year, Integer month, String precision) {
        return "YEAR".equals(precision) || month == null
                ? year + "年" : "%d年%02d月".formatted(year, month);
    }

    private static String normalizeVisibleValue(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000()（）-]+", "");
    }
}
