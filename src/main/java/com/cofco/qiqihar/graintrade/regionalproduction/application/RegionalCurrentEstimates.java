package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Reproducible current-year estimates; the value being compared never trains its own model. */
public final class RegionalCurrentEstimates {
    public static final String VERSION = "current-estimate-v1";
    private RegionalCurrentEstimates() {}
    public record Input(int year, BigDecimal value, String unit, String source, String url) {}
    public record Candidate(String model, BigDecimal meanAbsoluteError, int validationYears) {}
    public record Estimate(BigDecimal value, String model, String selection, String formula,
            List<Input> inputs, List<Candidate> candidates) {}
    public record Comparison(String label, String category, String unit, int publicYear, BigDecimal publicValue,
            String sourceName, String sourceUrl, int estimateYear, Estimate current,
            BigDecimal difference, BigDecimal differencePercent, Estimate historicalCheck,
            BigDecimal historicalDifference, String conclusion) {}

    public static List<Comparison> compare(List<RegionalAgricultureProfile.Indicator> observations, int year) {
        Map<String, TreeMap<Integer, RegionalAgricultureProfile.Indicator>> groups = new TreeMap<>();
        for (var item : observations) {
            if (!"OBSERVED".equals(item.dataKind()) || item.dataYear() > year || item.value() == null) continue;
            groups.computeIfAbsent(item.label() + "|" + item.unit(), key -> new TreeMap<>())
                    .putIfAbsent(item.dataYear(), item);
        }
        List<Comparison> result = new ArrayList<>();
        for (var byYear : groups.values()) {
            var series = List.copyOf(byYear.values());
            var latest = series.getLast();
            Estimate current = estimate(series, year);
            Estimate check = latest.dataYear() == year ? current : estimate(series, latest.dataYear());
            BigDecimal delta = current != null && latest.dataYear() == year
                    ? current.value().subtract(latest.value()) : null;
            BigDecimal deltaPercent = delta == null || latest.value().signum() == 0 ? null
                    : delta.multiply(BigDecimal.valueOf(100)).divide(latest.value().abs(), 2, RoundingMode.HALF_UP);
            BigDecimal historicalDelta = check == null ? null : check.value().subtract(latest.value());
            String conclusion = current == null ? "独立历史依据不足或资料过旧，保留公开值，暂不生成当前估算。"
                    : latest.dataYear() != year ? "公开值与当前估算属于不同年度，不能把两者差额称为估算误差；下方另列公开值所属年度的历史检验。"
                    : delta.signum() == 0 ? "当前估算与同年公开值一致；一致不代表经过独立实测认证。"
                    : "当前估算比同年公开值" + (delta.signum() > 0 ? "高" : "低") + delta.abs().stripTrailingZeros().toPlainString()
                            + latest.unit() + "；这反映历史趋势与新公布结果的差异，不足以判定公开数据错误，也不能直接归因为天气或政策。";
            result.add(new Comparison(latest.label(), latest.category(), latest.unit(), latest.dataYear(), latest.value(),
                    latest.sourceName(), latest.sourceUrl(), year, current, delta, deltaPercent, check, historicalDelta, conclusion));
        }
        return List.copyOf(result);
    }

    private static Estimate estimate(List<RegionalAgricultureProfile.Indicator> all, int target) {
        var series = all.stream().filter(i -> i.dataYear() < target).toList();
        if (series.isEmpty() || target - series.getLast().dataYear() > 2) return null;
        List<String> models = new ArrayList<>(List.of("最近值延续", "线性趋势"));
        if (series.stream().allMatch(i -> i.value().signum() > 0)) models.add("对数趋势");
        List<Candidate> candidates = new ArrayList<>();
        String selected = models.getFirst();
        double smallestError = Double.POSITIVE_INFINITY;
        if (series.size() >= 3) {
            for (String model : models) {
                double error = 0;
                for (int i = 2; i < series.size(); i++) {
                    double predicted = predict(series.subList(0, i), series.get(i).dataYear(), model);
                    error += Math.abs(predicted - series.get(i).value().doubleValue());
                }
                double meanError = error / (series.size() - 2);
                if (!Double.isFinite(meanError)) continue;
                candidates.add(new Candidate(model, decimal(meanError), series.size() - 2));
                if (meanError < smallestError) { smallestError = meanError; selected = model; }
            }
        }
        double prediction = predict(series, target, selected);
        if (!Double.isFinite(prediction)) return null;
        String selection = candidates.isEmpty()
                ? "仅有" + series.size() + "期独立历史值，无法可靠回测选模；采用最近值延续，不自行设定增长率。"
                : "逐年留出回测，以平均绝对误差最小的方法估算；每一轮只用更早年份训练。选中" + selected
                        + "，回测误差" + decimal(smallestError) + series.getLast().unit() + "。参数随输入更新重新拟合，目标年的公开值不参与训练。";
        var inputs = series.stream().map(i -> new Input(i.dataYear(), i.value(), i.unit(), i.sourceName(), i.sourceUrl())).toList();
        return new Estimate(decimal(prediction), selected, selection, formula(series, target, selected, prediction), inputs, List.copyOf(candidates));
    }

    private static double predict(List<RegionalAgricultureProfile.Indicator> series, int target, String model) {
        double result;
        if ("最近值延续".equals(model)) result = series.getLast().value().doubleValue();
        else {
            double[] coefficients = coefficients(series, "对数趋势".equals(model));
            result = coefficients[0] + coefficients[1] * (target - series.getFirst().dataYear());
            if ("对数趋势".equals(model)) result = Math.exp(result);
        }
        var last = series.getLast();
        // Only intrinsically nonnegative quantities are constrained; changes and net flows may be negative.
        if (!last.label().matches(".*(?:净|增减|变化|增长).*")) result = Math.max(0, result);
        if (last.unit().equals("%") && last.label().matches(".*(?:占比|覆盖率|机械化率).*")) result = Math.min(100, result);
        return result;
    }

    private static double[] coefficients(List<RegionalAgricultureProfile.Indicator> series, boolean log) {
        int base = series.getFirst().dataYear();
        double mx = series.stream().mapToInt(i -> i.dataYear() - base).average().orElseThrow();
        double my = series.stream().mapToDouble(i -> log ? Math.log(i.value().doubleValue()) : i.value().doubleValue()).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (var item : series) {
            double x = item.dataYear() - base - mx;
            numerator += x * ((log ? Math.log(item.value().doubleValue()) : item.value().doubleValue()) - my);
            denominator += x * x;
        }
        double slope = denominator == 0 ? 0 : numerator / denominator;
        return new double[]{my - slope * mx, slope};
    }

    private static String formula(List<RegionalAgricultureProfile.Indicator> series, int target, String model, double value) {
        var last = series.getLast();
        if (model.equals("最近值延续")) return target + "年估算=" + last.dataYear() + "年最近公开值=" + last.value() + last.unit();
        var c = coefficients(series, model.equals("对数趋势"));
        String term = decimal(c[0]) + "+(" + decimal(c[1]) + ")×(" + target + "-" + series.getFirst().dataYear() + ")";
        return (model.equals("对数趋势") ? "exp(" + term + ")" : term) + "=" + decimal(value) + last.unit()
                + "；截距和斜率由上列历史数据最小二乘拟合，适用指标遵守非负/占比范围约束。";
    }
    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP); }
}
