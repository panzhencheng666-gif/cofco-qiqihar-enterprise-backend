package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OverviewIndicator;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRegion;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOverviewRepository implements OverviewRepository {
    private final JdbcClient jdbc;
    public JdbcOverviewRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public boolean knownProduct(String productCode) { return exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)", productCode); }
    @Override public boolean knownRegion(String regionCode) { return exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)", regionCode); }
    @Override public boolean knownPeriod(String periodCode) { return exists("SELECT EXISTS(SELECT 1 FROM platform.business_period WHERE code=:value)", periodCode); }

    @Override
    public List<OverviewRegion> regions(String parentCode, String productCode, String periodCode) {
        return jdbc.sql("""
                WITH period AS (SELECT starts_on,ends_on FROM platform.business_period WHERE code=:period),
                approved AS (
                  SELECT region_code,record_id FROM production.production_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND survey_date BETWEEN starts_on AND ends_on
                  UNION ALL
                  SELECT region_code,record_id FROM market.market_record,period
                    WHERE product_code=:product AND status_code='APPROVED' AND trade_date BETWEEN starts_on AND ends_on
                  UNION ALL
                  SELECT destination_region_code,event_id::text FROM logistics.route_event,period
                    WHERE product_code=:product AND status_code='APPROVED' AND collection_date BETWEEN starts_on AND ends_on
                )
                SELECT region.code,region.name,region.parent_code,region.administrative_level,COUNT(approved.record_id) approved_count
                FROM platform.region region LEFT JOIN approved ON approved.region_code=region.code
                WHERE region.parent_code IS NOT DISTINCT FROM CAST(:parent AS varchar)
                GROUP BY region.code,region.name,region.parent_code,region.administrative_level,region.sort_order
                ORDER BY region.sort_order
                """).param("period", periodCode).param("product", productCode).param("parent", parentCode)
                .query((row, index) -> new OverviewRegion(row.getString("code"), row.getString("name"),
                        row.getString("parent_code"), row.getString("administrative_level"), row.getLong("approved_count"))).list();
    }

    @Override
    public List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear) {
        return jdbc.sql("""
                WITH RECURSIVE scope AS (
                  SELECT code FROM platform.region WHERE code=:region
                  UNION ALL SELECT child.code FROM platform.region child JOIN scope parent ON child.parent_code=parent.code
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
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT COALESCE(SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 ELSE 0 END),0) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.destination_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT COALESCE(SUM(CASE fact.unit_code WHEN '吨' THEN fact.value WHEN '万吨' THEN fact.value*10000 ELSE 0 END),0) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'SUPPLY_TOTAL_SUPPLY' THEN (SELECT COALESCE(SUM(total_supply),0) FROM latest_supply)
                    WHEN 'SUPPLY_TOTAL_USE' THEN (SELECT COALESCE(SUM(total_use),0) FROM latest_supply)
                    WHEN 'SUPPLY_ADOPTED_ENDING_INVENTORY' THEN (SELECT COALESCE(SUM(adopted_ending_inventory),0) FROM latest_supply)
                  END AS value,
                  CASE definition.code
                    WHEN 'PRODUCTION_CULTIVATED_AREA' THEN (SELECT COUNT(*) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'PRODUCTION_ESTIMATED_OUTPUT' THEN (SELECT COUNT(*) FROM production.production_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.survey_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'MARKET_AVERAGE_TRADE_PRICE' THEN (SELECT COUNT(*) FROM market.market_record record,period WHERE record.product_code=:product AND record.status_code='APPROVED' AND record.region_code IN(SELECT code FROM scope) AND record.trade_date BETWEEN period.starts_on AND period.ends_on)
                    WHEN 'LOGISTICS_INFLOW_VOLUME' THEN (SELECT COUNT(*) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.destination_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN (SELECT COUNT(*) FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id,period WHERE event.product_code=:product AND event.status_code='APPROVED' AND event.origin_region_code IN(SELECT code FROM scope) AND event.collection_date BETWEEN period.starts_on AND period.ends_on AND fact.fact_code='ROUTE_VOLUME')
                    ELSE (SELECT COUNT(*) FROM latest_supply)
                  END AS source_count
                FROM overview.indicator_definition definition ORDER BY definition.sort_order
                """).param("region", regionCode).param("period", periodCode).param("product", productCode).param("year", marketingYear)
                .query((row, index) -> new OverviewIndicator(row.getString("code"), row.getString("name"),
                        row.getString("unit_code"), decimal(row.getBigDecimal("value")), row.getString("source_domain"),
                        row.getLong("source_count"), sourcePath(row.getString("source_domain")))).list();
    }

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
