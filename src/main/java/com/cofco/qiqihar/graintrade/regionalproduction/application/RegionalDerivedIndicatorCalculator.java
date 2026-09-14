package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds reproducible ratios and intensity indicators from the latest public base values. */
final class RegionalDerivedIndicatorCalculator {
    private RegionalDerivedIndicatorCalculator() {}

    static List<RegionalAgricultureProfile.Indicator> complete(
            List<RegionalAgricultureProfile.Indicator> baseIndicators) {
        List<RegionalAgricultureProfile.Indicator> completed = new ArrayList<>(baseIndicators);
        Map<String, RegionalAgricultureProfile.Indicator> byLabel = new LinkedHashMap<>();
        baseIndicators.forEach(value -> byLabel.put(value.label(), value));

        ratio(completed, byLabel, "加工能力与粮食产量比", "PROCESSING", "%",
                "粮食设计加工能力", "粮食总产量", bd("100"));
        ratio(completed, byLabel, "加工企业密度", "PROCESSING", "家/百万吨",
                "粮食加工企业", "粮食总产量", bd("100"));

        intensity(completed, byLabel, "亩均计划涉农贷款", "FINANCE", "元/亩",
                "计划涉农贷款", bd("100000000"), "计划播种面积", bd("10000"));
        intensity(completed, byLabel, "亩均春耕资金需求", "FINANCE", "元/亩",
                "春耕资金需求", bd("100000000"), "计划播种面积", bd("10000"));
        intensity(completed, byLabel, "亩均种子需求", "INPUT", "公斤/亩",
                "种子需求量", bd("10000000"), "计划播种面积", bd("10000"));
        intensity(completed, byLabel, "亩均化肥需求", "INPUT", "公斤/亩",
                "化肥需求量", bd("10000000"), "计划播种面积", bd("10000"));
        intensity(completed, byLabel, "亩均柴油需求", "INPUT", "公斤/亩",
                "柴油需求量", bd("10000000"), "计划播种面积", bd("10000"));
        intensity(completed, byLabel, "农机检修密度", "TECHNOLOGY", "台/千亩",
                "完成检修农机", bd("10000"), "计划播种面积", bd("10000").divide(bd("1000")));

        ratio(completed, byLabel, "粮食种植占农作物比重", "LAND", "%",
                "粮食作物播种面积", "农作物播种面积", bd("100"));
        ratio(completed, byLabel, "高标准农田覆盖率", "INFRASTRUCTURE", "%",
                "累计高标准农田", "粮食作物播种面积", bd("100"));
        ratio(completed, byLabel, "保护性耕作覆盖率", "TECHNOLOGY", "%",
                "保护性耕作面积", "粮食作物播种面积", bd("100"));
        ratio(completed, byLabel, "单产提升示范覆盖率", "TECHNOLOGY", "%",
                "单产提升示范区", "粮食作物播种面积", bd("100"));

        ratio(completed, byLabel, "粮食播种占比", "LAND", "%",
                "粮食计划播种面积", "农作物播种面积", bd("100"));
        ratio(completed, byLabel, "补贴资金发放率", "FINANCE", "%",
                "已发放补贴补助", "粮食生产补贴补助", bd("100"));
        intensity(completed, byLabel, "亩均粮食生产补贴", "FINANCE", "元/亩",
                "粮食生产补贴补助", bd("100000000"), "粮食计划播种面积", bd("10000"));
        ratio(completed, byLabel, "绿色认证面积占粮食面积比重", "BRAND", "%",
                "绿色食品认证面积", "粮食计划播种面积", bd("100"));

        completed.sort(Comparator.comparing(RegionalAgricultureProfile.Indicator::category)
                .thenComparing(RegionalAgricultureProfile.Indicator::label));
        return List.copyOf(completed);
    }

    private static void ratio(List<RegionalAgricultureProfile.Indicator> output,
            Map<String, RegionalAgricultureProfile.Indicator> byLabel,
            String label, String category, String unit, String numeratorLabel,
            String denominatorLabel, BigDecimal scale) {
        var numerator = byLabel.get(numeratorLabel);
        var denominator = byLabel.get(denominatorLabel);
        if (numerator == null || denominator == null || denominator.value().signum() == 0
                || numerator.dataYear() != denominator.dataYear()
                || numerator.dataKind().equals("CONTEXT") || denominator.dataKind().equals("CONTEXT")) return;
        BigDecimal value = numerator.value().divide(denominator.value(), 8, RoundingMode.HALF_UP)
                .multiply(scale).setScale(2, RoundingMode.HALF_UP);
        output.add(derived(category, label, value, unit, numerator, denominator,
                numeratorLabel + "(" + plain(numerator.value()) + numerator.unit() + ")÷"
                        + denominatorLabel + "(" + plain(denominator.value()) + denominator.unit() + ")×"
                        + scale.stripTrailingZeros().toPlainString() + "=" + plain(value) + unit));
    }

    private static void intensity(List<RegionalAgricultureProfile.Indicator> output,
            Map<String, RegionalAgricultureProfile.Indicator> byLabel,
            String label, String category, String unit, String numeratorLabel,
            BigDecimal numeratorMultiplier, String denominatorLabel, BigDecimal denominatorMultiplier) {
        var numerator = byLabel.get(numeratorLabel);
        var denominator = byLabel.get(denominatorLabel);
        if (numerator == null || denominator == null || denominator.value().signum() == 0
                || numerator.dataYear() != denominator.dataYear()
                || numerator.dataKind().equals("CONTEXT") || denominator.dataKind().equals("CONTEXT")) return;
        BigDecimal value = numerator.value().multiply(numeratorMultiplier)
                .divide(denominator.value().multiply(denominatorMultiplier), 2, RoundingMode.HALF_UP);
        output.add(derived(category, label, value, unit, numerator, denominator,
                numeratorLabel + "(" + plain(numerator.value()) + numerator.unit() + ")×"
                        + numeratorMultiplier.stripTrailingZeros().toPlainString()
                        + "÷(" + denominatorLabel + "(" + plain(denominator.value())
                        + denominator.unit() + ")×"
                        + denominatorMultiplier.stripTrailingZeros().toPlainString() + ")="
                        + plain(value) + unit));
    }

    private static RegionalAgricultureProfile.Indicator derived(String category, String label,
            BigDecimal value, String unit, RegionalAgricultureProfile.Indicator numerator,
            RegionalAgricultureProfile.Indicator denominator, String formula) {
        String verifiedAt = latest(numerator.verifiedAt(), denominator.verifiedAt());
        return new RegionalAgricultureProfile.Indicator(category, label, value, unit,
                Math.max(numerator.dataYear(), denominator.dataYear()), "ESTIMATED",
                formula + "；按当前公开基础值动态计算",
                numerator.sourceName(), numerator.sourceUrl(), verifiedAt);
    }

    private static String latest(String left, String right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
