package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisRepository;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisSnapshot;
import com.cofco.qiqihar.graintrade.overview.application.OverviewIndicator;
import com.cofco.qiqihar.graintrade.overview.application.OverviewBusinessTable;
import com.cofco.qiqihar.graintrade.overview.application.OverviewDashboard;
import com.cofco.qiqihar.graintrade.overview.application.OverviewMapScope;
import com.cofco.qiqihar.graintrade.overview.application.OverviewOption;
import com.cofco.qiqihar.graintrade.overview.application.OverviewOptions;
import com.cofco.qiqihar.graintrade.overview.application.OverviewPeriodOption;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRegion;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRepository;
import com.cofco.qiqihar.graintrade.overview.application.RegionSurplusCalculation;
import com.cofco.qiqihar.graintrade.overview.application.RegionSurplusCalculator;
import com.cofco.qiqihar.graintrade.overview.application.RegionSurplusSource;
import com.cofco.qiqihar.graintrade.overview.application.AnnualComparisonDefinition;
import com.cofco.qiqihar.graintrade.overview.application.AnnualComparisonPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOverviewRepository implements OverviewRepository {
    private static final BigDecimal TONNES_PER_TEN_THOUSAND = new BigDecimal("10000");
    private static final String AUTHORIZED_REQUEST_SCOPE = """
            WITH RECURSIVE monitoring_scope(region_code) AS (
              SELECT region_code FROM platform.monitoring_scope_region
              WHERE scope_code='FORMAL_BUSINESS' AND included
                AND (:unrestricted OR region_code IN (:authorizedRegions))
            ), requested_scope(code) AS (
              SELECT code FROM platform.region
              WHERE CAST(:region AS varchar) IS NULL OR code=CAST(:region AS varchar)
              UNION
              SELECT child.code FROM platform.region child
              JOIN requested_scope parent ON child.parent_code=parent.code
            ), scope(code) AS (
              SELECT monitoring_scope.region_code FROM monitoring_scope
              WHERE CAST(:region AS varchar) IS NULL
                 OR monitoring_scope.region_code IN (SELECT code FROM requested_scope)
            )
            """;

    private static final String BUSINESS_TABLE_SCOPE = AUTHORIZED_REQUEST_SCOPE + """
            , candidate_table_region AS (
              SELECT region.code,region.name,region.sort_order
              FROM platform.region region
              JOIN monitoring_scope ON monitoring_scope.region_code=region.code
              WHERE (CAST(:region AS varchar) IS NULL AND region.administrative_level='PREFECTURE')
                 OR (CAST(:region AS varchar) IS NOT NULL
                     AND region.parent_code=CAST(:region AS varchar))
            ), table_region AS (
              SELECT code,name,sort_order FROM candidate_table_region
              UNION ALL
              SELECT selected.code,selected.name,selected.sort_order
              FROM platform.region selected
              JOIN monitoring_scope ON monitoring_scope.region_code=selected.code
              WHERE CAST(:region AS varchar) IS NOT NULL
                AND selected.code=CAST(:region AS varchar)
                AND NOT EXISTS(SELECT 1 FROM candidate_table_region)
            ), table_region_descendant(root_code,code) AS (
              SELECT code,code FROM table_region
              UNION ALL
              SELECT descendant.root_code,child.code
              FROM table_region_descendant descendant
              JOIN platform.region child ON child.parent_code=descendant.code
              JOIN monitoring_scope ON monitoring_scope.region_code=child.code
            )
            """;

    private final JdbcClient jdbc;
    private final ObservableAnalysisRepository observableAnalysisRepository;

    public JdbcOverviewRepository(
            JdbcClient jdbc,
            ObservableAnalysisRepository observableAnalysisRepository) {
        this.jdbc = jdbc;
        this.observableAnalysisRepository = observableAnalysisRepository;
    }

    @Override
    public OverviewOptions options() {
        List<OverviewOption> products = jdbc.sql("SELECT code,name FROM platform.product ORDER BY sort_order")
                .query((row, index) -> new OverviewOption(row.getString("code"), row.getString("name"))).list();
        List<OverviewPeriodOption> periods = jdbc.sql("SELECT code,name,starts_on,ends_on FROM platform.business_period ORDER BY starts_on DESC,sort_order DESC")
                .query((row, index) -> new OverviewPeriodOption(row.getString("code"), row.getString("name"),
                        row.getObject("starts_on", LocalDate.class).toString(),
                        row.getObject("ends_on", LocalDate.class).toString())).list();
        List<Integer> years = jdbc.sql("""
                SELECT survey_year FROM production.production_record
                WHERE status_code='APPROVED' AND survey_period_governance_state='CONFIRMED'
                UNION
                SELECT survey_year FROM market.market_record
                WHERE status_code='APPROVED' AND survey_period_governance_state='CONFIRMED'
                UNION
                SELECT survey_year FROM logistics.route_event
                WHERE status_code='APPROVED' AND survey_period_governance_state='CONFIRMED'
                UNION
                SELECT survey_year::integer FROM supply.calculation_run
                WHERE result_state='PUBLISHED' AND temporal_governance_state='CONFIRMED'
                  AND survey_year IS NOT NULL
                ORDER BY survey_year DESC
                """).query(Integer.class).list();
        return new OverviewOptions(products, periods, years);
    }

    @Override
    public OverviewMapScope mapScope() {
        return jdbc.sql("""
                SELECT scope.code scope_code,
                       scope.name,
                       render.geo_json boundary_geo_json,
                       render.source_name,
                       render.source_revision,
                       render.source_license,
                       render.component_geometry_fingerprint,
                       render.refreshed_at
                FROM platform.monitoring_scope scope
                JOIN overview.monitoring_scope_boundary boundary ON boundary.scope_code=scope.code
                JOIN overview.monitoring_scope_boundary_render render ON render.scope_code=scope.code
                WHERE scope.code='FORMAL_BUSINESS' AND scope.enabled
                """)
                .query((row, index) -> new OverviewMapScope(
                        row.getString("scope_code"),
                        row.getString("name"),
                        row.getString("boundary_geo_json"),
                        row.getString("source_name"),
                        row.getString("source_revision"),
                        row.getString("source_license"),
                        row.getString("component_geometry_fingerprint"),
                        row.getObject("refreshed_at", OffsetDateTime.class).toString()))
                .single();
    }

    @Override public boolean knownProduct(String productCode) { return exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)", productCode); }
    @Override public boolean knownRegion(String regionCode) {
        return exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)", regionCode);
    }
    @Override public boolean knownPeriod(String periodCode) { return exists("SELECT EXISTS(SELECT 1 FROM platform.business_period WHERE code=:value)", periodCode); }
    @Override public Optional<Integer> surveyYearForPeriod(String periodCode) {
        return jdbc.sql("SELECT EXTRACT(YEAR FROM ends_on)::integer FROM platform.business_period WHERE code=:period")
                .param("period", periodCode).query(Integer.class).optional();
    }
    @Override public boolean knownCultivar(String productCode, String cultivarCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.cultivar WHERE product_code=:product AND code=:cultivar)
                """).param("product", productCode).param("cultivar", cultivarCode).query(Boolean.class).single());
    }

    @Override
    public List<AnnualComparisonDefinition> annualComparisonDefinitions(String sourceDomain, String productCode) {
        return jdbc.sql("""
                SELECT definition.code,definition.name,definition.unit_code,
                       definition.source_domain,definition.aggregation_code
                FROM overview.indicator_definition definition
                JOIN overview.annual_comparison_metric_binding binding
                  ON binding.metric_code=definition.code
                WHERE definition.annual_comparison_enabled
                  AND definition.source_domain=:domain
                  AND (
                    binding.storage_code IN ('PRODUCTION_CORE','MARKET_CORE')
                    OR binding.storage_code='LOGISTICS_FACT'
                    OR binding.storage_code LIKE 'PRODUCTION_%' AND EXISTS (
                      SELECT 1 FROM platform.production_fact_applicability applicability
                      WHERE applicability.product_code=:product
                        AND applicability.fact_code=binding.field_code
                    )
                    OR binding.storage_code='MARKET_FACT' AND EXISTS (
                      SELECT 1 FROM platform.market_fact_applicability applicability
                      WHERE applicability.product_code=:product
                        AND applicability.fact_code=binding.field_code
                    )
                  )
                ORDER BY definition.sort_order
                """).param("domain", sourceDomain).param("product", productCode)
                .query((row, index) -> new AnnualComparisonDefinition(
                        row.getString("code"), row.getString("name"), row.getString("unit_code"),
                        row.getString("source_domain"), row.getString("aggregation_code")))
                .list();
    }

    @Override
    public Optional<AnnualComparisonDefinition> annualComparisonDefinition(String indicatorCode) {
        return jdbc.sql("""
                SELECT code,name,unit_code,source_domain,aggregation_code
                FROM overview.indicator_definition
                WHERE code=:code AND annual_comparison_enabled
                """).param("code", indicatorCode).query((row, index) -> new AnnualComparisonDefinition(
                        row.getString("code"), row.getString("name"), row.getString("unit_code"),
                        row.getString("source_domain"), row.getString("aggregation_code")))
                .optional();
    }

    @Override
    public boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes) {
        if (authorizedRegionCodes.contains("*")) return true;
        if (authorizedRegionCodes.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                WITH RECURSIVE navigable(code,parent_code) AS (
                  SELECT code,parent_code FROM platform.region WHERE code IN (:authorizedRegions)
                  UNION
                  SELECT parent.code,parent.parent_code FROM platform.region parent
                  JOIN navigable child ON child.parent_code=parent.code
                )
                SELECT EXISTS(SELECT 1 FROM navigable WHERE code=:region)
                """).param("authorizedRegions", authorizedRegionCodes).param("region", regionCode)
                .query(Boolean.class).single());
    }

    @Override
    public List<OverviewRegion> regions(
            String parentCode, String productCode, int year, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE authorized_region(code,parent_code) AS (
                  SELECT code,parent_code FROM platform.region
                  WHERE :unrestricted OR code IN (:authorizedRegions)
                ), navigable_region(code,parent_code) AS (
                  SELECT code,parent_code FROM authorized_region
                  UNION
                  SELECT parent.code,parent.parent_code FROM platform.region parent
                  JOIN navigable_region child ON child.parent_code=parent.code
                ), approved AS (
                  SELECT record.region_code,record.record_id
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                    WHERE record.product_code=:product AND record.status_code='APPROVED'
                      AND record.survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                      AND (:unrestricted OR record.region_code IN (:authorizedRegions))
                  UNION ALL
                  SELECT record.region_code,record.record_id
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                    WHERE record.product_code=:product AND record.status_code='APPROVED'
                      AND record.survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                      AND (:unrestricted OR record.region_code IN (:authorizedRegions))
                  UNION ALL
                  SELECT destination_region_code,event_id::text FROM logistics.route_event
                    WHERE product_code=:product AND status_code='APPROVED' AND survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                      AND (:unrestricted OR (origin_region_code IN (:authorizedRegions)
                        AND destination_region_code IN (:authorizedRegions)))
                ), candidate_region AS (
                  SELECT region.code,region.name,region.parent_code,region.administrative_level,region.sort_order,
                         NULL::text context_boundary_geo_json,false map_context_only
                    FROM platform.region region JOIN navigable_region ON navigable_region.code=region.code
                   WHERE region.parent_code IS NOT DISTINCT FROM CAST(:parent AS varchar)
                     AND EXISTS(
                       SELECT 1 FROM overview.administrative_boundary_render boundary
                        WHERE boundary.region_code=region.code
                       UNION ALL
                       SELECT 1 FROM platform.monitoring_scope_region scope
                        WHERE scope.scope_code='FORMAL_BUSINESS'
                          AND scope.included
                          AND scope.region_code=region.code
                     )
                )
                SELECT region.code,region.name,region.parent_code,region.administrative_level,COUNT(approved.record_id) approved_count,
                  COALESCE(region.context_boundary_geo_json,boundary_render.geo_json) boundary_geo_json,
                  ST_AsGeoJSON(COALESCE(location.wgs84_coordinate,derived_location.geometry)) location_geo_json,
                  CASE WHEN location.region_code IS NOT NULL THEN location.review_status
                       WHEN derived_location.geometry IS NOT NULL THEN 'DERIVED_FROM_VILLAGE_POINTS'
                  END location_review_status,
                  region.map_context_only
                FROM candidate_region region
                LEFT JOIN approved ON approved.region_code=region.code
                LEFT JOIN overview.administrative_boundary_render boundary_render
                  ON boundary_render.region_code=region.code
                LEFT JOIN platform.region_location location ON location.region_code=region.code
                LEFT JOIN LATERAL (
                  SELECT ST_Centroid(ST_Collect(child_location.wgs84_coordinate)) geometry
                  FROM platform.region child
                  JOIN platform.region_location child_location ON child_location.region_code=child.code
                  WHERE child.parent_code=region.code AND child.administrative_level='VILLAGE'
                ) derived_location ON true
                GROUP BY region.code,region.name,region.parent_code,region.administrative_level,region.sort_order,
                  region.context_boundary_geo_json,region.map_context_only,boundary_render.geo_json,
                  location.region_code,location.wgs84_coordinate,location.review_status,derived_location.geometry
                ORDER BY region.sort_order,region.name
                """).param("year", year).param("product", productCode).param("parent", parentCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewRegion(row.getString("code"), row.getString("name"),
                        row.getString("parent_code"), row.getString("administrative_level"), row.getLong("approved_count"),
                        row.getString("boundary_geo_json"), row.getString("location_geo_json"),
                        row.getString("location_review_status"), row.getBoolean("map_context_only"))).list();
    }

    @Override
    public List<OverviewRegion> locations(String ancestorCode, String level, String productCode, int year,
            Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), descendants(code) AS (
                  SELECT region.code FROM platform.region region
                  WHERE region.code=CAST(:ancestor AS varchar)
                  UNION ALL
                  SELECT child.code FROM platform.region child JOIN descendants parent ON child.parent_code=parent.code
                ), approved AS (
                  SELECT record.region_code,record.record_id
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                    WHERE record.product_code=:product AND record.status_code='APPROVED'
                      AND record.survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                  UNION ALL
                  SELECT record.region_code,record.record_id
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                    WHERE record.product_code=:product AND record.status_code='APPROVED'
                      AND record.survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                  UNION ALL
                  SELECT destination_region_code,event_id::text FROM logistics.route_event
                    WHERE product_code=:product AND status_code='APPROVED' AND survey_year=:year
                      AND survey_period_governance_state='CONFIRMED'
                )
                SELECT region.code,region.name,region.parent_code,region.administrative_level,
                  COUNT(approved.record_id) approved_count,
                  NULL::text boundary_geo_json,
                  ST_AsGeoJSON(COALESCE(location.wgs84_coordinate,derived_location.geometry)) location_geo_json,
                  CASE WHEN location.region_code IS NOT NULL THEN location.review_status
                       WHEN derived_location.geometry IS NOT NULL THEN 'DERIVED_FROM_VILLAGE_POINTS'
                  END location_review_status
                FROM platform.region region
                JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                LEFT JOIN platform.region_location location ON location.region_code=region.code
                LEFT JOIN LATERAL (
                  SELECT ST_Centroid(ST_Collect(child_location.wgs84_coordinate)) geometry
                  FROM platform.region child
                  JOIN platform.region_location child_location ON child_location.region_code=child.code
                  WHERE child.parent_code=region.code AND child.administrative_level='VILLAGE'
                ) derived_location ON true
                LEFT JOIN approved ON approved.region_code=region.code
                WHERE region.administrative_level=CAST(:level AS varchar)
                  AND COALESCE(location.wgs84_coordinate,derived_location.geometry) IS NOT NULL
                  AND (CAST(:ancestor AS varchar) IS NULL OR region.code IN (SELECT code FROM descendants))
                GROUP BY region.code,region.name,region.parent_code,region.administrative_level,region.sort_order,
                  location.region_code,location.wgs84_coordinate,location.review_status,derived_location.geometry
                ORDER BY region.sort_order,region.code
                """).param("ancestor", ancestorCode).param("level", level)
                .param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewRegion(row.getString("code"), row.getString("name"),
                        row.getString("parent_code"), row.getString("administrative_level"), row.getLong("approved_count"),
                        row.getString("boundary_geo_json"), row.getString("location_geo_json"),
                        row.getString("location_review_status"), false)).list();
    }

    @Override
    public List<OverviewIndicator> indicators(String productCode, String regionCode, int year,
            Set<String> authorizedRegionCodes) {
        List<OverviewIndicator> indicators = jdbc.sql(AUTHORIZED_REQUEST_SCOPE + """
                , effective_production_record AS (
                  SELECT record.*,
                    COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                  LEFT JOIN LATERAL (
                    SELECT max(event.occurred_at) approved_at
                    FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='PRODUCTION_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='PRODUCTION_RECORD_APPROVED'
                  ) approval ON true
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND record.region_code IN(SELECT code FROM scope)
                ), effective_market_candidate AS (
                  SELECT record.*,
                    COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at,
                    row_number() OVER(PARTITION BY record.region_code,
                      COALESCE(record.party_id::text,record.sample_point_id::text,record.record_id),
                      record.trade_date,record.trade_direction
                      ORDER BY record.version DESC,record.record_id DESC) effective_rank
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                  LEFT JOIN LATERAL (
                    SELECT max(event.occurred_at) approved_at
                    FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='MARKET_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='MARKET_RECORD_APPROVED'
                  ) approval ON true
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND record.survey_period_governance_state='CONFIRMED'
                    AND record.survey_year=:year AND record.region_code IN(SELECT code FROM scope)
                ), effective_market_record AS (
                  SELECT * FROM effective_market_candidate WHERE effective_rank=1
                ), effective_logistics_candidate AS (
                  SELECT event.*,
                    COALESCE(approval.approved_at,event.updated_at,event.reported_at) approved_at,
                    row_number() OVER(PARTITION BY event.business_region_code,event.collection_date,
                      event.direction_code,COALESCE(event.origin_node_code,'*'),
                      COALESCE(event.destination_node_code,'*'),event.source_organization
                      ORDER BY event.version DESC,event.event_id DESC) effective_rank
                  FROM logistics.route_event event
                  LEFT JOIN LATERAL (
                    SELECT max(outbox.occurred_at) approved_at
                    FROM platform.business_event_outbox outbox
                    WHERE outbox.aggregate_type='LOGISTICS_RECORD'
                      AND outbox.aggregate_id=event.event_id::text
                      AND outbox.action_code='LOGISTICS_RECORD_APPROVED'
                  ) approval ON true
                  WHERE event.product_code=:product AND event.status_code='APPROVED'
                    AND event.survey_period_governance_state='CONFIRMED'
                    AND event.survey_year=:year AND event.direction_code IN('INFLOW','OUTFLOW')
                    AND COALESCE(event.business_region_code,CASE event.direction_code
                      WHEN 'INFLOW' THEN event.destination_region_code ELSE event.origin_region_code END)
                      IN(SELECT code FROM scope)
                ), effective_logistics_event AS (
                  SELECT * FROM effective_logistics_candidate WHERE effective_rank=1
                ), governed_metric_fact AS (
                  SELECT fact.*,
                    CASE definition.source_domain
                      WHEN 'PRODUCTION' THEN production.approved_at
                      WHEN 'MARKET' THEN market.approved_at
                      ELSE fact.reported_at
                    END approved_at
                  FROM overview.approved_annual_metric_fact fact
                  JOIN overview.indicator_definition definition ON definition.code=fact.metric_code
                  LEFT JOIN effective_production_record production ON production.record_id=fact.record_id
                  LEFT JOIN effective_market_record market ON market.record_id=fact.record_id
                  WHERE fact.product_code=:product AND fact.region_code IN(SELECT code FROM scope)
                    AND EXTRACT(YEAR FROM fact.occurred_on)=:year
                    AND (
                      definition.source_domain='PRODUCTION' AND EXISTS(
                        SELECT 1 FROM effective_production_record record
                        WHERE record.record_id=fact.record_id)
                      OR definition.source_domain='MARKET' AND EXISTS(
                        SELECT 1 FROM effective_market_record record
                        WHERE record.record_id=fact.record_id))
                ), aggregated_metric_fact AS (
                  SELECT fact.metric_code,
                    CASE definition.aggregation_code
                      WHEN 'AVERAGE' THEN AVG(fact.value) ELSE SUM(fact.value) END value,
                    COUNT(*) source_count,MAX(fact.approved_at) data_cutoff
                  FROM governed_metric_fact fact
                  JOIN overview.indicator_definition definition ON definition.code=fact.metric_code
                  GROUP BY fact.metric_code,definition.aggregation_code
                )
                SELECT definition.code,definition.name,definition.unit_code,definition.source_domain,
                  definition.formula,definition.source_relation,definition.calculation_version,
                  CASE definition.code
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 END) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='INFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 END) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='OUTFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_AVERAGE_FREIGHT_RATE' THEN (SELECT AVG(fact.value) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE fact.fact_code='FREIGHT_RATE')
                    WHEN 'REGION_SURPLUS' THEN NULL
                    ELSE (SELECT value FROM aggregated_metric_fact metric WHERE metric.metric_code=definition.code)
                  END AS value,
                  CASE definition.code
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT COUNT(*) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='INFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT COUNT(*) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='OUTFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_AVERAGE_FREIGHT_RATE' THEN (SELECT COUNT(*) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE fact.fact_code='FREIGHT_RATE')
                    WHEN 'REGION_SURPLUS' THEN 0
                    ELSE COALESCE((SELECT source_count FROM aggregated_metric_fact metric WHERE metric.metric_code=definition.code),0)
                  END AS source_count,
                  CASE definition.code
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT MAX(event.approved_at) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='INFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT MAX(event.approved_at) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE event.direction_code='OUTFLOW' AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_AVERAGE_FREIGHT_RATE' THEN (SELECT MAX(event.approved_at) FROM effective_logistics_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id WHERE fact.fact_code='FREIGHT_RATE')
                    WHEN 'REGION_SURPLUS' THEN NULL
                    ELSE (SELECT data_cutoff FROM aggregated_metric_fact metric WHERE metric.metric_code=definition.code)
                  END AS data_cutoff
                FROM overview.indicator_definition definition ORDER BY definition.sort_order
                """).param("region", regionCode).param("product", productCode).param("year", year)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> {
                    long sourceCount = row.getLong("source_count");
                    OffsetDateTime cutoff = row.getObject("data_cutoff", OffsetDateTime.class);
                    return new OverviewIndicator(row.getString("code"), row.getString("name"),
                            row.getString("unit_code"), decimal(row.getBigDecimal("value")),
                            row.getString("source_domain"), sourceCount,
                            sourcePath(row.getString("source_domain")), row.getString("formula"),
                            row.getString("source_relation"), chineseTime(cutoff),
                            coverageScope(regionCode, productCode, year),
                            sourceCount > 0 ? "AVAILABLE" : "NO_APPROVED_SOURCES",
                            row.getString("calculation_version"));
                }).list();
        ObservableAnalysisSnapshot snapshot = observableAnalysisRepository.load(
                new ObservableAnalysisScope(
                        productCode,
                        regionCode == null
                                ? ObservableAnalysisScope.ALL_AUTHORIZED_REGIONS
                                : regionCode,
                        year,
                        null,
                        null,
                        null),
                authorizedRegionCodes);
        return indicators.stream()
                .map(indicator -> observableSupplyIndicator(indicator, snapshot))
                .toList();
    }

    private OverviewIndicator observableSupplyIndicator(
            OverviewIndicator indicator,
            ObservableAnalysisSnapshot snapshot) {
        var calculation = snapshot.supply().calculation();
        BigDecimal value;
        long sourceCount;
        String formula;
        switch (indicator.code()) {
            case "SUPPLY_TOTAL_SUPPLY" -> {
                value = calculation.totalSupplyTonnes();
                sourceCount = snapshot.coverage().recordCount();
                formula = "核定期初库存、预计总产和物流流入自动合计";
            }
            case "SUPPLY_TOTAL_USE" -> {
                value = calculation.totalUseTonnes();
                sourceCount = snapshot.coverage().recordCount();
                formula = "核定自用、物流流出和推算其他消耗自动合计";
            }
            case "SUPPLY_ADOPTED_ENDING_INVENTORY" -> {
                value = calculation.endingObservableInventoryTonnes();
                sourceCount = snapshot.supply().inventory().adoptedRecordCount();
                formula = "核定生产端和企业端期末库存自动合计";
            }
            default -> {
                return indicator;
            }
        }
        String coverageStatus = switch (calculation.qualityState()) {
            case AVAILABLE -> "AVAILABLE";
            case NO_APPROVED_DATA -> "NO_APPROVED_SOURCES";
            default -> "PARTIAL";
        };
        return new OverviewIndicator(
                indicator.code(),
                indicator.name(),
                indicator.unitCode(),
                decimal(toTenThousandTonnes(value)),
                indicator.sourceDomain(),
                sourceCount,
                "/api/v1/observable-analysis/snapshots",
                formula,
                "审核通过的产情、市场和物流数据自动计算结果",
                chineseTime(snapshot.dataCutoffAt()),
                indicator.coverageScope(),
                coverageStatus,
                "审核数据自动供需口径第1版");
    }

    private static BigDecimal toTenThousandTonnes(BigDecimal tonnes) {
        return tonnes == null ? null : tonnes.divide(TONNES_PER_TEN_THOUSAND);
    }

    @Override
    public OverviewDashboard dashboard(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        OverviewDashboard.Scope scope = dashboardScope(productCode, year, regionCode, authorizedRegionCodes);
        DashboardYoYData yearOnYear = dashboardYearOnYear(productCode, year, regionCode, authorizedRegionCodes);
        return new OverviewDashboard(
                scope,
                dashboardMetrics(productCode, year, regionCode, authorizedRegionCodes),
                dashboardRegionPath(regionCode),
                dashboardPriceTrend(productCode, year, regionCode, authorizedRegionCodes),
                dashboardProductStructure(productCode, year, regionCode, authorizedRegionCodes),
                dashboardRegionActivity(productCode, year, regionCode, authorizedRegionCodes),
                List.of(),
                yearOnYear.cultivatedArea(),
                yearOnYear.output(),
                dashboardBusinessTables(productCode, year, regionCode, authorizedRegionCodes));
    }

    @Override
    public List<AnnualComparisonPoint> annualComparison(String productCode, String cultivarCode, String regionCode,
            int surveyYear, AnnualComparisonDefinition definition, Set<String> authorizedRegionCodes) {
        String publication = "APPROVED_" + definition.sourceDomain() + "_RECORD:v";
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE region.code=:region
                  UNION
                  SELECT child.code FROM platform.region child
                  JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), comparison_year AS (
                  SELECT (:surveyYear-year_offset)::text business_year,
                    make_date(:surveyYear-year_offset,1,1) starts_on,
                    make_date(:surveyYear-year_offset,12,31) ends_on
                  FROM generate_series(0,3) year_offset
                ), approved AS (
                  SELECT record.* FROM (
                    SELECT fact.* FROM overview.approved_annual_metric_fact fact
                    UNION ALL
                    SELECT CASE
                        WHEN route_fact.fact_code='FREIGHT_RATE' THEN 'LOGISTICS_AVERAGE_FREIGHT_RATE'
                        WHEN event.direction_code='INFLOW' THEN 'LOGISTICS_INFLOW_VOLUME'
                        ELSE 'LOGISTICS_OUTFLOW_VOLUME' END metric_code,
                      event.product_code,NULL::varchar cultivar_code,
                      COALESCE(event.business_region_code,CASE event.direction_code
                        WHEN 'INFLOW' THEN event.destination_region_code ELSE event.origin_region_code END) region_code,
                      make_date(event.survey_year,COALESCE(event.survey_month,1),1) occurred_on,
                      event.event_id::text record_id,event.version,event.reported_at,route_fact.value
                    FROM logistics.route_event event
                    JOIN logistics.route_fact route_fact ON route_fact.event_id=event.event_id
                    WHERE event.status_code='APPROVED'
                      AND event.survey_period_governance_state='CONFIRMED'
                      AND route_fact.fact_code IN ('ROUTE_VOLUME','FREIGHT_RATE')
                  ) record
                  WHERE record.metric_code=:metric AND record.product_code=:product
                    AND record.region_code IN (SELECT code FROM scope)
                    AND record.occurred_on BETWEEN make_date(:surveyYear-3,1,1) AND make_date(:surveyYear,12,31)
                    AND (CAST(:cultivar AS varchar) IS NULL
                      OR record.cultivar_code=CAST(:cultivar AS varchar))
                    AND (
                      :sourceDomain='PRODUCTION' AND EXISTS(
                        SELECT 1 FROM production.effective_approved_production_record source
                        WHERE source.record_id=record.record_id)
                      OR :sourceDomain='MARKET' AND EXISTS(
                        SELECT 1 FROM market.effective_approved_market_record source
                        WHERE source.record_id=record.record_id)
                      OR :sourceDomain='LOGISTICS' AND EXISTS(
                        SELECT 1 FROM logistics.route_event source
                        WHERE source.event_id::text=record.record_id
                          AND source.status_code='APPROVED'
                          AND source.survey_period_governance_state='CONFIRMED'))
                )
                SELECT comparison_year.business_year,
                  CASE WHEN :aggregation='AVERAGE' THEN AVG(record.value) ELSE SUM(record.value) END value,
                  COUNT(record.record_id) source_count,
                  MAX(record.version) source_version,MAX(record.reported_at) data_cutoff
                FROM comparison_year LEFT JOIN approved record
                  ON record.occurred_on BETWEEN comparison_year.starts_on AND comparison_year.ends_on
                GROUP BY comparison_year.business_year,comparison_year.ends_on
                ORDER BY comparison_year.ends_on DESC
                """)
                .param("region", regionCode).param("surveyYear", surveyYear).param("product", productCode)
                .param("metric", definition.code()).param("aggregation", definition.aggregationCode())
                .param("cultivar", cultivarCode).param("sourceDomain", definition.sourceDomain())
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> {
                    long count = row.getLong("source_count");
                    OffsetDateTime cutoff = row.getObject("data_cutoff", OffsetDateTime.class);
                    return new AnnualComparisonPoint(row.getString("business_year"), row.getBigDecimal("value"),
                            count == 0 ? null : publication + row.getLong("source_version"),
                            chineseTime(cutoff),
                            count == 0 ? "NO_APPROVED_RECORDS" : null);
                }).list();
    }

    private OverviewDashboard.Scope dashboardScope(
            String productCode, int year, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql(AUTHORIZED_REQUEST_SCOPE + """
                , effective_production_record AS (
                  SELECT record.record_id,record.region_code,record.last_modified_by,
                    COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                  LEFT JOIN LATERAL (
                    SELECT max(event.occurred_at) approved_at
                    FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='PRODUCTION_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='PRODUCTION_RECORD_APPROVED'
                  ) approval ON true
                  WHERE record.product_code=:product AND record.region_code IN(SELECT code FROM scope)
                    AND record.survey_year=:year
                ), effective_market_candidate AS (
                  SELECT record.record_id,record.region_code,record.last_modified_by,
                    COALESCE(approval.approved_at,record.updated_at,record.reported_at) approved_at,
                    row_number() OVER(PARTITION BY record.region_code,
                      COALESCE(record.party_id::text,record.sample_point_id::text,record.record_id),
                      record.trade_date,record.trade_direction
                      ORDER BY record.version DESC,record.record_id DESC) effective_rank
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                  LEFT JOIN LATERAL (
                    SELECT max(event.occurred_at) approved_at
                    FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='MARKET_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='MARKET_RECORD_APPROVED'
                  ) approval ON true
                  WHERE record.product_code=:product AND record.region_code IN(SELECT code FROM scope)
                    AND record.status_code='APPROVED' AND record.survey_year=:year
                    AND record.survey_period_governance_state='CONFIRMED'
                ), effective_market_record AS (
                  SELECT record_id,region_code,last_modified_by,approved_at
                  FROM effective_market_candidate WHERE effective_rank=1
                ), effective_logistics_candidate AS (
                  SELECT event.event_id::text record_id,event.business_region_code region_code,
                    event.last_modified_by,
                    COALESCE(approval.approved_at,event.updated_at,event.reported_at) approved_at,
                    row_number() OVER(PARTITION BY event.business_region_code,event.collection_date,
                      event.direction_code,COALESCE(event.origin_node_code,'*'),
                      COALESCE(event.destination_node_code,'*'),event.source_organization
                      ORDER BY event.version DESC,event.event_id DESC) effective_rank
                  FROM logistics.route_event event
                  LEFT JOIN LATERAL (
                    SELECT max(outbox.occurred_at) approved_at
                    FROM platform.business_event_outbox outbox
                    WHERE outbox.aggregate_type='LOGISTICS_RECORD'
                      AND outbox.aggregate_id=event.event_id::text
                      AND outbox.action_code='LOGISTICS_RECORD_APPROVED'
                  ) approval ON true
                  WHERE event.product_code=:product AND event.status_code='APPROVED'
                    AND event.business_region_code IN(SELECT code FROM scope)
                    AND event.survey_year=:year AND event.survey_period_governance_state='CONFIRMED'
                    AND event.direction_code IN('INFLOW','OUTFLOW')
                ), effective_logistics_event AS (
                  SELECT record_id,region_code,last_modified_by,approved_at
                  FROM effective_logistics_candidate WHERE effective_rank=1
                ), business_record AS (
                  SELECT * FROM effective_production_record
                  UNION ALL SELECT * FROM effective_market_record
                  UNION ALL SELECT * FROM effective_logistics_event
                )
                SELECT
                  COUNT(*) FILTER(WHERE region.administrative_level='COUNTY') county_count,
                  COUNT(*) FILTER(WHERE region.administrative_level='TOWNSHIP') township_count,
                  COUNT(*) FILTER(WHERE region.administrative_level='VILLAGE') village_count,
                  (SELECT COUNT(DISTINCT security_user.work_unit_code)
                     FROM business_record record
                     JOIN platform.security_user security_user ON security_user.subject_id=record.last_modified_by
                    ) reporting_unit_count,
                  (SELECT COUNT(*) FROM business_record) approved_record_count,
                  (SELECT MAX(approved_at) FROM business_record) latest_updated_at
                FROM scope JOIN platform.region region ON region.code=scope.code
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> {
                    OffsetDateTime updatedAt = row.getObject("latest_updated_at", OffsetDateTime.class);
                    return new OverviewDashboard.Scope(
                            row.getLong("county_count"), row.getLong("township_count"), row.getLong("village_count"),
                            row.getLong("reporting_unit_count"), row.getLong("approved_record_count"),
                            chineseTime(updatedAt));
                }).single();
    }

    private List<OverviewDashboard.Metric> dashboardMetrics(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<OverviewDashboard.Metric> metrics = new java.util.ArrayList<>(indicators(
                productCode, regionCode, year, authorizedRegionCodes).stream()
                .filter(indicator -> switch (indicator.code()) {
                    case "PRODUCTION_CULTIVATED_AREA", "PRODUCTION_ESTIMATED_OUTPUT",
                            "MARKET_AVERAGE_PURCHASE_PRICE", "MARKET_AVERAGE_SALE_PRICE",
                            "LOGISTICS_INFLOW_VOLUME",
                            "LOGISTICS_OUTFLOW_VOLUME", "LOGISTICS_AVERAGE_FREIGHT_RATE", "SUPPLY_TOTAL_SUPPLY",
                            "SUPPLY_TOTAL_USE", "SUPPLY_ADOPTED_ENDING_INVENTORY" -> true;
                    default -> false;
                })
                .map(indicator -> new OverviewDashboard.Metric(
                        indicator.code(), indicator.name(), indicator.unitCode(),
                        indicator.sourceCount() == 0 ? null : indicator.value(), indicator.sourceCount(),
                        indicator.dataCutoff(), indicator.coverageStatus(), indicator.formula(),
                        indicator.sourcePath(), indicator.sourceRelation(), indicator.coverageScope(),
                        indicator.calculationVersion(), List.of()))
                .toList());
        RegionSurplusSourceSelection surplusSources = regionSurplusSources(
                productCode, year, regionCode, authorizedRegionCodes);
        RegionSurplusCalculation surplus = new RegionSurplusCalculator().calculate(
                surplusSources.sources(), surplusSources.calculationVersion());
        metrics.add(new OverviewDashboard.Metric(
                "REGION_SURPLUS", "地区余粮", "吨",
                surplus.valueTonnes() == null ? null : decimal(surplus.valueTonnes()),
                surplus.sourceCount(),
                chineseDate(surplus.dataCutoff()),
                surplus.coverageStatus(),
                "按公开样本身份采用最新产情期末余粮与市场现有库存后合计",
                "/api/v1/overview/dashboard",
                "产情与市场核定记录",
                coverageScope(regionCode, productCode, year),
                surplus.calculationVersion(), surplus.auditSources()));
        return List.copyOf(metrics);
    }

    private RegionSurplusSourceSelection regionSurplusSources(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<RegionSurplusContract> contracts = jdbc.sql("""
                        SELECT version_code,name
                        FROM overview.region_surplus_calculation_contract
                        WHERE status_code='ACTIVE' AND effective_from<=CURRENT_TIMESTAMP
                          AND (effective_to IS NULL OR CURRENT_TIMESTAMP<effective_to)
                        ORDER BY version_code
                        """).query((row, ignored) -> new RegionSurplusContract(
                        row.getString("version_code"), row.getString("name"))).list();
        if (contracts.size() != 1) return new RegionSurplusSourceSelection(List.of(), null);
        RegionSurplusContract selected = contracts.getFirst();
        List<RegionSurplusSource> sources = jdbc.sql(AUTHORIZED_REQUEST_SCOPE + """
                , selected_contract AS (
                  SELECT CAST(:contractVersion AS varchar) version_code,
                         CAST(:contractName AS varchar) name
                ), production_metadata AS (
                  SELECT metadata.record_id,
                    max(metadata.value) FILTER(WHERE metadata.field_code='PROD_ENDING_INVENTORY') ending_value
                  FROM production.production_record_submission_metadata metadata
                  WHERE metadata.field_code='PROD_ENDING_INVENTORY'
                  GROUP BY metadata.record_id
                ), sources AS (
                  SELECT 'PRODUCTION'::varchar source_domain,record.record_id source_record_id,
                    record.version source_version,identity.business_identity subject_key,
                    NULL::varchar inventory_holder_key,
                    identity.business_identity cargo_owner_key,
                    'PRODUCTION_SURPLUS'::varchar ownership_type,record.region_code,
                    (make_date(record.survey_year,coalesce(record.survey_month,12),1)
                      + interval '1 month - 1 day')::date::text cutoff_date,
                    metadata.ending_value,approval.occurred_at approved_at,
                    NULL::varchar contract_issue,
                    contract.name calculation_version,record.object_type_code sample_type_code
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                  JOIN production.production_record_business_identity identity
                    ON identity.record_id=record.record_id
                  JOIN production_metadata metadata ON metadata.record_id=record.record_id
                  CROSS JOIN selected_contract contract
                  LEFT JOIN LATERAL (
                    SELECT event.occurred_at FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='PRODUCTION_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='PRODUCTION_RECORD_APPROVED'
                    ORDER BY event.event_sequence DESC LIMIT 1
                  ) approval ON true
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND record.survey_year=:year AND record.survey_period_governance_state='CONFIRMED'
                    AND record.region_code IN(SELECT code FROM scope)
                    AND metadata.ending_value IS NOT NULL
                  UNION ALL
                  SELECT 'MARKET',record.record_id,record.version,
                    identity.business_identity,identity.business_identity,identity.business_identity,
                    'OWNED',record.region_code,
                    (make_date(record.survey_year,coalesce(record.survey_month,12),1)
                      + interval '1 month - 1 day')::date::text,
                    fact.value::text,approval.occurred_at,NULL::varchar,
                    contract.name,record.object_type_code
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                  JOIN market.market_record_business_identity identity
                    ON identity.record_id=record.record_id
                  JOIN market.market_record_fact fact ON fact.record_id=record.record_id
                    AND fact.fact_code='ENDING_INVENTORY'
                  LEFT JOIN LATERAL (
                    SELECT event.occurred_at FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='MARKET_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='MARKET_RECORD_APPROVED'
                    ORDER BY event.event_sequence DESC LIMIT 1
                  ) approval ON true
                  CROSS JOIN selected_contract contract
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND record.survey_year=:year AND record.survey_period_governance_state='CONFIRMED'
                    AND record.region_code IN(SELECT code FROM scope)
                )
                SELECT * FROM sources ORDER BY source_domain,source_record_id
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("contractVersion", selected.versionCode()).param("contractName", selected.name())
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query(this::regionSurplusSource).list();
        return new RegionSurplusSourceSelection(sources, selected.name());
    }

    private RegionSurplusSource regionSurplusSource(ResultSet row, int ignored) throws SQLException {
        String issue = row.getString("contract_issue");
        BigDecimal value = null;
        LocalDate cutoff = null;
        try {
            String valueText = row.getString("ending_value");
            if (valueText != null) value = new BigDecimal(valueText);
        } catch (NumberFormatException exception) {
            issue = "INVALID_VALUE";
        }
        try {
            String cutoffText = row.getString("cutoff_date");
            if (cutoffText != null) cutoff = LocalDate.parse(cutoffText);
        } catch (java.time.DateTimeException exception) {
            issue = "INVALID_CUTOFF_DATE";
        }
        return new RegionSurplusSource(
                row.getString("source_domain"), row.getString("source_record_id"),
                row.getLong("source_version"), row.getString("subject_key"),
                row.getString("inventory_holder_key"), row.getString("cargo_owner_key"),
                row.getString("ownership_type"), row.getString("region_code"), cutoff, value,
                row.getObject("approved_at", OffsetDateTime.class), issue,
                row.getString("calculation_version"), row.getString("sample_type_code"));
    }

    private record RegionSurplusContract(String versionCode, String name) {}
    private record RegionSurplusSourceSelection(
            List<RegionSurplusSource> sources, String calculationVersion) {}

    private List<OverviewBusinessTable> dashboardBusinessTables(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        return List.of(
                productionBusinessTable(productCode, year, regionCode, authorizedRegionCodes),
                marketBusinessTable(productCode, year, regionCode, authorizedRegionCodes),
                logisticsBusinessTable(productCode, year, regionCode, authorizedRegionCodes),
                supplyBusinessTable(productCode, year, regionCode, authorizedRegionCodes));
    }

    private OverviewBusinessTable productionBusinessTable(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<OverviewBusinessTable.Column> columns = jdbc.sql("""
                WITH requested(code,indicator_code,sort_order) AS (VALUES
                  ('PROD_AREA_MU','PRODUCTION_CULTIVATED_AREA',10),
                  ('PROD_HARVEST_AREA_MU',NULL,20),
                  ('PROD_AFFECTED_AREA_MU',NULL,30),
                  ('PROD_YIELD_PER_MU','PRODUCTION_AVERAGE_YIELD_PER_MU',40),
                  ('PROD_ESTIMATED_OUTPUT','PRODUCTION_ESTIMATED_OUTPUT',50),
                  ('PROD_ENDING_INVENTORY',NULL,60)
                )
                SELECT requested.code,
                  COALESCE(field.name,fact.label) label,
                  COALESCE(indicator.unit_code,fact.unit) unit_code
                FROM requested
                LEFT JOIN platform.field_definition field ON field.code=requested.code
                LEFT JOIN overview.indicator_definition indicator
                  ON indicator.code=requested.indicator_code
                LEFT JOIN platform.production_fact_definition fact ON fact.code=requested.code
                ORDER BY requested.sort_order
                """).query((row, ignored) -> new OverviewBusinessTable.Column(
                        row.getString("code"), row.getString("label"), row.getString("unit_code"))).list();
        List<OverviewBusinessTable.Row> rows = jdbc.sql(BUSINESS_TABLE_SCOPE + """
                , metadata AS (
                  SELECT metadata.record_id,
                    max(CASE WHEN metadata.field_code='PROD_HARVEST_AREA_MU'
                          AND metadata.value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
                        THEN metadata.value::numeric END) harvest_area,
                    max(CASE WHEN metadata.field_code='PROD_AFFECTED_AREA_MU'
                          AND metadata.value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
                        THEN metadata.value::numeric END) affected_area,
                    max(CASE WHEN metadata.field_code='PROD_ENDING_INVENTORY'
                          AND metadata.value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
                        THEN metadata.value::numeric END) ending_inventory
                  FROM production.production_record_submission_metadata metadata
                  WHERE metadata.field_code IN (
                    'PROD_HARVEST_AREA_MU','PROD_AFFECTED_AREA_MU','PROD_ENDING_INVENTORY')
                  GROUP BY metadata.record_id
                ), approved_record AS (
                  SELECT descendant.root_code,record.record_id,identity.business_identity,
                    record.reported_at,record.cultivated_area_mu,record.estimated_output_kg,
                    metadata.harvest_area,metadata.affected_area,metadata.ending_inventory
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                  JOIN production.production_record_business_identity identity
                    ON identity.record_id=record.record_id
                  JOIN table_region_descendant descendant ON descendant.code=record.region_code
                  LEFT JOIN metadata ON metadata.record_id=record.record_id
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND record.status_code='APPROVED'
                    AND record.survey_period_governance_state='CONFIRMED'
                )
                SELECT region.code region_code,region.name region_name,
                  count(DISTINCT record.business_identity) source_count,
                  max(record.reported_at) latest_approved_at,
                  sum(record.cultivated_area_mu) cultivated_area,
                  count(record.cultivated_area_mu) cultivated_area_count,
                  sum(record.harvest_area) harvest_area,count(record.harvest_area) harvest_area_count,
                  sum(record.affected_area) affected_area,count(record.affected_area) affected_area_count,
                  sum(record.estimated_output_kg)/NULLIF(sum(record.cultivated_area_mu),0) yield_per_mu,
                  count(record.estimated_output_kg) yield_count,
                  sum(record.estimated_output_kg) estimated_output,
                  count(record.estimated_output_kg) estimated_output_count,
                  sum(record.ending_inventory) ending_inventory,
                  count(record.ending_inventory) ending_inventory_count
                FROM table_region region
                LEFT JOIN approved_record record ON record.root_code=region.code
                GROUP BY region.code,region.name,region.sort_order
                ORDER BY region.sort_order,region.name
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, ignored) -> {
                    long sourceCount = row.getLong("source_count");
                    long areaCount = row.getLong("cultivated_area_count");
                    long harvestCount = row.getLong("harvest_area_count");
                    long affectedCount = row.getLong("affected_area_count");
                    long yieldCount = row.getLong("yield_count");
                    long outputCount = row.getLong("estimated_output_count");
                    long inventoryCount = row.getLong("ending_inventory_count");
                    Map<String, OverviewBusinessTable.Cell> values = new LinkedHashMap<>();
                    values.put("PROD_AREA_MU", cell(row.getBigDecimal("cultivated_area"), areaCount));
                    values.put("PROD_HARVEST_AREA_MU", cell(row.getBigDecimal("harvest_area"), harvestCount));
                    values.put("PROD_AFFECTED_AREA_MU", cell(row.getBigDecimal("affected_area"), affectedCount));
                    values.put("PROD_YIELD_PER_MU", cell(row.getBigDecimal("yield_per_mu"), yieldCount));
                    values.put("PROD_ESTIMATED_OUTPUT", cell(row.getBigDecimal("estimated_output"), outputCount));
                    values.put("PROD_ENDING_INVENTORY", cell(row.getBigDecimal("ending_inventory"), inventoryCount));
                    return businessRow(row, sourceCount, values,
                            areaCount, harvestCount, affectedCount, yieldCount, outputCount, inventoryCount);
                }).list();
        return businessTable("PRODUCTION", "产情监测表", columns, rows);
    }

    private OverviewBusinessTable marketBusinessTable(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<OverviewBusinessTable.Column> columns = jdbc.sql("""
                WITH requested(code,sort_order) AS (VALUES
                  ('MKT_PURCHASE_BASE_PRICE',10),('MKT_SALE_BASE_PRICE',20),
                  ('PURCHASE_VOLUME',30),('SALES_VOLUME',40),
                  ('MKT_CARRIAGE_BOARD_AMOUNT',50),('MKT_FREIGHT_AMOUNT',60),
                  ('ENDING_INVENTORY',70)
                )
                SELECT requested.code,COALESCE(core.label,fact.label) label,
                  COALESCE(core.unit,fact.unit) unit_code
                FROM requested
                LEFT JOIN platform.market_core_field_definition core ON core.code=requested.code
                LEFT JOIN platform.market_fact_definition fact ON fact.code=requested.code
                ORDER BY requested.sort_order
                """).query((row, ignored) -> new OverviewBusinessTable.Column(
                        row.getString("code"), row.getString("label"), row.getString("unit_code"))).list();
        List<OverviewBusinessTable.Row> rows = jdbc.sql(BUSINESS_TABLE_SCOPE + """
                , fact AS (
                  SELECT fact.record_id,
                    max(fact.value) FILTER(WHERE fact.fact_code='PURCHASE_VOLUME') purchase_volume,
                    max(fact.value) FILTER(WHERE fact.fact_code='SALES_VOLUME') sales_volume,
                    max(fact.value) FILTER(WHERE fact.fact_code='ENDING_INVENTORY') ending_inventory
                  FROM market.market_record_fact fact
                  WHERE fact.fact_code IN ('PURCHASE_VOLUME','SALES_VOLUME','ENDING_INVENTORY')
                  GROUP BY fact.record_id
                ), approved_record AS (
                  SELECT descendant.root_code,record.record_id,record.reported_at,
                    record.purchase_base_price,record.sale_base_price,
                    record.carriage_board_amount,record.freight_amount,
                    fact.purchase_volume,fact.sales_volume,fact.ending_inventory
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                  JOIN table_region_descendant descendant ON descendant.code=record.region_code
                  LEFT JOIN fact ON fact.record_id=record.record_id
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND record.status_code='APPROVED'
                    AND record.survey_period_governance_state='CONFIRMED'
                )
                SELECT region.code region_code,region.name region_name,
                  count(record.record_id) source_count,max(record.reported_at) latest_approved_at,
                  avg(record.purchase_base_price) purchase_price,count(record.purchase_base_price) purchase_price_count,
                  avg(record.sale_base_price) sale_price,count(record.sale_base_price) sale_price_count,
                  sum(record.purchase_volume) purchase_volume,count(record.purchase_volume) purchase_volume_count,
                  sum(record.sales_volume) sales_volume,count(record.sales_volume) sales_volume_count,
                  avg(record.carriage_board_amount) carriage_board,count(record.carriage_board_amount) carriage_board_count,
                  avg(record.freight_amount) freight_amount,count(record.freight_amount) freight_amount_count,
                  sum(record.ending_inventory) ending_inventory,count(record.ending_inventory) ending_inventory_count
                FROM table_region region
                LEFT JOIN approved_record record ON record.root_code=region.code
                GROUP BY region.code,region.name,region.sort_order
                ORDER BY region.sort_order,region.name
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, ignored) -> {
                    long sourceCount = row.getLong("source_count");
                    long purchasePriceCount = row.getLong("purchase_price_count");
                    long salePriceCount = row.getLong("sale_price_count");
                    long purchaseVolumeCount = row.getLong("purchase_volume_count");
                    long salesVolumeCount = row.getLong("sales_volume_count");
                    long boardCount = row.getLong("carriage_board_count");
                    long freightCount = row.getLong("freight_amount_count");
                    long inventoryCount = row.getLong("ending_inventory_count");
                    Map<String, OverviewBusinessTable.Cell> values = new LinkedHashMap<>();
                    values.put("MKT_PURCHASE_BASE_PRICE", cell(row.getBigDecimal("purchase_price"), purchasePriceCount));
                    values.put("MKT_SALE_BASE_PRICE", cell(row.getBigDecimal("sale_price"), salePriceCount));
                    values.put("PURCHASE_VOLUME", cell(row.getBigDecimal("purchase_volume"), purchaseVolumeCount));
                    values.put("SALES_VOLUME", cell(row.getBigDecimal("sales_volume"), salesVolumeCount));
                    values.put("MKT_CARRIAGE_BOARD_AMOUNT", cell(row.getBigDecimal("carriage_board"), boardCount));
                    values.put("MKT_FREIGHT_AMOUNT", cell(row.getBigDecimal("freight_amount"), freightCount));
                    values.put("ENDING_INVENTORY", cell(row.getBigDecimal("ending_inventory"), inventoryCount));
                    return businessRow(row, sourceCount, values, purchasePriceCount, salePriceCount,
                            purchaseVolumeCount, salesVolumeCount, boardCount, freightCount, inventoryCount);
                }).list();
        return businessTable("MARKET", "市场监测表", columns, rows);
    }

    private OverviewBusinessTable logisticsBusinessTable(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<OverviewBusinessTable.Column> columns = jdbc.sql("""
                WITH requested(code,sort_order) AS (VALUES
                  ('LOG_TRANSPORT_MODE',10),('LOG_DIRECTION',20),('LOG_ROUTE_VOLUME',30),
                  ('LOG_FREIGHT_RATE',40),('LOG_BOARD_PRICE',50)
                )
                SELECT requested.code,definition.label,definition.unit unit_code
                FROM requested
                JOIN platform.logistics_core_field_definition definition ON definition.code=requested.code
                ORDER BY requested.sort_order
                """).query((row, ignored) -> new OverviewBusinessTable.Column(
                        row.getString("code"), row.getString("label"), row.getString("unit_code"))).list();
        List<OverviewBusinessTable.Row> rows = jdbc.sql(BUSINESS_TABLE_SCOPE + """
                , fact AS (
                  SELECT fact.event_id,
                    max(CASE WHEN fact.fact_code='ROUTE_VOLUME' THEN CASE fact.unit_code
                      WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 END END) route_volume,
                    max(fact.value) FILTER(WHERE fact.fact_code='FREIGHT_RATE') freight_rate,
                    max(fact.value) FILTER(WHERE fact.fact_code='BOARD_PRICE') board_price
                  FROM logistics.route_fact fact
                  WHERE fact.fact_code IN ('ROUTE_VOLUME','FREIGHT_RATE','BOARD_PRICE')
                  GROUP BY fact.event_id
                ), approved_event AS (
                  SELECT descendant.root_code,event.event_id,event.reported_at,
                    mode.name transport_mode,direction.label direction_label,
                    fact.route_volume,fact.freight_rate,fact.board_price
                  FROM logistics.route_event event
                  JOIN table_region_descendant descendant ON descendant.code=COALESCE(
                    event.business_region_code,CASE event.direction_code
                      WHEN 'INFLOW' THEN event.destination_region_code ELSE event.origin_region_code END)
                  JOIN platform.transport_mode mode ON mode.code=event.transport_mode_code
                  JOIN platform.logistics_core_field_option direction
                    ON direction.field_code='LOG_DIRECTION' AND direction.value=event.direction_code
                  LEFT JOIN fact ON fact.event_id=event.event_id
                  WHERE event.product_code=:product AND event.survey_year=:year
                    AND event.status_code='APPROVED'
                    AND event.survey_period_governance_state='CONFIRMED'
                )
                SELECT region.code region_code,region.name region_name,
                  count(event.event_id) source_count,max(event.reported_at) latest_approved_at,
                  string_agg(DISTINCT event.transport_mode,'、' ORDER BY event.transport_mode) transport_mode,
                  count(event.transport_mode) transport_mode_count,
                  string_agg(DISTINCT event.direction_label,'、' ORDER BY event.direction_label) direction_label,
                  count(event.direction_label) direction_count,
                  sum(event.route_volume) route_volume,count(event.route_volume) route_volume_count,
                  avg(event.freight_rate) freight_rate,count(event.freight_rate) freight_rate_count,
                  avg(event.board_price) board_price,count(event.board_price) board_price_count
                FROM table_region region
                LEFT JOIN approved_event event ON event.root_code=region.code
                GROUP BY region.code,region.name,region.sort_order
                ORDER BY region.sort_order,region.name
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, ignored) -> {
                    long sourceCount = row.getLong("source_count");
                    long modeCount = row.getLong("transport_mode_count");
                    long directionCount = row.getLong("direction_count");
                    long volumeCount = row.getLong("route_volume_count");
                    long freightCount = row.getLong("freight_rate_count");
                    long boardCount = row.getLong("board_price_count");
                    Map<String, OverviewBusinessTable.Cell> values = new LinkedHashMap<>();
                    values.put("LOG_TRANSPORT_MODE", textCell(row.getString("transport_mode"), modeCount));
                    values.put("LOG_DIRECTION", textCell(row.getString("direction_label"), directionCount));
                    values.put("LOG_ROUTE_VOLUME", cell(row.getBigDecimal("route_volume"), volumeCount));
                    values.put("LOG_FREIGHT_RATE", cell(row.getBigDecimal("freight_rate"), freightCount));
                    values.put("LOG_BOARD_PRICE", cell(row.getBigDecimal("board_price"), boardCount));
                    return businessRow(row, sourceCount, values,
                            modeCount, directionCount, volumeCount, freightCount, boardCount);
                }).list();
        return businessTable("LOGISTICS", "物流监测表", columns, rows);
    }

    private OverviewBusinessTable supplyBusinessTable(
            String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<OverviewBusinessTable.Column> columns = jdbc.sql("""
                WITH requested(code,role_code,indicator_code,formula_code,field_code,sort_order,unit_code) AS (VALUES
                  ('OPENING_INVENTORY','OPENING_INVENTORY',NULL,NULL,NULL,10,'吨'),
                  ('LOCAL_PRODUCTION','LOCAL_PRODUCTION',NULL,NULL,NULL,20,'吨'),
                  ('EXTERNAL_INFLOW','EXTERNAL_INFLOW',NULL,NULL,NULL,30,'吨'),
                  ('EXTERNAL_OUTFLOW','EXTERNAL_OUTFLOW',NULL,NULL,NULL,40,'吨'),
                  ('SUPPLY_TOTAL_SUPPLY',NULL,'SUPPLY_TOTAL_SUPPLY',NULL,NULL,50,'吨'),
                  ('SUPPLY_TOTAL_USE',NULL,'SUPPLY_TOTAL_USE',NULL,NULL,60,'吨'),
                  ('SUPPLY_ADOPTED_ENDING_INVENTORY',NULL,'SUPPLY_ADOPTED_ENDING_INVENTORY',NULL,NULL,70,'吨'),
                  ('SUPPLY_INVENTORY_RECONCILIATION_DIFFERENCE',NULL,NULL,
                    'INVENTORY_RECONCILIATION_DIFFERENCE',NULL,80,'吨'),
                  ('SUPPLY_BALANCED',NULL,NULL,NULL,NULL,90,NULL),
                  ('SUPPLY_RESULT_STATE',NULL,NULL,NULL,'SUP_RESULT_STATE',100,NULL)
                )
                SELECT requested.code,
                  COALESCE(role.label,indicator.name,expression.label,field.name,
                    CASE requested.code WHEN 'SUPPLY_BALANCED' THEN '平衡校验' END) label,
                  requested.unit_code
                FROM requested
                LEFT JOIN supply.account_input_role role ON role.role_code=requested.role_code
                LEFT JOIN overview.indicator_definition indicator ON indicator.code=requested.indicator_code
                LEFT JOIN platform.field_definition field ON field.code=requested.field_code
                LEFT JOIN LATERAL (
                  SELECT expression.label FROM supply.formula_expression expression
                  JOIN supply.formula_version version
                    ON version.formula_version_id=expression.formula_version_id
                  WHERE expression.result_code=requested.formula_code
                  ORDER BY version.version_no DESC LIMIT 1
                ) expression ON true
                ORDER BY requested.sort_order
                """).query((row, ignored) -> new OverviewBusinessTable.Column(
                        row.getString("code"), row.getString("label"), row.getString("unit_code"))).list();
        List<OverviewBusinessTable.Row> rows = jdbc.sql(BUSINESS_TABLE_SCOPE + """
                , ranked_run AS (
                  SELECT descendant.root_code,run.*,
                    row_number() OVER(PARTITION BY descendant.root_code,run.region_code
                      ORDER BY run.created_at DESC,run.version DESC,run.calculation_run_id DESC) row_rank
                  FROM supply.calculation_run run
                  JOIN table_region_descendant descendant ON descendant.code=run.region_code
                  WHERE run.product_code=:product AND run.survey_year=:year
                    AND run.result_state='PUBLISHED'
                    AND run.temporal_governance_state='CONFIRMED'
                ), latest_run AS (
                  SELECT * FROM ranked_run WHERE row_rank=1
                ), source_value AS (
                  SELECT reference.calculation_run_id,
                    max(reference.adopted_value) FILTER(WHERE reference.role_code='OPENING_INVENTORY') opening_inventory,
                    max(reference.adopted_value) FILTER(WHERE reference.role_code='LOCAL_PRODUCTION') local_production,
                    max(reference.adopted_value) FILTER(WHERE reference.role_code='EXTERNAL_INFLOW') external_inflow,
                    max(reference.adopted_value) FILTER(WHERE reference.role_code='EXTERNAL_OUTFLOW') external_outflow
                  FROM supply.calculation_source_reference reference
                  JOIN latest_run run ON run.calculation_run_id=reference.calculation_run_id
                  GROUP BY reference.calculation_run_id
                )
                SELECT region.code region_code,region.name region_name,
                  count(run.calculation_run_id) source_count,max(run.created_at) latest_approved_at,
                  sum(source.opening_inventory) opening_inventory,count(source.opening_inventory) opening_inventory_count,
                  sum(source.local_production) local_production,count(source.local_production) local_production_count,
                  sum(source.external_inflow) external_inflow,count(source.external_inflow) external_inflow_count,
                  sum(source.external_outflow) external_outflow,count(source.external_outflow) external_outflow_count,
                  sum(run.total_supply) total_supply,count(run.total_supply) total_supply_count,
                  sum(run.total_use) total_use,count(run.total_use) total_use_count,
                  sum(run.adopted_ending_inventory) adopted_ending_inventory,
                  count(run.adopted_ending_inventory) adopted_ending_inventory_count,
                  sum(run.inventory_reconciliation_difference) reconciliation_difference,
                  count(run.inventory_reconciliation_difference) reconciliation_difference_count,
                  bool_and(run.balanced) balanced,count(run.balanced) balanced_count,
                  CASE WHEN count(run.calculation_run_id)>0 THEN '已发布' END result_state,
                  count(run.calculation_run_id) result_state_count
                FROM table_region region
                LEFT JOIN latest_run run ON run.root_code=region.code
                LEFT JOIN source_value source ON source.calculation_run_id=run.calculation_run_id
                GROUP BY region.code,region.name,region.sort_order
                ORDER BY region.sort_order,region.name
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, ignored) -> {
                    long sourceCount = row.getLong("source_count");
                    long openingCount = row.getLong("opening_inventory_count");
                    long productionCount = row.getLong("local_production_count");
                    long inflowCount = row.getLong("external_inflow_count");
                    long outflowCount = row.getLong("external_outflow_count");
                    long supplyCount = row.getLong("total_supply_count");
                    long useCount = row.getLong("total_use_count");
                    long inventoryCount = row.getLong("adopted_ending_inventory_count");
                    long differenceCount = row.getLong("reconciliation_difference_count");
                    long balancedCount = row.getLong("balanced_count");
                    long resultStateCount = row.getLong("result_state_count");
                    Boolean balanced = row.getObject("balanced", Boolean.class);
                    Map<String, OverviewBusinessTable.Cell> values = new LinkedHashMap<>();
                    values.put("OPENING_INVENTORY", cell(row.getBigDecimal("opening_inventory"), openingCount));
                    values.put("LOCAL_PRODUCTION", cell(row.getBigDecimal("local_production"), productionCount));
                    values.put("EXTERNAL_INFLOW", cell(row.getBigDecimal("external_inflow"), inflowCount));
                    values.put("EXTERNAL_OUTFLOW", cell(row.getBigDecimal("external_outflow"), outflowCount));
                    values.put("SUPPLY_TOTAL_SUPPLY", cell(row.getBigDecimal("total_supply"), supplyCount));
                    values.put("SUPPLY_TOTAL_USE", cell(row.getBigDecimal("total_use"), useCount));
                    values.put("SUPPLY_ADOPTED_ENDING_INVENTORY",
                            cell(row.getBigDecimal("adopted_ending_inventory"), inventoryCount));
                    values.put("SUPPLY_INVENTORY_RECONCILIATION_DIFFERENCE",
                            cell(row.getBigDecimal("reconciliation_difference"), differenceCount));
                    values.put("SUPPLY_BALANCED", textCell(balanced == null ? null
                            : balanced ? "已平衡" : "未平衡", balancedCount));
                    values.put("SUPPLY_RESULT_STATE", textCell(row.getString("result_state"), resultStateCount));
                    return businessRow(row, sourceCount, values, openingCount, productionCount,
                            inflowCount, outflowCount, supplyCount, useCount, inventoryCount,
                            differenceCount, balancedCount, resultStateCount);
                }).list();
        return businessTable("SUPPLY", "供需平衡表", columns, rows);
    }

    private static OverviewBusinessTable businessTable(
            String code, String title, List<OverviewBusinessTable.Column> columns,
            List<OverviewBusinessTable.Row> rows) {
        String coverage = rows.stream().anyMatch(row -> row.sourceCount() > 0)
                ? "AVAILABLE" : "NO_APPROVED_SOURCES";
        return new OverviewBusinessTable(code, title, coverage, columns, rows);
    }

    private static OverviewBusinessTable.Row businessRow(
            ResultSet row, long sourceCount, Map<String, OverviewBusinessTable.Cell> values,
            long... valueSourceCounts) throws SQLException {
        return new OverviewBusinessTable.Row(
                row.getString("region_code"), row.getString("region_name"), sourceCount,
                chineseTime(row.getObject("latest_approved_at", OffsetDateTime.class)),
                completenessStatus(sourceCount, valueSourceCounts), values);
    }

    private static OverviewBusinessTable.Cell cell(BigDecimal value, long sourceCount) {
        return new OverviewBusinessTable.Cell(sourceCount == 0 ? null : decimal(value), sourceCount);
    }

    private static OverviewBusinessTable.Cell textCell(String value, long sourceCount) {
        return new OverviewBusinessTable.Cell(sourceCount == 0 ? null : value, sourceCount);
    }

    private static String completenessStatus(long sourceCount, long... valueSourceCounts) {
        if (sourceCount == 0) return "NO_APPROVED_SOURCES";
        for (long valueSourceCount : valueSourceCounts) {
            if (valueSourceCount == 0) return "PARTIAL";
        }
        return "COMPLETE";
    }

    private List<OverviewOption> dashboardRegionPath(String regionCode) {
        if (regionCode == null) return List.of();
        return jdbc.sql("""
                WITH RECURSIVE path AS (
                  SELECT region.code,region.name,region.parent_code,0 depth
                  FROM platform.region region
                  JOIN platform.monitoring_scope_region scope ON scope.region_code=region.code
                  WHERE scope.scope_code='FORMAL_BUSINESS' AND scope.included AND region.code=:region
                  UNION ALL
                  SELECT parent.code,parent.name,parent.parent_code,path.depth+1
                  FROM platform.region parent JOIN path ON path.parent_code=parent.code
                )
                SELECT code,name FROM path ORDER BY depth DESC
                """).param("region", regionCode)
                .query((row, index) -> new OverviewOption(row.getString("code"), row.getString("name"))).list();
    }

    private List<OverviewDashboard.PriceTrendPoint> dashboardPriceTrend(
            String productCode, int year, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql(AUTHORIZED_REQUEST_SCOPE + """
                SELECT to_char(date_trunc('month',record.trade_date),'YYYY-MM') period_label,
                       AVG(record.actual_trade_price) value,COUNT(*) source_count
                FROM market.market_record record
                JOIN market.effective_approved_market_record effective
                  ON effective.record_id=record.record_id
                WHERE record.product_code=:product AND record.status_code='APPROVED'
                  AND record.region_code IN(SELECT code FROM scope)
                  AND record.survey_year=:year AND record.survey_period_governance_state='CONFIRMED'
                GROUP BY date_trunc('month',record.trade_date)
                ORDER BY date_trunc('month',record.trade_date) DESC LIMIT 12
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.PriceTrendPoint(
                        row.getString("period_label"), decimal(row.getBigDecimal("value")), row.getLong("source_count")))
                .list().reversed();
    }

    private List<OverviewDashboard.ProductShare> dashboardProductStructure(
            String productCode, int year, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql(AUTHORIZED_REQUEST_SCOPE + """
                SELECT product.code product_code,product.name product_name,SUM(record.estimated_output_kg) value,
                       COUNT(*) source_count
                FROM production.production_record record
                JOIN production.effective_approved_production_record effective
                  ON effective.record_id=record.record_id
                JOIN platform.product product ON product.code=record.product_code
                WHERE record.product_code=:product AND record.status_code='APPROVED'
                  AND record.region_code IN(SELECT code FROM scope)
                  AND record.survey_year=:year AND record.survey_period_governance_state='CONFIRMED'
                GROUP BY product.code,product.name,product.sort_order ORDER BY product.sort_order
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.ProductShare(
                        row.getString("product_code"), row.getString("product_name"),
                        decimal(row.getBigDecimal("value")), "公斤", row.getLong("source_count"))).list();
    }

    private List<OverviewDashboard.RegionActivity> dashboardRegionActivity(
            String productCode, int year, String regionCode, Set<String> authorizedRegionCodes) {
        if (regionCode == null) return List.of();
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), children AS (
                  SELECT region.code,region.name,region.sort_order FROM platform.region region
                  WHERE region.parent_code=:region
                ), all_descendants(root_code,code) AS (
                  SELECT code,code FROM children
                  UNION ALL
                  SELECT descendants.root_code,child.code FROM platform.region child
                  JOIN all_descendants descendants ON child.parent_code=descendants.code
                ), descendants(root_code,code) AS (
                  SELECT DISTINCT descendants.root_code,descendants.code
                  FROM all_descendants descendants
                  JOIN monitoring_scope ON monitoring_scope.region_code=descendants.code
                ), business_record AS (
                  SELECT record.record_id,record.region_code FROM production.production_record record
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND record.status_code='APPROVED' AND record.survey_period_governance_state='CONFIRMED'
                  UNION ALL
                  SELECT record.record_id,record.region_code
                  FROM market.market_record record
                  JOIN market.effective_approved_market_record effective
                    ON effective.record_id=record.record_id
                  WHERE record.product_code=:product AND record.survey_year=:year
                    AND record.status_code='APPROVED' AND record.survey_period_governance_state='CONFIRMED'
                  UNION ALL
                  SELECT event.event_id::text,event.destination_region_code FROM logistics.route_event event
                  WHERE event.product_code=:product AND event.survey_year=:year
                    AND event.status_code='APPROVED' AND event.survey_period_governance_state='CONFIRMED'
                )
                SELECT children.code region_code,children.name region_name,
                       COUNT(record.record_id) approved_count,
                       COUNT(record.record_id) total_count
                FROM children JOIN descendants ON descendants.root_code=children.code
                LEFT JOIN business_record record ON record.region_code=descendants.code
                GROUP BY children.code,children.name,children.sort_order
                HAVING COUNT(record.record_id)>0 ORDER BY children.sort_order
                """).param("region", regionCode).param("year", year).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.RegionActivity(
                        row.getString("region_code"), row.getString("region_name"),
                        row.getLong("approved_count"), row.getLong("total_count"))).list();
    }

    private DashboardYoYData dashboardYearOnYear(
            String productCode, int year, String regionCode, Set<String> authorizedRegionCodes) {
        if (regionCode == null) return new DashboardYoYData(List.of(), List.of());
        List<YoYRow> rows = jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), children AS (
                  SELECT region.code,region.name,region.sort_order FROM platform.region region
                  WHERE region.parent_code=:region
                ), all_descendants(root_code,code) AS (
                  SELECT code,code FROM children
                  UNION ALL
                  SELECT descendants.root_code,child.code FROM platform.region child
                  JOIN all_descendants descendants ON child.parent_code=descendants.code
                ), descendants(root_code,code) AS (
                  SELECT DISTINCT descendants.root_code,descendants.code
                  FROM all_descendants descendants
                  JOIN monitoring_scope ON monitoring_scope.region_code=descendants.code
                ), production_record AS (
                  SELECT record.*,
                    CASE WHEN record.survey_year=:year THEN 'CURRENT' ELSE 'PREVIOUS' END comparison_period
                  FROM production.production_record record
                  JOIN production.effective_approved_production_record effective
                    ON effective.record_id=record.record_id
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND record.survey_period_governance_state='CONFIRMED'
                    AND record.survey_year IN (:year,:previousYear)
                )
                SELECT children.code region_code,children.name region_name,
                  SUM(record.cultivated_area_mu) FILTER(WHERE record.comparison_period='CURRENT') current_area,
                  SUM(record.cultivated_area_mu) FILTER(WHERE record.comparison_period='PREVIOUS') previous_area,
                  SUM(record.estimated_output_kg) FILTER(WHERE record.comparison_period='CURRENT') current_output,
                  SUM(record.estimated_output_kg) FILTER(WHERE record.comparison_period='PREVIOUS') previous_output,
                  COUNT(record.record_id) FILTER(WHERE record.comparison_period='CURRENT') current_count,
                  COUNT(record.record_id) FILTER(WHERE record.comparison_period='PREVIOUS') previous_count
                FROM children JOIN descendants ON descendants.root_code=children.code
                LEFT JOIN production_record record ON record.region_code=descendants.code
                GROUP BY children.code,children.name,children.sort_order
                HAVING COUNT(record.record_id)>0 ORDER BY children.sort_order
                """).param("region", regionCode).param("year", year).param("previousYear", year - 1)
                .param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new YoYRow(
                        row.getString("region_code"), row.getString("region_name"),
                        row.getBigDecimal("current_area"), row.getBigDecimal("previous_area"),
                        row.getBigDecimal("current_output"), row.getBigDecimal("previous_output"),
                        row.getLong("current_count"), row.getLong("previous_count"))).list();
        List<OverviewDashboard.YoYComparison> cultivatedArea = rows.stream().map(row ->
                new OverviewDashboard.YoYComparison(
                        row.regionCode(), row.regionName(), decimal(row.currentArea()), decimal(row.previousArea()),
                        "亩", row.currentCount(), row.previousCount())).toList();
        List<OverviewDashboard.YoYComparison> output = rows.stream().map(row ->
                new OverviewDashboard.YoYComparison(
                        row.regionCode(), row.regionName(), decimal(row.currentOutput()), decimal(row.previousOutput()),
                        "公斤", row.currentCount(), row.previousCount())).toList();
        return new DashboardYoYData(cultivatedArea, output);
    }

    private record YoYRow(
            String regionCode, String regionName,
            BigDecimal currentArea, BigDecimal previousArea,
            BigDecimal currentOutput, BigDecimal previousOutput,
            long currentCount, long previousCount) {}

    private record DashboardYoYData(
            List<OverviewDashboard.YoYComparison> cultivatedArea,
            List<OverviewDashboard.YoYComparison> output) {}

    private boolean exists(String sql, String value) { return Boolean.TRUE.equals(jdbc.sql(sql).param("value", value).query(Boolean.class).single()); }
    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    private static String sourcePath(String domain) {
        return switch (domain) {
            case "PRODUCTION" -> "/api/v1/production-records";
            case "MARKET" -> "/api/v1/market-records";
            case "LOGISTICS" -> "/api/v1/logistics-records";
            case "SUPPLY" -> "/api/v1/supply-accounts";
            default -> throw new IllegalStateException("Unknown overview source domain");
        };
    }

    private static String coverageScope(String regionCode, String productCode, int year) {
        return "所选地区及全部下级地区、所选产品、" + year + "年度";
    }

    private static String chineseTime(OffsetDateTime value) {
        if (value == null) return null;
        return value.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
    }

    private static String chineseDate(LocalDate value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
    }
}
