package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OverviewIndicator;
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
import java.util.Optional;
import java.util.Set;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOverviewRepository implements OverviewRepository {
    private final JdbcClient jdbc;
    public JdbcOverviewRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public OverviewOptions options() {
        List<OverviewOption> products = jdbc.sql("SELECT code,name FROM platform.product ORDER BY sort_order")
                .query((row, index) -> new OverviewOption(row.getString("code"), row.getString("name"))).list();
        List<OverviewPeriodOption> periods = jdbc.sql("SELECT code,name,starts_on,ends_on FROM platform.business_period ORDER BY starts_on DESC,sort_order DESC")
                .query((row, index) -> new OverviewPeriodOption(row.getString("code"), row.getString("name"),
                        row.getObject("starts_on", LocalDate.class).toString(),
                        row.getObject("ends_on", LocalDate.class).toString())).list();
        return new OverviewOptions(products, periods);
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
            String parentCode, String productCode, String periodCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE authorized_region(code,parent_code) AS (
                  SELECT code,parent_code FROM platform.region
                  WHERE :unrestricted OR code IN (:authorizedRegions)
                ), navigable_region(code,parent_code) AS (
                  SELECT code,parent_code FROM authorized_region
                  UNION
                  SELECT parent.code,parent.parent_code FROM platform.region parent
                  JOIN navigable_region child ON child.parent_code=parent.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)),
                approved AS (
                  SELECT region_code,record_id FROM production.production_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND survey_date BETWEEN starts_on AND ends_on
                      AND (:unrestricted OR region_code IN (:authorizedRegions))
                  UNION ALL
                  SELECT region_code,record_id FROM market.market_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND trade_date BETWEEN starts_on AND ends_on
                      AND (:unrestricted OR region_code IN (:authorizedRegions))
                  UNION ALL
                  SELECT destination_region_code,event_id::text FROM logistics.route_event,period
                    WHERE product_code=:product AND status_code='APPROVED' AND collection_date BETWEEN starts_on AND ends_on
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
                """).param("period", periodCode).param("product", productCode).param("parent", parentCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewRegion(row.getString("code"), row.getString("name"),
                        row.getString("parent_code"), row.getString("administrative_level"), row.getLong("approved_count"),
                        row.getString("boundary_geo_json"), row.getString("location_geo_json"),
                        row.getString("location_review_status"), row.getBoolean("map_context_only"))).list();
    }

    @Override
    public List<OverviewRegion> locations(String ancestorCode, String level, String productCode, String periodCode,
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
                ), period AS (
                  SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)
                ), approved AS (
                  SELECT region_code,record_id FROM production.production_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND survey_date BETWEEN starts_on AND ends_on
                  UNION ALL
                  SELECT region_code,record_id FROM market.market_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND trade_date BETWEEN starts_on AND ends_on
                  UNION ALL
                  SELECT destination_region_code,event_id::text FROM logistics.route_event,period
                    WHERE product_code=:product AND status_code='APPROVED' AND collection_date BETWEEN starts_on AND ends_on
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
                .param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewRegion(row.getString("code"), row.getString("name"),
                        row.getString("parent_code"), row.getString("administrative_level"), row.getLong("approved_count"),
                        row.getString("boundary_geo_json"), row.getString("location_geo_json"),
                        row.getString("location_review_status"), false)).list();
    }

    @Override
    public List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode,
            String marketingYear, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child
                  JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=:period),
                latest_supply AS (
                  SELECT DISTINCT ON (run.region_code) run.* FROM supply.calculation_run run JOIN scope ON scope.code=run.region_code
                  WHERE run.product_code=:product AND run.marketing_year=:year AND run.result_state='FORMAL'
                  ORDER BY run.region_code,run.created_at DESC,run.calculation_run_id DESC
                )
                SELECT definition.code,definition.name,definition.unit_code,definition.source_domain,
                  CASE definition.code
                    WHEN 'PRODUCTION_CULTIVATED_AREA' THEN (SELECT COALESCE(SUM(record.cultivated_area_mu),0) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'PRODUCTION_ESTIMATED_OUTPUT' THEN (SELECT COALESCE(SUM(record.estimated_output_kg),0) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'MARKET_AVERAGE_TRADE_PRICE' THEN (SELECT COALESCE(AVG(record.actual_trade_price),0) FROM market.market_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.trade_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT COALESCE(SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 ELSE 0 END),0) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT region_code FROM monitoring_scope) AND event.destination_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT COALESCE(SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 ELSE 0 END),0) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT code FROM scope) AND event.destination_region_code IN(SELECT region_code FROM monitoring_scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'SUPPLY_TOTAL_SUPPLY' THEN (SELECT COALESCE(SUM(total_supply),0) FROM latest_supply)
                    WHEN 'SUPPLY_TOTAL_USE' THEN (SELECT COALESCE(SUM(total_use),0) FROM latest_supply)
                    WHEN 'SUPPLY_ADOPTED_ENDING_INVENTORY' THEN (SELECT COALESCE(SUM(adopted_ending_inventory),0) FROM latest_supply)
                  END AS value,
                  CASE definition.code
                    WHEN 'PRODUCTION_CULTIVATED_AREA' THEN (SELECT COUNT(*) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'PRODUCTION_ESTIMATED_OUTPUT' THEN (SELECT COUNT(*) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'MARKET_AVERAGE_TRADE_PRICE' THEN (SELECT COUNT(*) FROM market.market_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.trade_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT COUNT(*) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT region_code FROM monitoring_scope) AND event.destination_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT COUNT(*) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT code FROM scope) AND event.destination_region_code IN(SELECT region_code FROM monitoring_scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    ELSE (SELECT COUNT(*) FROM latest_supply)
                  END AS source_count
                FROM overview.indicator_definition definition ORDER BY definition.sort_order
                """).param("region", regionCode).param("period", periodCode).param("product", productCode).param("year", marketingYear)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewIndicator(row.getString("code"), row.getString("name"),
                        row.getString("unit_code"), decimal(row.getBigDecimal("value")), row.getString("source_domain"),
                        row.getLong("source_count"), sourcePath(row.getString("source_domain")))).list();
    }

    @Override
    public OverviewDashboard dashboard(
            String productCode, String periodCode, String regionCode, String marketingYear,
            Set<String> authorizedRegionCodes) {
        OverviewDashboard.Scope scope = dashboardScope(productCode, periodCode, regionCode, authorizedRegionCodes);
        DashboardYoYData yearOnYear = dashboardYearOnYear(productCode, periodCode, regionCode, authorizedRegionCodes);
        return new OverviewDashboard(
                scope,
                dashboardMetrics(productCode, periodCode, regionCode, marketingYear, authorizedRegionCodes),
                dashboardRegionPath(regionCode),
                dashboardPriceTrend(productCode, periodCode, regionCode, authorizedRegionCodes),
                dashboardProductStructure(periodCode, regionCode, authorizedRegionCodes),
                dashboardRegionActivity(productCode, periodCode, regionCode, authorizedRegionCodes),
                dashboardAlerts(productCode, periodCode, regionCode, authorizedRegionCodes),
                yearOnYear.cultivatedArea(),
                yearOnYear.output());
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
                  SELECT record.* FROM overview.approved_annual_metric_fact record
                  WHERE record.metric_code=:metric AND record.product_code=:product
                    AND record.region_code IN (SELECT code FROM scope)
                    AND record.occurred_on BETWEEN make_date(:surveyYear-3,1,1) AND make_date(:surveyYear,12,31)
                    AND (CAST(:cultivar AS varchar) IS NULL
                      OR record.cultivar_code=CAST(:cultivar AS varchar))
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
                .param("cultivar", cultivarCode).param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> {
                    long count = row.getLong("source_count");
                    OffsetDateTime cutoff = row.getObject("data_cutoff", OffsetDateTime.class);
                    return new AnnualComparisonPoint(row.getString("business_year"), row.getBigDecimal("value"),
                            count == 0 ? null : publication + row.getLong("source_version"),
                            cutoff == null ? null : DateTimeFormatter.ISO_INSTANT.format(cutoff.toInstant()),
                            count == 0 ? "NO_APPROVED_RECORDS" : null);
                }).list();
    }

    private OverviewDashboard.Scope dashboardScope(
            String productCode, String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region
                  JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child
                  JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (
                  SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)
                ), business_record AS (
                  SELECT record.record_id,record.region_code,record.status_code,record.reported_at,record.last_modified_by
                  FROM production.production_record record,period
                  WHERE record.product_code=:product AND record.region_code IN(SELECT code FROM scope)
                    AND record.survey_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT record.record_id,record.region_code,record.status_code,record.reported_at,record.last_modified_by
                  FROM market.market_record record,period
                  WHERE record.product_code=:product AND record.region_code IN(SELECT code FROM scope)
                    AND record.trade_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT event.event_id::text,event.destination_region_code,event.status_code,event.reported_at,event.last_modified_by
                  FROM logistics.route_event event,period
                  WHERE event.product_code=:product AND event.destination_region_code IN(SELECT code FROM scope)
                    AND event.collection_date BETWEEN period.starts_on AND period.ends_on
                )
                SELECT
                  COUNT(*) FILTER(WHERE region.administrative_level='COUNTY') county_count,
                  COUNT(*) FILTER(WHERE region.administrative_level='TOWNSHIP') township_count,
                  COUNT(*) FILTER(WHERE region.administrative_level='VILLAGE') village_count,
                  (SELECT COUNT(DISTINCT security_user.work_unit_code)
                     FROM business_record record
                     JOIN platform.security_user security_user ON security_user.subject_id=record.last_modified_by
                    WHERE record.status_code='APPROVED') reporting_unit_count,
                  (SELECT COUNT(*) FROM business_record WHERE status_code='APPROVED') approved_record_count,
                  (SELECT MAX(reported_at) FROM business_record WHERE status_code='APPROVED') latest_updated_at
                FROM scope JOIN platform.region region ON region.code=scope.code
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> {
                    OffsetDateTime updatedAt = row.getObject("latest_updated_at", OffsetDateTime.class);
                    return new OverviewDashboard.Scope(
                            row.getLong("county_count"), row.getLong("township_count"), row.getLong("village_count"),
                            row.getLong("reporting_unit_count"), row.getLong("approved_record_count"),
                            updatedAt == null ? null : updatedAt.toString());
                }).single();
    }

    private List<OverviewDashboard.Metric> dashboardMetrics(
            String productCode, String periodCode, String regionCode, String marketingYear,
            Set<String> authorizedRegionCodes) {
        List<OverviewDashboard.Metric> metrics = new java.util.ArrayList<>(indicators(
                productCode, regionCode, periodCode, marketingYear, authorizedRegionCodes).stream()
                .filter(indicator -> switch (indicator.code()) {
                    case "PRODUCTION_CULTIVATED_AREA", "PRODUCTION_ESTIMATED_OUTPUT",
                            "MARKET_AVERAGE_TRADE_PRICE", "SUPPLY_TOTAL_SUPPLY",
                            "SUPPLY_TOTAL_USE", "SUPPLY_ADOPTED_ENDING_INVENTORY" -> true;
                    default -> false;
                })
                .map(indicator -> new OverviewDashboard.Metric(
                        indicator.code(), indicator.name(), indicator.unitCode(),
                        indicator.value(), indicator.sourceCount()))
                .toList());
        RegionSurplusCalculation surplus = new RegionSurplusCalculator().calculate(
                regionSurplusSources(productCode, periodCode, regionCode, authorizedRegionCodes));
        metrics.add(new OverviewDashboard.Metric(
                "REGION_SURPLUS", "地区余粮", "吨",
                surplus.valueTonnes() == null ? null : decimal(surplus.valueTonnes()),
                surplus.sourceCount(),
                surplus.dataCutoff() == null ? null : surplus.dataCutoff().toString(),
                surplus.coverageStatus(), surplus.calculationVersion(), surplus.auditSources()));
        return List.copyOf(metrics);
    }

    private List<RegionSurplusSource> regionSurplusSources(
            String productCode, String periodCode, String regionCode,
            Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region
                  JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child
                  JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (
                  SELECT starts_on,ends_on FROM platform.business_period
                  WHERE code=CAST(:period AS varchar)
                ), production_metadata AS (
                  SELECT metadata.record_id,
                    max(metadata.value) FILTER(WHERE metadata.field_code='PROD_ENDING_INVENTORY') ending_value,
                    max(metadata.value) FILTER(WHERE metadata.field_code='PROD_SURPLUS_SUBJECT_CODE') subject_code,
                    max(metadata.value) FILTER(WHERE metadata.field_code='PROD_SURPLUS_CUTOFF_DATE') cutoff_date
                  FROM production.production_record_submission_metadata metadata
                  WHERE metadata.field_code IN (
                    'PROD_ENDING_INVENTORY','PROD_SURPLUS_SUBJECT_CODE','PROD_SURPLUS_CUTOFF_DATE')
                  GROUP BY metadata.record_id
                ), market_context AS (
                  SELECT context.record_id,
                    max(context.value) FILTER(WHERE context.field_code='MKT_INVENTORY_HOLDER_CODE') holder_code,
                    max(context.value) FILTER(WHERE context.field_code='MKT_INVENTORY_OWNERSHIP_TYPE') ownership_type,
                    max(context.value) FILTER(WHERE context.field_code='MKT_STORAGE_REGION_CODE') storage_region_code,
                    max(context.value) FILTER(WHERE context.field_code='MKT_CARGO_OWNER_CODE') cargo_owner_code,
                    max(context.value) FILTER(WHERE context.field_code='MKT_INVENTORY_CUTOFF_DATE') cutoff_date
                  FROM market.market_record_core_value context
                  WHERE context.field_code IN (
                    'MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
                    'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE')
                  GROUP BY context.record_id
                ), sources AS (
                  SELECT 'PRODUCTION'::varchar source_domain,record.record_id source_record_id,
                    record.version source_version,
                    metadata.subject_code subject_key,
                    NULL::varchar inventory_holder_key,
                    metadata.subject_code cargo_owner_key,
                    'PRODUCTION_SURPLUS'::varchar ownership_type,record.region_code,
                    metadata.cutoff_date,metadata.ending_value,
                    approval.occurred_at approved_at,NULL::varchar contract_issue
                  FROM production.production_record record
                  JOIN production_metadata metadata ON metadata.record_id=record.record_id
                  JOIN period ON record.survey_date BETWEEN period.starts_on AND period.ends_on
                  LEFT JOIN LATERAL (
                    SELECT event.occurred_at FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='PRODUCTION_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='PRODUCTION_RECORD_APPROVED'
                    ORDER BY event.event_sequence DESC LIMIT 1
                  ) approval ON true
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND record.region_code IN(SELECT code FROM scope)
                    AND metadata.ending_value IS NOT NULL
                  UNION ALL
                  SELECT 'MARKET',record.record_id,record.version,
                    context.holder_code,context.holder_code,context.cargo_owner_code,
                    context.ownership_type,context.storage_region_code,
                    context.cutoff_date,fact.value::text,approval.occurred_at,NULL::varchar
                  FROM market.market_record record
                  JOIN market.market_record_fact fact ON fact.record_id=record.record_id
                    AND fact.fact_code='ENDING_INVENTORY'
                  LEFT JOIN market_context context ON context.record_id=record.record_id
                  JOIN period ON record.trade_date BETWEEN period.starts_on AND period.ends_on
                  LEFT JOIN LATERAL (
                    SELECT event.occurred_at FROM platform.business_event_outbox event
                    WHERE event.aggregate_type='MARKET_RECORD'
                      AND event.aggregate_id=record.record_id
                      AND event.action_code='MARKET_RECORD_APPROVED'
                    ORDER BY event.event_sequence DESC LIMIT 1
                  ) approval ON true
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND (context.storage_region_code IN(SELECT code FROM scope)
                      OR (context.storage_region_code IS NULL AND record.region_code IN(SELECT code FROM scope)))
                )
                SELECT * FROM sources ORDER BY source_domain,source_record_id
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query(this::regionSurplusSource).list();
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
                row.getObject("approved_at", OffsetDateTime.class), issue);
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
            String productCode, String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar))
                SELECT to_char(date_trunc('month',record.trade_date),'YYYY-MM') period_label,
                       AVG(record.actual_trade_price) value,COUNT(*) source_count
                FROM market.market_record record,period
                WHERE record.product_code=:product AND record.status_code='APPROVED'
                  AND record.region_code IN(SELECT code FROM scope)
                  AND record.trade_date BETWEEN period.starts_on AND period.ends_on
                GROUP BY date_trunc('month',record.trade_date)
                ORDER BY date_trunc('month',record.trade_date) DESC LIMIT 12
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.PriceTrendPoint(
                        row.getString("period_label"), decimal(row.getBigDecimal("value")), row.getLong("source_count")))
                .list().reversed();
    }

    private List<OverviewDashboard.ProductShare> dashboardProductStructure(
            String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar))
                SELECT product.code product_code,product.name product_name,SUM(record.estimated_output_kg) value,
                       COUNT(*) source_count
                FROM production.production_record record JOIN platform.product product ON product.code=record.product_code,period
                WHERE record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope)
                  AND record.survey_date BETWEEN period.starts_on AND period.ends_on
                GROUP BY product.code,product.name,product.sort_order ORDER BY product.sort_order
                """).param("region", regionCode).param("period", periodCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.ProductShare(
                        row.getString("product_code"), row.getString("product_name"),
                        decimal(row.getBigDecimal("value")), "公斤", row.getLong("source_count"))).list();
    }

    private List<OverviewDashboard.RegionActivity> dashboardRegionActivity(
            String productCode, String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        if (regionCode == null) return List.of();
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), children AS (
                  SELECT region.code,region.name,region.sort_order FROM platform.region region
                  JOIN monitoring_scope ON monitoring_scope.region_code=region.code WHERE region.parent_code=:region
                ), descendants(root_code,code) AS (
                  SELECT code,code FROM children
                  UNION ALL
                  SELECT descendants.root_code,child.code FROM platform.region child
                  JOIN descendants ON child.parent_code=descendants.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)),
                business_record AS (
                  SELECT record.record_id,record.region_code,record.status_code FROM production.production_record record,period
                  WHERE record.product_code=:product AND record.survey_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT record.record_id,record.region_code,record.status_code FROM market.market_record record,period
                  WHERE record.product_code=:product AND record.trade_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT event.event_id::text,event.destination_region_code,event.status_code FROM logistics.route_event event,period
                  WHERE event.product_code=:product AND event.collection_date BETWEEN period.starts_on AND period.ends_on
                )
                SELECT children.code region_code,children.name region_name,
                       COUNT(record.record_id) FILTER(WHERE record.status_code='APPROVED') approved_count,
                       COUNT(record.record_id) total_count
                FROM children JOIN descendants ON descendants.root_code=children.code
                LEFT JOIN business_record record ON record.region_code=descendants.code
                GROUP BY children.code,children.name,children.sort_order
                HAVING COUNT(record.record_id)>0 ORDER BY children.sort_order
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.RegionActivity(
                        row.getString("region_code"), row.getString("region_name"),
                        row.getLong("approved_count"), row.getLong("total_count"))).list();
    }

    private List<OverviewDashboard.Alert> dashboardAlerts(
            String productCode, String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), scope(code) AS (
                  SELECT region.code FROM platform.region region JOIN monitoring_scope ON monitoring_scope.region_code=region.code
                  WHERE CAST(:region AS varchar) IS NULL OR region.code=CAST(:region AS varchar)
                  UNION
                  SELECT child.code FROM platform.region child JOIN scope parent ON child.parent_code=parent.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)),
                returned_record AS (
                  SELECT record.region_code,record.survey_date occurred_on FROM production.production_record record,period
                  WHERE record.product_code=:product AND record.status_code='RETURNED'
                    AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT record.region_code,record.trade_date FROM market.market_record record,period
                  WHERE record.product_code=:product AND record.status_code='RETURNED'
                    AND record.region_code IN(SELECT code FROM scope) AND record.trade_date BETWEEN period.starts_on AND period.ends_on
                  UNION ALL
                  SELECT event.destination_region_code,event.collection_date FROM logistics.route_event event,period
                  WHERE event.product_code=:product AND event.status_code='RETURNED'
                    AND event.destination_region_code IN(SELECT code FROM scope)
                    AND event.collection_date BETWEEN period.starts_on AND period.ends_on
                )
                SELECT 'RETURNED_RECORD' code,'WARNING' severity,region.name region_name,
                       COUNT(*)::text||'条填报记录退回补充' message,MAX(returned.occurred_on)::text occurred_on
                FROM returned_record returned JOIN platform.region region ON region.code=returned.region_code
                GROUP BY region.code,region.name ORDER BY MAX(returned.occurred_on) DESC,region.code LIMIT 5
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
                .param("unrestricted", authorizedRegionCodes.contains("*"))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewDashboard.Alert(
                        row.getString("code"), row.getString("severity"), row.getString("region_name"),
                        row.getString("message"), row.getString("occurred_on"))).list();
    }

    private DashboardYoYData dashboardYearOnYear(
            String productCode, String periodCode, String regionCode, Set<String> authorizedRegionCodes) {
        if (regionCode == null) return new DashboardYoYData(List.of(), List.of());
        List<YoYRow> rows = jdbc.sql("""
                WITH RECURSIVE monitoring_scope AS (
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE scope_code='FORMAL_BUSINESS' AND included
                    AND (:unrestricted OR region_code IN (:authorizedRegions))
                ), children AS (
                  SELECT region.code,region.name,region.sort_order FROM platform.region region
                  JOIN monitoring_scope ON monitoring_scope.region_code=region.code WHERE region.parent_code=:region
                ), descendants(root_code,code) AS (
                  SELECT code,code FROM children
                  UNION ALL
                  SELECT descendants.root_code,child.code FROM platform.region child
                  JOIN descendants ON child.parent_code=descendants.code
                  JOIN monitoring_scope ON monitoring_scope.region_code=child.code
                ), period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=CAST(:period AS varchar)),
                production_record AS (
                  SELECT record.*,
                    CASE WHEN record.survey_date BETWEEN period.starts_on AND period.ends_on THEN 'CURRENT' ELSE 'PREVIOUS' END comparison_period
                  FROM production.production_record record,period
                  WHERE record.product_code=:product AND record.status_code='APPROVED'
                    AND (record.survey_date BETWEEN period.starts_on AND period.ends_on
                      OR record.survey_date BETWEEN (period.starts_on - INTERVAL '1 year')::date
                                                AND (period.ends_on - INTERVAL '1 year')::date)
                )
                SELECT children.code region_code,children.name region_name,
                  COALESCE(SUM(record.cultivated_area_mu) FILTER(WHERE record.comparison_period='CURRENT'),0) current_area,
                  COALESCE(SUM(record.cultivated_area_mu) FILTER(WHERE record.comparison_period='PREVIOUS'),0) previous_area,
                  COALESCE(SUM(record.estimated_output_kg) FILTER(WHERE record.comparison_period='CURRENT'),0) current_output,
                  COALESCE(SUM(record.estimated_output_kg) FILTER(WHERE record.comparison_period='PREVIOUS'),0) previous_output,
                  COUNT(record.record_id) FILTER(WHERE record.comparison_period='CURRENT') current_count,
                  COUNT(record.record_id) FILTER(WHERE record.comparison_period='PREVIOUS') previous_count
                FROM children JOIN descendants ON descendants.root_code=children.code
                LEFT JOIN production_record record ON record.region_code=descendants.code
                GROUP BY children.code,children.name,children.sort_order
                HAVING COUNT(record.record_id)>0 ORDER BY children.sort_order
                """).param("region", regionCode).param("period", periodCode).param("product", productCode)
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
    private static String decimal(BigDecimal value) { return value == null ? "0" : value.stripTrailingZeros().toPlainString(); }
    private static String sourcePath(String domain) {
        return switch (domain) {
            case "PRODUCTION" -> "/api/v1/production-records";
            case "MARKET" -> "/api/v1/market-records";
            case "LOGISTICS" -> "/api/v1/logistics-records";
            case "SUPPLY" -> "/api/v1/supply-accounts";
            default -> throw new IllegalStateException("Unknown overview source domain");
        };
    }
}
