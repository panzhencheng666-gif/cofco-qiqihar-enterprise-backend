package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Positive-series models selected using rolling one-step error; no crop-specific growth constants. */
public final class RegionalHistoricalProjection {
    private RegionalHistoricalProjection() {}
    public record Fit(double annualRate, String model, String reason) {}

    public static Fit fit(List<RegionalAgricultureProfile.Indicator> input) {
        var series = input.stream().filter(i -> i.value().signum() > 0)
                .sorted(Comparator.comparingInt(RegionalAgricultureProfile.Indicator::dataYear)).toList();
        if (series.size() < 2) return new Fit(0, "最近值延续", "只有一期有效数据，不凭空设置增长率，预测沿用最近公开值");
        if (series.size() == 2) return new Fit(rate(series), "两期年均趋势", "两期同口径数据按年度间隔计算复合变化率；历史不足，尚不能回测选模");
        double flatError = 0, trendError = 0;
        for (int i = 2; i < series.size(); i++) {
            var past = series.subList(0, i);
            var previous = past.getLast();
            var actual = series.get(i);
            double flat = previous.value().doubleValue();
            double trend = flat * Math.exp(rate(past) * (actual.dataYear() - previous.dataYear()));
            flatError += Math.abs(flat - actual.value().doubleValue()) / actual.value().doubleValue();
            trendError += Math.abs(trend - actual.value().doubleValue()) / actual.value().doubleValue();
        }
        boolean useTrend = trendError < flatError;
        return new Fit(useTrend ? rate(series) : 0, useTrend ? "对数趋势回归" : "最近值延续",
                "按历史年份逐期留出验证：最近值模型平均相对误差" + percent(flatError / (series.size()-2))
                + "，趋势模型" + percent(trendError / (series.size()-2)) + "；选择误差较低者，回测未使用未来数据");
    }

    private static double rate(List<RegionalAgricultureProfile.Indicator> series) {
        double mx = series.stream().mapToInt(RegionalAgricultureProfile.Indicator::dataYear).average().orElseThrow();
        double my = series.stream().mapToDouble(i -> Math.log(i.value().doubleValue())).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (var item : series) {
            double dx = item.dataYear() - mx;
            numerator += dx * (Math.log(item.value().doubleValue()) - my);
            denominator += dx * dx;
        }
        return denominator == 0 ? 0 : numerator / denominator;
    }

    public static List<RegionalAgricultureProfile.Indicator> project(List<RegionalAgricultureProfile.Indicator> history, int year) {
        Map<String, List<RegionalAgricultureProfile.Indicator>> groups = history.stream()
                .filter(i -> i.dataYear() <= year && i.dataKind().equals("OBSERVED") && i.value().signum() > 0)
                .filter(i -> i.category().startsWith("CROP_") || i.category().equals("LIVESTOCK") || i.category().equals("FISHERY"))
                .filter(i -> i.label().endsWith("产量") || i.label().endsWith("播种面积"))
                .collect(Collectors.groupingBy(i -> i.label() + "|" + i.unit(), TreeMap::new, Collectors.toList()));
        List<RegionalAgricultureProfile.Indicator> result = new ArrayList<>();
        groups.values().forEach(values -> {
            var series = values.stream().sorted(Comparator.comparingInt(RegionalAgricultureProfile.Indicator::dataYear)).toList();
            var latest = series.getLast();
            // Do not extrapolate a distant obsolete series as if it described current production.
            if (year - latest.dataYear() > 2) return;
            Fit fit = fit(series);
            String inputs = series.stream().map(i -> i.dataYear() + "年=" + i.value().stripTrailingZeros().toPlainString() + i.unit())
                    .collect(Collectors.joining("；"));
            for (int target : new int[]{year, year + 1}) {
                if (latest.dataYear() >= target) continue;
                double factor = Math.exp(fit.annualRate * (target - latest.dataYear()));
                if (!Double.isFinite(factor)) continue;
                BigDecimal value = latest.value().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
                String method = "方法：" + fit.model + "。选择原因：" + fit.reason + "。历史输入：" + inputs
                        + "。计算：" + latest.value() + "×exp(" + String.format(java.util.Locale.ROOT,"%.6f",fit.annualRate)
                        + "×" + (target-latest.dataYear()) + ")=" + value + latest.unit()
                        + "。所有输入为本地区同指标同单位历史公开值；新闻、政策和天气在未校准影响系数前不直接乘入产量。"
                        + "每次来源更新后重新拟合；结果为趋势情景，未提供统计置信概率。";
                result.add(new RegionalAgricultureProfile.Indicator("OUTLOOK", latest.label() + " · " + target + (target == year ? "年补算" : "年预测"),
                        value,latest.unit(),target,"ESTIMATED",method,latest.sourceName(),latest.sourceUrl(),latest.verifiedAt()));
            }
        });
        return List.copyOf(result);
    }
    private static String percent(double value) { return String.format(java.util.Locale.ROOT,"%.2f%%", value*100); }
}
