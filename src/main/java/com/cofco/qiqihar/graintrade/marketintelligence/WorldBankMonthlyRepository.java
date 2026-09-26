package com.cofco.qiqihar.graintrade.marketintelligence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WorldBankMonthlyRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public WorldBankMonthlyRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record Point(LocalDate period, BigDecimal price, LocalDate sourceUpdatedOn, String sourceUrl) { }
    public record State(Instant lastAttemptAt, Instant lastSuccessAt, LocalDate latestPeriod, String lastError) { }

    @Transactional
    public void save(WorldBankMonthlyWorkbook.Parsed parsed, String sourceUrl, String sha256, Instant fetchedAt) {
        var latest = parsed.observations().stream().map(WorldBankMonthlyWorkbook.Observation::period)
                .max(LocalDate::compareTo).orElseThrow();
        var earliest = latest.minusMonths(71);
        var observations = parsed.observations().stream()
                .filter(value -> !value.period().isBefore(earliest)).toList();
        template.batchUpdate("""
                INSERT INTO market_intelligence.monthly_benchmark_price
                    (series_code,period,price,unit,source_url,source_updated_on,fetched_at,source_sha256)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (series_code,period) DO UPDATE SET
                    price=EXCLUDED.price, source_url=EXCLUDED.source_url,
                    source_updated_on=EXCLUDED.source_updated_on,
                    fetched_at=EXCLUDED.fetched_at, source_sha256=EXCLUDED.source_sha256
                WHERE market_intelligence.monthly_benchmark_price.price IS DISTINCT FROM EXCLUDED.price
                   OR market_intelligence.monthly_benchmark_price.source_sha256 IS DISTINCT FROM EXCLUDED.source_sha256
                """, observations, 250, (statement, value) -> {
            statement.setString(1, value.series().code);
            statement.setObject(2, value.period());
            statement.setBigDecimal(3, value.price());
            statement.setString(4, value.series().unit);
            statement.setString(5, sourceUrl);
            statement.setObject(6, parsed.sourceUpdatedOn());
            statement.setObject(7, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
            statement.setString(8, sha256);
        });
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state
                    (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                VALUES ('world-bank-pink-sheet',:at,:at,:period,NULL)
                ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,
                    last_success_at=:at,latest_period=:period,last_error=NULL
                """).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC)).param("period", latest).update();
    }

    public void failure(Instant attemptedAt, String error) {
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                VALUES ('world-bank-pink-sheet',:at,:error)
                ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                """).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                .param("error", error.substring(0, Math.min(400, error.length()))).update();
    }

    public List<Point> points(WorldBankMonthlySeries series, int months) {
        return jdbc.sql("""
                SELECT period,price,source_updated_on,source_url
                FROM market_intelligence.monthly_benchmark_price
                WHERE series_code=:series ORDER BY period DESC LIMIT :months
                """).param("series", series.code).param("months", months)
                .query((rs, row) -> new Point(rs.getObject("period", LocalDate.class), rs.getBigDecimal("price"),
                        rs.getObject("source_updated_on", LocalDate.class), rs.getString("source_url")))
                .list().reversed();
    }

    public State state() {
        return jdbc.sql("""
                SELECT last_attempt_at,last_success_at,latest_period,last_error
                FROM market_intelligence.source_sync_state WHERE source_code='world-bank-pink-sheet'
                """).query((rs, row) -> new State(
                    rs.getObject("last_attempt_at", java.time.OffsetDateTime.class) == null ? null
                            : rs.getObject("last_attempt_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject("last_success_at", java.time.OffsetDateTime.class) == null ? null
                            : rs.getObject("last_success_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject("latest_period", LocalDate.class), rs.getString("last_error")))
                .optional().orElse(new State(null, null, null, null));
    }
}
