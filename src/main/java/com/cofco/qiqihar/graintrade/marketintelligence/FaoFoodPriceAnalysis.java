package com.cofco.qiqihar.graintrade.marketintelligence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FaoFoodPriceAnalysis {
    private final FaoFoodPriceFeed feed;
    public FaoFoodPriceAnalysis(FaoFoodPriceFeed feed) { this.feed = feed; }

    public record Point(LocalDate period, BigDecimal value) { }
    public record Snapshot(String series, String title, String unit, String cadence,
            List<Point> points, BigDecimal latest, LocalDate latestPeriod,
            BigDecimal previous, BigDecimal monthChangePct, BigDecimal yearChangePct,
            BigDecimal trailingThreeMonthAverage, String trend, String sourceUrl,
            LocalDate sourceUpdatedOn, Instant lastAttemptAt, Instant lastSuccessAt,
            String syncError, boolean stale) { }

    public Snapshot snapshot(String code, int months) {
        var series = FaoFoodPriceSeries.fromCode(code);
        int requested = Math.max(1, Math.min(months, 120));
        var rows = feed.points(series, Math.max(13, requested));
        var state = feed.state();
        var latest = rows.isEmpty() ? null : rows.getLast();
        var previous = rows.size() < 2 ? null : rows.get(rows.size() - 2);
        var yearAgo = latest == null ? null : rows.stream()
                .filter(row -> row.period().equals(latest.period().minusYears(1))).findFirst().orElse(null);
        var monthPct = latest == null || previous == null
                || !previous.period().equals(latest.period().minusMonths(1))
                ? null : WorldBankMonthlyAnalysis.change(latest.value(), previous.value());
        var yearPct = latest == null || yearAgo == null ? null
                : WorldBankMonthlyAnalysis.change(latest.value(), yearAgo.value());
        BigDecimal average = null;
        if (latest != null && rows.size() >= 3
                && rows.get(rows.size() - 2).period().equals(latest.period().minusMonths(1))
                && rows.get(rows.size() - 3).period().equals(latest.period().minusMonths(2)))
            average = rows.subList(rows.size() - 3, rows.size()).stream()
                    .map(FaoFoodPriceFeed.Point::value).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        var visible = rows.stream().skip(Math.max(0, rows.size() - requested))
                .map(row -> new Point(row.period(), row.value())).toList();
        var stale = latest == null || latest.period().isBefore(LocalDate.now().withDayOfMonth(1).minusMonths(2));
        return new Snapshot(series.code, series.title, "指数点", "月度公开指数",
                visible, latest == null ? null : latest.value(), latest == null ? null : latest.period(),
                previous == null ? null : previous.value(), monthPct, yearPct, average,
                monthPct == null ? "INSUFFICIENT_DATA" : monthPct.signum() > 0 ? "RISING"
                        : monthPct.signum() < 0 ? "FALLING" : "FLAT",
                latest == null ? FaoFoodPriceFeed.SOURCE_URL : latest.sourceUrl(), null,
                state.lastAttemptAt(), state.lastSuccessAt(), state.lastError(), stale);
    }
}
