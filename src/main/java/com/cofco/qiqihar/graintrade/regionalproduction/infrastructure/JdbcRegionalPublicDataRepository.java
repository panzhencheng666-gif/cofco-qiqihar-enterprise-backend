package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfile;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfileCalculator;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalPublicDataRepository;
import java.math.BigDecimal;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalPublicIndicatorParser;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionalPublicDataRepository implements RegionalPublicDataRepository {
    private final JdbcClient jdbc;

    public JdbcRegionalPublicDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Context load(String root, int requestedYear) {
        var observations = jdbc.sql("""
                WITH latest AS (
                  SELECT metric.product_code,max(metric.data_year) AS data_year
                  FROM production.regional_public_crop_metric metric
                  JOIN production.regional_public_source source ON source.source_id=metric.source_id
                  WHERE source.active AND source.root_region_code=:root AND metric.data_year<=:year
                  GROUP BY metric.product_code
                )
                SELECT metric.product_code,metric.data_year,
                       sum(metric.planted_area_mu*source.reliability_weight)
                         FILTER (WHERE metric.planted_area_mu IS NOT NULL)
                         / nullif(sum(source.reliability_weight)
                         FILTER (WHERE metric.planted_area_mu IS NOT NULL),0) AS planted_area_mu,
                       sum(metric.yield_per_mu_kg*source.reliability_weight)
                         FILTER (WHERE metric.yield_per_mu_kg IS NOT NULL)
                         / nullif(sum(source.reliability_weight)
                         FILTER (WHERE metric.yield_per_mu_kg IS NOT NULL),0) AS yield_per_mu_kg,
                       count(DISTINCT metric.source_id) AS source_count
                FROM production.regional_public_crop_metric metric
                JOIN production.regional_public_source source ON source.source_id=metric.source_id AND source.active
                JOIN latest ON latest.product_code=metric.product_code AND latest.data_year=metric.data_year
                WHERE source.root_region_code=:root
                GROUP BY metric.product_code,metric.data_year
                ORDER BY metric.product_code
                """).param("root", root).param("year", requestedYear)
                .query((rs, n) -> new RegionalAgricultureProfileCalculator.Observation(
                        rs.getString("product_code"), rs.getBigDecimal("planted_area_mu"),
                        rs.getBigDecimal("yield_per_mu_kg"), "PUBLIC_MULTI_SOURCE", rs.getInt("data_year"),
                        rs.getInt("source_count")))
                .list();
        var sources = jdbc.sql("""
                SELECT source_id,source_type,source_name,source_url,source_class,reliability_weight,published_on,
                       last_success_at,last_status,evidence
                FROM production.regional_public_source
                WHERE active AND root_region_code IN (:root,'*')
                ORDER BY CASE source_type WHEN 'AGRICULTURE' THEN 1 WHEN 'WEATHER' THEN 2 ELSE 3 END,
                         published_on DESC NULLS LAST,source_id
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Source(
                        rs.getString("source_id"), rs.getString("source_type"), rs.getString("source_name"),
                        rs.getString("source_url"), rs.getString("source_class"),
                        rs.getBigDecimal("reliability_weight"), date(rs.getDate("published_on")),
                        instant(rs.getTimestamp("last_success_at")), rs.getString("last_status"),
                        rs.getString("evidence")))
                .list();
        var policies = jdbc.sql("""
                SELECT source_name,source_url,published_on,evidence FROM production.regional_public_source
                WHERE active AND source_type='POLICY' AND root_region_code IN (:root,'*')
                ORDER BY published_on DESC NULLS LAST
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Policy(
                        rs.getString("source_name"),
                        date(rs.getDate("published_on")), rs.getString("source_name"), rs.getString("source_url"),
                        "适用范围以原文为准", rs.getString("evidence"))).list();
        var weather = jdbc.sql("""
                SELECT mean_temperature_c,precipitation_mm,soil_moisture_percent,risk,
                       assessment,observed_at,source_id
                FROM production.regional_weather_snapshot WHERE root_region_code=:root
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Weather(
                        rs.getBigDecimal("mean_temperature_c"), rs.getBigDecimal("precipitation_mm"),
                        rs.getBigDecimal("soil_moisture_percent"), rs.getString("risk"),
                        rs.getString("assessment"), instant(rs.getTimestamp("observed_at")),
                rs.getString("source_id"))).optional().orElse(null);
        var indicators = jdbc.sql("""
                SELECT indicator.category,indicator.label,indicator.value,indicator.unit,
                       indicator.data_year,indicator.data_kind,indicator.method,
                       source.source_name,source.source_url,
                       indicator.fetched_at AS verified_at
                FROM (
                  SELECT DISTINCT ON (i.label) i.*
                  FROM production.regional_public_indicator i
                  JOIN production.regional_public_source s ON s.source_id=i.source_id
                  WHERE s.active AND i.current_evidence AND i.root_region_code=:root AND i.data_year<=:year
                    AND i.method NOT LIKE '%待核对计划原始来源%'
                  ORDER BY i.label,i.data_year DESC,s.reliability_weight DESC,i.fetched_at DESC
                ) indicator
                JOIN production.regional_public_source source ON source.source_id=indicator.source_id
                WHERE source.active AND indicator.root_region_code=:root
                ORDER BY indicator.sort_order,indicator.label
                """).param("root", root).param("year", requestedYear).query((rs, n) -> new RegionalAgricultureProfile.Indicator(
                        rs.getString("category"), rs.getString("label"), rs.getBigDecimal("value"),
                        rs.getString("unit"), rs.getInt("data_year"), rs.getString("data_kind"),
                        rs.getString("method"), rs.getString("source_name"), rs.getString("source_url"),
                        instant(rs.getTimestamp("verified_at")))).list();
        var status = jdbc.sql("""
                SELECT max(last_attempt_at) AS last_attempt,max(last_success_at) AS last_success,
                       min(next_refresh_at) AS next_refresh,
                       CASE
                         WHEN NOT bool_and(last_status IN (
                           'SUCCESS','SUCCESS_CHANGED','SUCCESS_UNCHANGED',
                           'BOOTSTRAP_VERIFIED','BOOTSTRAP_REFERENCE')) THEN 'PARTIAL'
                         WHEN bool_or(last_status='SUCCESS_CHANGED') THEN 'SUCCESS_CHANGED'
                         WHEN bool_and(last_status='SUCCESS_UNCHANGED') THEN 'SUCCESS_UNCHANGED'
                         ELSE 'SUCCESS'
                       END AS refresh_result
                FROM production.regional_public_source WHERE active AND root_region_code IN (:root,'*')
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.RefreshStatus(
                        "每日 08:30", rs.getString("refresh_result"),
                        instant(rs.getTimestamp("last_attempt")), instant(rs.getTimestamp("last_success")),
                        instant(rs.getTimestamp("next_refresh")))).single();
        return new Context(observations, status, weather, indicators, policies, sources);
    }

    @Override
    public List<RegionalAgricultureProfile.Indicator> history(String root, int year) {
        return indicatorHistory(root, year, false);
    }

    @Override
    public List<RegionalAgricultureProfile.Indicator> calculationHistory(String root, int year) {
        return indicatorHistory(root, year, true);
    }

    private List<RegionalAgricultureProfile.Indicator> indicatorHistory(String root, int year, boolean includeDerivedYield) {
        return jdbc.sql("""
                SELECT DISTINCT ON (i.label,i.unit,i.data_year) i.*,s.source_name,s.source_url
                FROM production.regional_public_indicator i
                JOIN production.regional_public_source s ON s.source_id=i.source_id
                WHERE s.active AND i.current_evidence AND i.root_region_code=:root AND i.data_year<=:year
                  AND (i.data_kind='OBSERVED' OR (:derivedYield AND i.data_kind='ESTIMATED'
                    AND i.label LIKE '%平均单产' AND i.method LIKE '同一地区同一年度同一作物，总产除以面积得到亩均产出。%'))
                  AND i.indicator_id LIKE 'auto-%'
                ORDER BY i.label,i.unit,i.data_year,s.reliability_weight DESC,i.fetched_at DESC
                """).param("root", root).param("year", year).param("derivedYield", includeDerivedYield).query((rs,n) -> new RegionalAgricultureProfile.Indicator(
                        rs.getString("category"),rs.getString("label"),rs.getBigDecimal("value"),rs.getString("unit"),
                        rs.getInt("data_year"),rs.getString("data_kind"),rs.getString("method"),
                        rs.getString("source_name"),rs.getString("source_url"),instant(rs.getTimestamp("fetched_at")))).list();
    }

    @Override
    public List<DueSource> due(Instant now) {
        return jdbc.sql("""
                SELECT source_id,root_region_code,source_type,source_name,source_url,parser_key
                FROM production.regional_public_source
                WHERE active AND parser_key<>'OPEN_METEO'
                  AND (next_refresh_at IS NULL OR next_refresh_at<=:now)
                ORDER BY source_type,source_id
                """).param("now", Timestamp.from(now)).query((rs, n) -> new DueSource(
                        rs.getString("source_id"), rs.getString("root_region_code"), rs.getString("source_type"),
                rs.getString("source_name"), rs.getString("source_url"), rs.getString("parser_key"))).list();
    }

    @Override
    @Transactional
    public List<DueSource> claimDueWeather(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                WITH claimed AS (
                  SELECT source_id
                  FROM production.regional_public_source
                  WHERE active AND parser_key='OPEN_METEO'
                    AND (
                      next_refresh_at IS NULL OR next_refresh_at<=:now
                      OR (last_status IN ('SUCCESS_CHANGED','SUCCESS_UNCHANGED')
                          AND last_success_at<=:stale
                          AND next_refresh_at>:legacyCutoff)
                    )
                  ORDER BY source_id
                  FOR UPDATE SKIP LOCKED
                  LIMIT :limit
                )
                UPDATE production.regional_public_source source
                SET last_attempt_at=:now,next_refresh_at=:lease
                FROM claimed
                WHERE source.source_id=claimed.source_id
                RETURNING source.source_id,source.root_region_code,source.source_type,
                          source.source_name,source.source_url,source.parser_key
                """).param("now", Timestamp.from(now))
                .param("stale", Timestamp.from(now.minus(15, ChronoUnit.MINUTES)))
                .param("legacyCutoff", Timestamp.from(now.plus(30, ChronoUnit.MINUTES)))
                .param("lease", Timestamp.from(leaseUntil))
                .param("limit", Math.max(1, Math.min(limit, 8)))
                .query((rs, n) -> new DueSource(
                        rs.getString("source_id"), rs.getString("root_region_code"), rs.getString("source_type"),
                        rs.getString("source_name"), rs.getString("source_url"), rs.getString("parser_key"))).list();
    }

    @Override
    public void recordPageSuccess(String id, Instant now, String hash, String excerpt) {
        updateSuccess(id, now, hash, excerpt);
        jdbc.sql("UPDATE production.regional_public_source SET evidence=:text WHERE source_id=:id")
                .param("text", excerpt).param("id",id).update();
    }

    @Override
    public void recordCropMetrics(String id, List<PublicCropMetric> metrics, Instant now) {
        for (var metric : metrics) {
            jdbc.sql("""
                    INSERT INTO production.regional_public_crop_metric(
                      source_id,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                      total_output_kg,evidence,fetched_at)
                    VALUES(:id,:year,:product,:area,:yield,:output,:evidence,:now)
                    ON CONFLICT(source_id,data_year,product_code) DO UPDATE SET
                      planted_area_mu=excluded.planted_area_mu,yield_per_mu_kg=excluded.yield_per_mu_kg,
                      total_output_kg=excluded.total_output_kg,evidence=excluded.evidence,
                      fetched_at=excluded.fetched_at
                    """).param("id", id).param("year", metric.year()).param("product", metric.productCode())
                    .param("area", metric.plantedAreaMu()).param("yield", metric.yieldPerMuKg())
                    .param("output", metric.totalOutputKg()).param("evidence", metric.evidence())
                    .param("now", Timestamp.from(now)).update();
        }
    }

    @Override
    @Transactional
    public void recordIndicators(String id, List<RegionalPublicIndicatorParser.Metric> metrics, Instant now) {
        // A successful replacement withdraws disappeared facts for that source and period.
        // Keep the rows for audit; failures and empty parses must retain the last valid set.
        for (int year : metrics.stream().mapToInt(RegionalPublicIndicatorParser.Metric::year).distinct().toArray()) {
            jdbc.sql("""
                    UPDATE production.regional_public_indicator SET current_evidence=false
                    WHERE source_id=:id AND data_year=:year AND indicator_id LIKE 'auto-%'
                    """).param("id",id).param("year",year).update();
        }
        for (var metric : metrics) {
            jdbc.sql("""
                    INSERT INTO production.regional_public_indicator(
                      indicator_id,root_region_code,category,label,value,unit,data_year,data_kind,
                      method,source_id,fetched_at,current_evidence)
                    SELECT 'auto-' || md5(:id || ':' || :year || ':' || :label),root_region_code,
                      :category,:label,:value,:unit,:year,:kind,:method,source_id,:now,true
                    FROM production.regional_public_source WHERE source_id=:id
                    ON CONFLICT(indicator_id) DO UPDATE SET
                      value=excluded.value,unit=excluded.unit,data_kind=excluded.data_kind,
                      method=excluded.method,fetched_at=excluded.fetched_at,current_evidence=true
                    """).param("id", id).param("year", metric.year()).param("category", metric.category())
                    .param("label", metric.label()).param("value", metric.value()).param("unit", metric.unit())
                    .param("kind", metric.kind()).param("method", metric.method()).param("now", Timestamp.from(now)).update();
        }
    }

    @Override
    public void recordWeatherSuccess(String id, String root, Instant observedAt,
            BigDecimal temperature, BigDecimal precipitation, BigDecimal soilMoisture,
            String risk, String assessment, Instant now, String hash, String excerpt) {
        jdbc.sql("""
                INSERT INTO production.regional_weather_snapshot(
                  root_region_code,observed_at,mean_temperature_c,precipitation_mm,
                  soil_moisture_percent,risk,assessment,source_id,fetched_at)
                VALUES(:root,:observed,:temperature,:precipitation,:soil,:risk,:assessment,:id,:now)
                ON CONFLICT(root_region_code) DO UPDATE SET
                  observed_at=excluded.observed_at,mean_temperature_c=excluded.mean_temperature_c,
                  precipitation_mm=excluded.precipitation_mm,soil_moisture_percent=excluded.soil_moisture_percent,
                  risk=excluded.risk,assessment=excluded.assessment,source_id=excluded.source_id,
                  fetched_at=excluded.fetched_at
                """).param("root", root).param("observed", Timestamp.from(observedAt))
                .param("temperature", temperature).param("precipitation", precipitation)
                .param("soil", soilMoisture).param("risk", risk).param("assessment", assessment)
                .param("id", id).param("now", Timestamp.from(now)).update();
        updateSuccess(id, now, hash, excerpt, nextWeatherRefresh(now));
    }

    private void updateSuccess(String id, Instant now, String hash, String excerpt) {
        updateSuccess(id, now, hash, excerpt, nextDailyRefresh(now));
    }

    private void updateSuccess(String id, Instant now, String hash, String excerpt, Instant next) {
        jdbc.sql("""
                UPDATE production.regional_public_source SET last_attempt_at=:now,last_success_at=:now,
                  next_refresh_at=:next,
                  last_status=CASE
                    WHEN last_content_hash IS NULL OR last_content_hash<>:hash THEN 'SUCCESS_CHANGED'
                    ELSE 'SUCCESS_UNCHANGED'
                  END,
                  last_error=NULL,
                  last_content_hash=:hash,last_excerpt=:excerpt WHERE source_id=:id
                """).param("now", Timestamp.from(now)).param("next", Timestamp.from(next))
                .param("hash", hash).param("excerpt", excerpt).param("id", id).update();
    }

    @Override
    public void recordFailure(String id, Instant now, String message) {
        jdbc.sql("""
                UPDATE production.regional_public_source SET last_attempt_at=:now,next_refresh_at=:next,
                  last_status='FAILED_USING_LAST_SUCCESS',last_error=:message WHERE source_id=:id
                """).param("now", Timestamp.from(now)).param("next", Timestamp.from(now.plus(1, ChronoUnit.HOURS).isBefore(nextDailyRefresh(now))
                        ? now.plus(1, ChronoUnit.HOURS) : nextDailyRefresh(now)))
                .param("message", message == null ? "unknown" : message.substring(0, Math.min(500, message.length())))
                .param("id", id).update();
    }

    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static String date(java.sql.Date value) { return value == null ? null : value.toLocalDate().toString(); }

    static Instant nextDailyRefresh(Instant now) {
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var local = now.atZone(zone);
        var next = local.toLocalDate().atTime(8, 30).atZone(zone);
        if (!next.toInstant().isAfter(now)) next = next.plusDays(1);
        return next.toInstant();
    }

    static Instant nextWeatherRefresh(Instant now) {
        return now.plus(15, ChronoUnit.MINUTES);
    }
}
