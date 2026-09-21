package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RegionalAgricultureProfileCalculator {
    private static final List<String> PRODUCTS = List.of("CORN", "SOYBEAN", "RICE");
    private static final Map<String, String> NAMES = Map.of(
            "CORN", "玉米", "SOYBEAN", "大豆", "RICE", "稻谷");

    public RegionalAgricultureProfile calculate(
            String regionCode,
            String regionName,
            String administrativeLevel,
            int year,
            BigDecimal boundaryAreaSquareMetres,
            List<Observation> observations) {
        return calculate(regionCode, regionName, administrativeLevel, year,
                boundaryAreaSquareMetres, observations, ForecastContext.neutral());
    }

    public RegionalAgricultureProfile calculate(
            String regionCode,
            String regionName,
            String administrativeLevel,
            int year,
            BigDecimal boundaryAreaSquareMetres,
            List<Observation> observations,
            ForecastContext forecastContext) {
        Map<String, Observation> observed = new LinkedHashMap<>();
        observations.forEach(value -> observed.put(value.productCode(), value));

        record Working(String code, String kind, BigDecimal area, BigDecimal yield, String basis) {}
        List<Working> working = PRODUCTS.stream().filter(code -> observed.containsKey(code)
                && observed.get(code).plantedAreaMu() != null && observed.get(code).yieldPerMuKg() != null
                && observed.get(code).plantedAreaMu().signum() > 0).map(code -> {
            Observation value = observed.get(code);
            {
                BigDecimal baseYield = value.yieldPerMuKg();
                int gap = value.dataYear() == null ? 0 : Math.max(0, year - value.dataYear());
                BigDecimal area = compound(value.plantedAreaMu(), forecastContext.rate(code + ":area"), gap);
                BigDecimal yield = compound(baseYield, forecastContext.rate(code + ":yield"), gap);
                String kind = !"REGIONAL_OFFICIAL".equals(value.sourceKind()) || gap > 0 ? "MODEL_ESTIMATE" : "OBSERVED";
                return new Working(code, kind, area, yield,
                        value.explanation() != null ? value.explanation() : gap > 0
                                ? "采用" + value.dataYear() + "年" + sourceBasis(value)
                                        + "为基线，按历史数据选定的趋势补算" + year + "年缺项"
                                : value.yieldPerMuKg() == null
                                ? "面积采用地区正式数据，单产暂用未校准参考参数补齐"
                                : value.sourceCount() > 1
                                ? sourceBasis(value) + "，面积和单产采用可靠度加权结果"
                                : "采用地区年度正式数据自动汇总");
            }
        }).toList();
        BigDecimal totalArea = working.stream().map(Working::area).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<RegionalAgricultureProfile.Crop> crops = new ArrayList<>();
        BigDecimal allocatedPercent = BigDecimal.ZERO;
        for (int index = 0; index < working.size(); index++) {
            Working value = working.get(index);
            BigDecimal percent = index == working.size() - 1
                    ? bd("100.00").subtract(allocatedPercent)
                    : value.area().multiply(bd("100")).divide(totalArea, 2, RoundingMode.HALF_UP);
            allocatedPercent = allocatedPercent.add(percent);
            BigDecimal output = value.area().multiply(value.yield()).setScale(4, RoundingMode.HALF_UP);

            crops.add(new RegionalAgricultureProfile.Crop(
                    value.code(), NAMES.get(value.code()), value.kind(), scale(value.area()),
                    scale(value.yield()), output, percent, value.basis(),
                    "总产=播种面积×亩均单产；结构占比=本品种面积÷三品种面积合计",
                    null, null, null,
                    forecasts(year, value.code(), value.area(), value.yield(), forecastContext)));
        }
        return new RegionalAgricultureProfile(
                regionCode, regionName, administrativeLevel, year, true, Instant.now().toString(),
                switch (administrativeLevel) {
                    case "VILLAGE" -> "行政村级缺项估算：采用最近层级依据，按完整边界权重或同级等份假设分配；具体方法见数值说明";
                    case "TOWNSHIP" -> "乡镇级缺项估算：采用最近层级依据，按完整边界权重或同级等份假设分配；具体方法见数值说明";
                    default -> "地区公开统计与模型补算覆盖";
                },
                null,
                observations.isEmpty()
                        ? "公开行政区边界与系统统计模型自动生成"
                        : "地区年度正式数据优先，缺项由公开行政区边界与统计模型自动补齐",
                "公开历史值和面积密度补算当年缺项；历史趋势或最近值延续预测明年；依据不足的作物不生成数值",
                new RegionalAgricultureProfile.RefreshStatus(
                        "每日 08:30", "WAITING_FOR_SOURCE_SYNC", null, null, null),
                null, List.of(), List.of(), List.of(),
                List.copyOf(crops));
    }

    private static List<RegionalAgricultureProfile.Forecast> forecasts(
            int year, String productCode, BigDecimal area, BigDecimal yield,
            ForecastContext context) {
        BigDecimal areaFactor = BigDecimal.ONE.add(context.rate(productCode + ":area"));
        BigDecimal yieldFactor = BigDecimal.ONE.add(context.rate(productCode + ":yield"));
        // No empirically fitted causal coefficient exists for policy/weather yet.
        BigDecimal policyFactor = BigDecimal.ONE;
        BigDecimal nextArea = area.multiply(areaFactor);
        BigDecimal nextYield = yield.multiply(yieldFactor)
                .multiply(policyFactor);
        return List.of(new RegionalAgricultureProfile.Forecast(
                year + 1, scale(nextArea), scale(nextYield),
                nextArea.multiply(nextYield).setScale(4, RoundingMode.HALF_UP),
                "明年总产=当年面积×" + areaFactor.setScale(6, RoundingMode.HALF_UP)
                        + "（面积趋势）×当年单产×" + yieldFactor.setScale(6, RoundingMode.HALF_UP)
                        + "（单产趋势）×" + BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP)
                        + "（天气修正）×" + policyFactor.setScale(6, RoundingMode.HALF_UP) + "（政策修正）；趋势由历史公开值拟合；缺历史则延续最近值；天气与政策仅作背景，尚无校准因果系数",
                null));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal compound(BigDecimal value, BigDecimal rate, int years) {
        BigDecimal result = value;
        for (int index = 0; index < years; index++) result = result.multiply(BigDecimal.ONE.add(rate));
        return result;
    }

    private static String sourceBasis(Observation value) {
        return value.sourceCount() > 1
                ? value.sourceCount() + "个公开渠道按来源可靠度融合"
                : "公开资料";
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    public record Observation(
            String productCode,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            String sourceKind,
            Integer dataYear,
            int sourceCount, String explanation) {
        public Observation(String productCode, BigDecimal plantedAreaMu, BigDecimal yieldPerMuKg,
                String sourceKind, Integer dataYear, int sourceCount) {
            this(productCode, plantedAreaMu, yieldPerMuKg, sourceKind, dataYear, sourceCount, null);
        }
        public Observation(String productCode, BigDecimal plantedAreaMu,
                BigDecimal yieldPerMuKg, String sourceKind) {
            this(productCode, plantedAreaMu, yieldPerMuKg, sourceKind, null, 1);
        }

        public Observation(String productCode, BigDecimal plantedAreaMu,
                BigDecimal yieldPerMuKg, String sourceKind, Integer dataYear) {
            this(productCode, plantedAreaMu, yieldPerMuKg, sourceKind, dataYear, 1);
        }
    }

    public record ForecastContext(BigDecimal weatherFactor, boolean policyAvailable, Map<String, BigDecimal> rates) {
        public ForecastContext(BigDecimal weatherFactor, boolean policyAvailable) {
            this(weatherFactor, policyAvailable, Map.of());
        }
        public BigDecimal rate(String key) { return rates.getOrDefault(key, BigDecimal.ZERO); }
        public ForecastContext {
            if (weatherFactor == null) weatherFactor = BigDecimal.ONE;
            if (rates == null) rates = Map.of();
        }

        static ForecastContext neutral() {
            return new ForecastContext(BigDecimal.ONE, false);
        }
    }
}
