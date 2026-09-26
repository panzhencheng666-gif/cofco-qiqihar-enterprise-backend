package com.cofco.qiqihar.graintrade.marketintelligence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorldBankMonthlyAnalysis {
    private final WorldBankMonthlyRepository repository;

    public WorldBankMonthlyAnalysis(WorldBankMonthlyRepository repository) { this.repository = repository; }

    public record Point(LocalDate period, BigDecimal value) { }
    public record Snapshot(String series, String title, String unit, String cadence,
            List<Point> points, BigDecimal latest, LocalDate latestPeriod,
            BigDecimal previous, BigDecimal monthChangePct, BigDecimal yearChangePct,
            BigDecimal trailingThreeMonthAverage, String trend, String sourceUrl,
            LocalDate sourceUpdatedOn,
            Instant lastAttemptAt, Instant lastSuccessAt, String syncError, boolean stale) { }

    public Snapshot snapshot(String code, int months) {
        var series = WorldBankMonthlySeries.fromCode(code);
        var rows = repository.points(series, Math.max(13, Math.min(months, 120)));
        var state = repository.state();
        var latest = rows.isEmpty() ? null : rows.getLast();
        var previous = rows.size() < 2 ? null : rows.get(rows.size() - 2);
        var yearAgo = latest == null ? null : rows.stream()
                .filter(row -> row.period().equals(latest.period().minusYears(1))).findFirst().orElse(null);
        var monthPct = latest == null || previous == null
                || !previous.period().equals(latest.period().minusMonths(1))
                ? null : change(latest.price(), previous.price());
        var yearPct = latest == null || yearAgo == null ? null : change(latest.price(), yearAgo.price());
        BigDecimal average = null;
        if (rows.size() >= 3 && rows.get(rows.size() - 2).period().equals(latest.period().minusMonths(1))
                && rows.get(rows.size() - 3).period().equals(latest.period().minusMonths(2)))
            average = rows.subList(rows.size() - 3, rows.size()).stream()
                .map(WorldBankMonthlyRepository.Point::price).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        var visible = rows.stream().skip(Math.max(0, rows.size() - Math.max(1, Math.min(months, 120))))
                .map(row -> new Point(row.period(), row.price())).toList();
        var stale = latest == null || latest.period().isBefore(LocalDate.now().withDayOfMonth(1).minusMonths(2));
        return new Snapshot(series.code, series.title, series.unit, "月度公开基准价",
                visible, latest == null ? null : latest.price(), latest == null ? null : latest.period(),
                previous == null ? null : previous.price(), monthPct, yearPct, average,
                monthPct == null ? "INSUFFICIENT_DATA" : monthPct.signum() > 0 ? "RISING"
                        : monthPct.signum() < 0 ? "FALLING" : "FLAT",
                latest == null ? WorldBankMonthlyRefresh.SOURCE_URL : latest.sourceUrl(),
                latest == null ? null : latest.sourceUpdatedOn(),
                state.lastAttemptAt(), state.lastSuccessAt(),
                state.lastError(), stale);
    }

    static BigDecimal change(BigDecimal current, BigDecimal previous) {
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
    }
}
