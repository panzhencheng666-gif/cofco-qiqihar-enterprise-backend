package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfile;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfileCalculator;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalPublicDataRepository;
import java.math.BigDecimal;
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
                SELECT DISTINCT ON (metric.product_code)
                       metric.product_code,metric.planted_area_mu,metric.yield_per_mu_kg,metric.data_year
                FROM production.regional_public_crop_metric metric
                JOIN production.regional_public_source source ON source.source_id=metric.source_id
                WHERE source.root_region_code=:root AND metric.data_year<=:year
                ORDER BY metric.product_code,metric.data_year DESC,metric.fetched_at DESC
                """).param("root", root).param("year", requestedYear)
                .query((rs, n) -> new RegionalAgricultureProfileCalculator.Observation(
                        rs.getString("product_code"), rs.getBigDecimal("planted_area_mu"),
                        rs.getBigDecimal("yield_per_mu_kg"), "PUBLIC_SOURCE", rs.getInt("data_year")))
                .list();
        var sources = jdbc.sql("""
                SELECT source_id,source_type,source_name,source_url,published_on,
                       last_success_at,last_status,evidence,last_excerpt
                FROM production.regional_public_source
                WHERE active AND root_region_code IN (:root,'*')
                ORDER BY CASE source_type WHEN 'AGRICULTURE' THEN 1 WHEN 'WEATHER' THEN 2 ELSE 3 END,
                         published_on DESC NULLS LAST,source_id
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Source(
                        rs.getString("source_id"), rs.getString("source_type"), rs.getString("source_name"),
                        rs.getString("source_url"), date(rs.getDate("published_on")),
                        instant(rs.getTimestamp("last_success_at")), rs.getString("last_status"),
                        rs.getString("last_excerpt") == null ? rs.getString("evidence") : rs.getString("last_excerpt")))
                .list();
        var policies = jdbc.sql("""
                SELECT source_name,source_url,published_on,evidence FROM production.regional_public_source
                WHERE active AND source_type='POLICY' AND root_region_code IN (:root,'*')
                ORDER BY published_on DESC NULLS LAST
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Policy(
                        rs.getString("evidence").contains("强农惠农") ? "2026年强农惠农富农政策清单" : "2026年中央一号文件农业部署",
                        date(rs.getDate("published_on")), rs.getString("source_name"), rs.getString("source_url"),
                        "玉米、大豆、水稻", rs.getString("evidence"))).list();
        var weather = jdbc.sql("""
                SELECT mean_temperature_c,precipitation_mm,soil_moisture_percent,risk,
                       assessment,observed_at,source_id
                FROM production.regional_weather_snapshot WHERE root_region_code=:root
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.Weather(
                        rs.getBigDecimal("mean_temperature_c"), rs.getBigDecimal("precipitation_mm"),
                        rs.getBigDecimal("soil_moisture_percent"), rs.getString("risk"),
                        rs.getString("assessment"), instant(rs.getTimestamp("observed_at")),
                        rs.getString("source_id"))).optional().orElse(null);
        var status = jdbc.sql("""
                SELECT max(last_attempt_at) AS last_attempt,max(last_success_at) AS last_success,
                       min(next_refresh_at) AS next_refresh,
                       bool_and(last_status IN ('SUCCESS','BOOTSTRAP_VERIFIED','BOOTSTRAP_REFERENCE')) AS healthy
                FROM production.regional_public_source WHERE active AND root_region_code IN (:root,'*')
                """).param("root", root).query((rs, n) -> new RegionalAgricultureProfile.RefreshStatus(
                        "每日", rs.getBoolean("healthy") ? "SUCCESS" : "PARTIAL",
                        instant(rs.getTimestamp("last_attempt")), instant(rs.getTimestamp("last_success")),
                        instant(rs.getTimestamp("next_refresh")))).single();
        return new Context(observations, status, weather, policies, sources);
    }

    @Override
    public List<DueSource> due(Instant now) {
        return jdbc.sql("""
                SELECT source_id,root_region_code,source_type,source_name,source_url,parser_key
                FROM production.regional_public_source
                WHERE active AND (next_refresh_at IS NULL OR next_refresh_at<=:now)
                ORDER BY source_type,source_id
                """).param("now", Timestamp.from(now)).query((rs, n) -> new DueSource(
                        rs.getString("source_id"), rs.getString("root_region_code"), rs.getString("source_type"),
                        rs.getString("source_name"), rs.getString("source_url"), rs.getString("parser_key"))).list();
    }

    @Override
    public void recordPageSuccess(String id, Instant now, String hash, String excerpt) {
        updateSuccess(id, now, hash, excerpt);
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
        updateSuccess(id, now, hash, excerpt);
    }

    private void updateSuccess(String id, Instant now, String hash, String excerpt) {
        jdbc.sql("""
                UPDATE production.regional_public_source SET last_attempt_at=:now,last_success_at=:now,
                  next_refresh_at=:next,last_status='SUCCESS',last_error=NULL,
                  last_content_hash=:hash,last_excerpt=:excerpt WHERE source_id=:id
                """).param("now", Timestamp.from(now)).param("next", Timestamp.from(now.plus(1, ChronoUnit.DAYS)))
                .param("hash", hash).param("excerpt", excerpt).param("id", id).update();
    }

    @Override
    public void recordFailure(String id, Instant now, String message) {
        jdbc.sql("""
                UPDATE production.regional_public_source SET last_attempt_at=:now,next_refresh_at=:next,
                  last_status='FAILED_USING_LAST_SUCCESS',last_error=:message WHERE source_id=:id
                """).param("now", Timestamp.from(now)).param("next", Timestamp.from(now.plus(1, ChronoUnit.HOURS)))
                .param("message", message == null ? "unknown" : message.substring(0, Math.min(500, message.length())))
                .param("id", id).update();
    }

    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static String date(java.sql.Date value) { return value == null ? null : value.toLocalDate().toString(); }
}
