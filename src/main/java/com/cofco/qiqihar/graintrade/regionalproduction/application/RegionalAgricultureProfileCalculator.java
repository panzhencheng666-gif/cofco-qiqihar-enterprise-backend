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
            "CORN", "玉米", "SOYBEAN", "大豆", "RICE", "水稻");
    private static final Map<String, BigDecimal> DEFAULT_YIELD = Map.of(
            "CORN", bd("610"), "SOYBEAN", bd("155"), "RICE", bd("520"));
    private static final Map<String, BigDecimal> AREA_GROWTH = Map.of(
            "CORN", bd("0.012"), "SOYBEAN", bd("0.018"), "RICE", bd("0.005"));
    private static final Map<String, BigDecimal> YIELD_GROWTH = Map.of(
            "CORN", bd("0.008"), "SOYBEAN", bd("0.010"), "RICE", bd("0.006"));

    public RegionalAgricultureProfile calculate(
            String regionCode,
            String regionName,
            String administrativeLevel,
            int year,
            BigDecimal boundaryAreaSquareMetres,
            List<Observation> observations) {
        Map<String, BigDecimal> shares = shares(regionCode);
        Map<String, Observation> observed = new LinkedHashMap<>();
        observations.forEach(value -> observed.put(value.productCode(), value));
        BigDecimal inferredTotalArea = inferTotalArea(regionCode, boundaryAreaSquareMetres, shares, observed);

        record Working(String code, String kind, BigDecimal area, BigDecimal yield, String basis) {}
        List<Working> working = PRODUCTS.stream().map(code -> {
            Observation value = observed.get(code);
            if (value != null && value.plantedAreaMu() != null) {
                BigDecimal yield = value.yieldPerMuKg() == null ? DEFAULT_YIELD.get(code) : value.yieldPerMuKg();
                String kind = value.yieldPerMuKg() == null ? "MODEL_ESTIMATE" : "OBSERVED";
                return new Working(code, kind, value.plantedAreaMu(), yield,
                        value.yieldPerMuKg() == null
                                ? "面积采用地区正式数据，单产由多年均值模型补齐"
                                : "采用地区年度正式数据自动汇总");
            }
            return new Working(code, "MODEL_ESTIMATE",
                    inferredTotalArea.multiply(shares.get(code)), DEFAULT_YIELD.get(code),
                    "依据公开行政区边界面积、区域耕作系数和三品种结构系数推算");
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
                    forecasts(year, value.code(), value.area(), value.yield())));
        }
        return new RegionalAgricultureProfile(
                regionCode, regionName, administrativeLevel, year, true, Instant.now().toString(),
                observations.isEmpty()
                        ? "公开行政区边界与系统统计模型自动生成"
                        : "地区年度正式数据优先，缺项由公开行政区边界与统计模型自动补齐",
                "结构系数估算当年缺项；面积和单产采用复合增长公式推算未来三年",
                List.copyOf(crops));
    }

    private static List<RegionalAgricultureProfile.Forecast> forecasts(
            int year, String productCode, BigDecimal area, BigDecimal yield) {
        List<RegionalAgricultureProfile.Forecast> result = new ArrayList<>();
        BigDecimal nextArea = area;
        BigDecimal nextYield = yield;
        for (int offset = 1; offset <= 3; offset++) {
            nextArea = nextArea.multiply(BigDecimal.ONE.add(AREA_GROWTH.get(productCode)));
            nextYield = nextYield.multiply(BigDecimal.ONE.add(YIELD_GROWTH.get(productCode)));
            result.add(new RegionalAgricultureProfile.Forecast(
                    year + offset, scale(nextArea), scale(nextYield),
                    nextArea.multiply(nextYield).setScale(4, RoundingMode.HALF_UP)));
        }
        return List.copyOf(result);
    }

    private static BigDecimal inferTotalArea(
            String regionCode,
            BigDecimal boundaryAreaSquareMetres,
            Map<String, BigDecimal> shares,
            Map<String, Observation> observations) {
        List<BigDecimal> estimates = observations.values().stream()
                .filter(value -> value.plantedAreaMu() != null && value.plantedAreaMu().signum() > 0)
                .map(value -> value.plantedAreaMu().divide(shares.get(value.productCode()), 8, RoundingMode.HALF_UP))
                .toList();
        if (!estimates.isEmpty()) {
            return estimates.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(estimates.size()), 8, RoundingMode.HALF_UP);
        }
        BigDecimal cultivatedRatio = switch (root(regionCode)) {
            case "230200" -> bd("0.42");
            case "231100" -> bd("0.28");
            case "150700" -> bd("0.18");
            case "232700" -> bd("0.035");
            default -> bd("0.15");
        };
        return boundaryAreaSquareMetres.divide(bd("666.6666667"), 8, RoundingMode.HALF_UP)
                .multiply(cultivatedRatio);
    }

    private static Map<String, BigDecimal> shares(String regionCode) {
        return switch (root(regionCode)) {
            case "231100" -> map("0.40", "0.55", "0.05");
            case "150700" -> map("0.55", "0.35", "0.10");
            case "232700" -> map("0.36", "0.52", "0.12");
            default -> map("0.62", "0.30", "0.08");
        };
    }

    private static String root(String regionCode) {
        if (regionCode == null || regionCode.length() < 4) return "";
        if (regionCode.startsWith("2302")) return "230200";
        if (regionCode.startsWith("2311")) return "231100";
        if (regionCode.startsWith("1507")) return "150700";
        if (regionCode.startsWith("2327")) return "232700";
        return regionCode.length() >= 6 ? regionCode.substring(0, 6) : regionCode;
    }

    private static Map<String, BigDecimal> map(String corn, String soybean, String rice) {
        return Map.of("CORN", bd(corn), "SOYBEAN", bd(soybean), "RICE", bd(rice));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    public record Observation(
            String productCode,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            String sourceKind) {}
}
