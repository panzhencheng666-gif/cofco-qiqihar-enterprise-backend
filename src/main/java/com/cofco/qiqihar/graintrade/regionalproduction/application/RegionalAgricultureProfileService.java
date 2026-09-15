package com.cofco.qiqihar.graintrade.regionalproduction.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegionalAgricultureProfileService {
    private static final List<String> PRODUCTS = List.of("CORN", "SOYBEAN", "RICE");
    private static final Set<String> SUPPORTED_ROOTS = Set.of("230200", "231100", "150700", "232700");
    private final RegionalCropAnnualStatRepository annualStats;
    private final RegionalCropSummaryRepository summaries;
    private final RegionalAgricultureBoundaryRepository boundaries;
    private final RegionalAgricultureProfileCalculator calculator;
    private final RegionalPublicDataRepository publicData;
    private final AccessControl access;

    public RegionalAgricultureProfileService(
            RegionalCropAnnualStatRepository annualStats,
            RegionalCropSummaryRepository summaries,
            RegionalAgricultureBoundaryRepository boundaries,
            RegionalAgricultureProfileCalculator calculator,
            RegionalPublicDataRepository publicData,
            AccessControl access) {
        this.annualStats = annualStats;
        this.summaries = summaries;
        this.boundaries = boundaries;
        this.calculator = calculator;
        this.publicData = publicData;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public RegionalAgricultureProfile profile(int year, String regionCode) {
        return profile(year, regionCode, new java.util.HashSet<>(), access.requireBusinessReadScope(), new java.util.HashMap<>());
    }

    RegionalAgricultureProfile profileForRefresh(int year, String regionCode, java.util.Map<String, RegionalAgricultureProfile> completed) {
        return profile(year,regionCode,new java.util.HashSet<>(),
                new com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope("regional-daily-calculation",Set.of("*")),completed);
    }

    private RegionalAgricultureProfile profile(int year, String regionCode, Set<String> lineage,
            com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope scope,
            java.util.Map<String,RegionalAgricultureProfile> completed) {
        if (completed.containsKey(regionCode)) return completed.get(regionCode);
        if (!lineage.add(regionCode) || lineage.size() > 5)
            throw invalid("REGIONAL_PROFILE_HIERARCHY_INVALID", "地区上下级关系需要核实，暂不能逐级估算");
        if (year < 2001 || year > 2100) {
            throw invalid("REGIONAL_PROFILE_YEAR_INVALID", "地区农业档案年度必须在2001至2100之间");
        }
        var region = annualStats.region(regionCode)
                .orElseThrow(() -> invalid("REGIONAL_PROFILE_REGION_INVALID", "地区代码不存在"));
        if (!SUPPORTED_ROOTS.contains(root(region.code()))) {
            throw invalid("REGIONAL_PROFILE_REGION_UNSUPPORTED", "地区不在齐齐哈尔、黑河、呼伦贝尔或大兴安岭范围内");
        }
        var publicContext = publicData.load(root(region.code()), year);
        List<RegionalAgricultureProfileCalculator.Observation> observations = new ArrayList<>();
        if (Set.of("PREFECTURE", "COUNTY").contains(region.administrativeLevel())) {
            for (String product : PRODUCTS) {
                summaries.summarize(year, product, region.code(), scope.regionCodes())
                        .filter(RegionalCropSummary::currentDataAvailable)
                        .ifPresent(value -> observations.add(
                                new RegionalAgricultureProfileCalculator.Observation(
                                        product, value.plantedAreaMu(), value.yieldPerMuKg(),
                                        "REGIONAL_OFFICIAL")));
            }
        }
        if ("PREFECTURE".equals(region.administrativeLevel()) && observations.isEmpty()) {
            observations.addAll(publicContext.observations());
        }
        BigDecimal boundaryArea = boundaries.areaSquareMetres(region.code())
                .orElseThrow(() -> invalid("REGIONAL_PROFILE_BOUNDARY_MISSING", "所选地区缺少可用于模型推算的公开边界"));
        var history = publicData.history(root(region.code()), year);
        var trendRates = new java.util.HashMap<String, BigDecimal>();
        var trendEvidence = new java.util.HashMap<String, String>();
        java.util.Map.of("CORN", "玉米", "SOYBEAN", "大豆", "RICE", "稻谷").forEach((code, name) -> {
            for (String measure : List.of("area", "yield")) {
                String label = name + (measure.equals("area") ? "播种面积" : "平均单产");
                var series = history.stream().filter(i -> i.label().equals(label)).toList();
                var fit = RegionalHistoricalProjection.fit(series);
                trendEvidence.merge(code, label + "预测依据：" + (series.isEmpty() ? "缺少公开历史序列，沿用基线"
                        : series.stream().map(i -> i.dataYear()+"年"+i.value()+i.unit()+"（"+i.sourceName()+"）")
                            .collect(java.util.stream.Collectors.joining("、"))) + "；" + fit.reason() + "。", String::concat);
                trendRates.put(code + ":" + measure, BigDecimal.valueOf(Math.expm1(fit.annualRate())));
            }
        });
        // Complete inputs from actual regional history; never infer cultivation from a fixed regional ratio.
        var resolved = new ArrayList<RegionalAgricultureProfileCalculator.Observation>();
        var names = java.util.Map.of("CORN", "玉米", "SOYBEAN", "大豆", "RICE", "稻谷");
        RegionalAgricultureProfile parentProfile = null;
        boolean parentLoaded = false;
        for (String product : PRODUCTS) {
            var current = observations.stream().filter(o -> o.productCode().equals(product)).findFirst().orElse(null);
            BigDecimal area = current == null ? null : current.plantedAreaMu();
            BigDecimal yield = current == null ? null : current.yieldPerMuKg();
            String explanation = current == null ? "" : "面积基线：" + (current.dataYear() == null ? year : current.dataYear())
                    + "年地区数据 " + area + "亩。";
            boolean modeled = current == null || yield == null || (current.dataYear() != null && current.dataYear() < year);
            if (area != null && yield == null && Set.of("PREFECTURE", "COUNTY").contains(region.administrativeLevel())) {
                for (int previous = year - 1; previous >= year - 3 && yield == null; previous--) {
                    var past = summaries.summarize(previous, product, region.code(), scope.regionCodes()).orElse(null);
                    if (past != null && past.yieldPerMuKg() != null && past.yieldPerMuKg().signum() > 0) {
                        yield = past.yieldPerMuKg();
                        explanation += "单产基线：" + previous + "年同地区同作物年度汇总 " + yield
                                + "公斤/亩；本年尚无单产，采用最近值延续，未额外假设固定增产。";
                    }
                }
            }
            final String name = names.get(product);
            var historicalArea = history.stream().filter(i -> i.label().equals(name + "播种面积"))
                    .max(java.util.Comparator.comparingInt(RegionalAgricultureProfile.Indicator::dataYear)).orElse(null);
            var historicalYield = history.stream().filter(i -> i.label().equals(name + "平均单产"))
                    .max(java.util.Comparator.comparingInt(RegionalAgricultureProfile.Indicator::dataYear)).orElse(null);
            if (area == null && historicalArea != null && "PREFECTURE".equals(region.administrativeLevel())) {
                var rootArea = boundaries.areaSquareMetres(root(region.code())).orElse(null);
                if (rootArea != null && rootArea.signum() > 0) {
                    var ratio = boundaryArea.divide(rootArea, 12, java.math.RoundingMode.HALF_UP);
                    area = historicalArea.value().multiply(new BigDecimal("10000")).multiply(ratio);
                    explanation += historicalArea.sourceName() + "公布" + historicalArea.dataYear() + "年上级" + name
                            + "播种面积" + historicalArea.value() + "万亩；本地区边界面积" + boundaryArea
                            + "平方米÷上级边界面积" + rootArea + "平方米=" + ratio + "，按此比例分摊为" + area
                            + "亩。采用均匀种植密度假设，不代表实测耕地或村级统计。";
                }
            }
            if (yield == null && historicalYield != null && "PREFECTURE".equals(region.administrativeLevel())) {
                yield = historicalYield.value();
                explanation += "单产采用上级" + historicalYield.dataYear() + "年同作物公开面积和产量计算值"
                        + yield + "公斤/亩；依据：" + historicalYield.method() + "。";
            }
            if (yield == null && "PREFECTURE".equals(region.administrativeLevel())
                    && (scope.isUnrestricted() || scope.regionCodes().contains(region.code()))) {
                var outputs = history.stream().filter(i -> i.label().equals(name + "产量")
                        && "万吨".equals(i.unit()) && i.value().signum() > 0)
                        .sorted(java.util.Comparator.comparingInt(RegionalAgricultureProfile.Indicator::dataYear).reversed()).toList();
                for (var output : outputs) {
                    var past = summaries.summarize(output.dataYear(), product, region.code(), scope.regionCodes()).orElse(null);
                    if (past == null || past.plantedAreaMu() == null || past.plantedAreaMu().signum() <= 0) continue;
                    yield = output.value().multiply(new BigDecimal("10000000"))
                            .divide(past.plantedAreaMu(), 8, java.math.RoundingMode.HALF_UP);
                    modeled = true;
                    explanation += "同年口径推导：" + output.dataYear() + "年" + output.sourceName()
                            + "公开" + name + "总产" + output.value().stripTrailingZeros().toPlainString()
                            + "万吨×10000000÷同年地区年度面积" + past.plantedAreaMu().stripTrailingZeros().toPlainString()
                            + "亩=" + yield + "公斤/亩（" + output.sourceUrl() + "）。"
                            + "这是两类资料结合的估算单产，假设统计地域与播种面积口径一致，口径差异会影响结果；"
                            + year + "年使用当前面积与该单产基线估算，并非公开发布的当年总产。";
                    break;
                }
            }
            if (area != null && yield != null) {
                if (explanation.isBlank()) explanation = "采用地区年度正式面积与单产";
                resolved.add(new RegionalAgricultureProfileCalculator.Observation(product, area, yield,
                        modeled ? "PUBLIC_MODEL" : "REGIONAL_OFFICIAL", year, 1, explanation + trendEvidence.getOrDefault(product,"")));
            }
            else if (region.parentCode() != null && SUPPORTED_ROOTS.contains(root(region.parentCode()))
                    && !"PREFECTURE".equals(region.administrativeLevel())) {
                if (!parentLoaded) {
                    parentProfile = profile(year, region.parentCode(), lineage, scope, completed);
                    parentLoaded = true;
                }
                var parentCrop = parentProfile.crops().stream().filter(c -> c.productCode().equals(product)).findFirst().orElse(null);
                var allocation = boundaries.allocation(region.code(),region.parentCode()).orElse(null);
                if (parentCrop != null && allocation != null && allocation.share().signum() > 0) {
                    String chain = "选择依据：本地区" + year + "年" + name + "资料不完整，优先采用直接上级"
                            + parentProfile.regionName() + "，使农业环境和统计范围尽可能接近。\n";
                    if (area == null) {
                        area = parentCrop.plantedAreaMu().multiply(allocation.share());
                        chain += "面积分配：" + allocation.basis() + "=" + allocation.share().stripTrailingZeros().toPlainString()
                                + "；上级" + parentCrop.plantedAreaMu() + "亩×该权重=" + area + "亩。\n";
                    } else chain += "面积保留：本地区已有" + area + "亩面积依据，只补齐缺失的单产。\n";
                    if (yield == null) yield = parentCrop.yieldPerMuKg();
                    chain += "单产选择：采用最近可用的同作物单产" + yield + "公斤/亩；缺少当地独立测量时，假设当地单产接近上级。\n"
                            + "产量换算：" + area + "亩×" + yield + "公斤/亩=" + area.multiply(yield) + "公斤。\n"
                            + "适用限制：这是密度分配估算，未反映各地真实耕地、灌溉及种植偏好差异；上级或当地新资料到达后自动重算。\n"
                            + "上级依据（" + parentProfile.regionName() + "）：\n" + parentCrop.basis();
                    resolved.add(new RegionalAgricultureProfileCalculator.Observation(product,area,yield,"PUBLIC_MODEL",year,1,explanation+chain));
                }
            }
        }
        var calculated = calculator.calculate(region.code(), region.name(), region.administrativeLevel(),
                year, boundaryArea, resolved, new RegionalAgricultureProfileCalculator.ForecastContext(
                        BigDecimal.ONE, !publicContext.policies().isEmpty(), trendRates));
        var facts = boundaries.facts(region.code());
        var indicators = new ArrayList<>(RegionalDerivedIndicatorCalculator.complete(publicContext.indicators()));
        indicators.addAll(RegionalHistoricalProjection.project(history, year));
        if (!"PREFECTURE".equals(region.administrativeLevel())) {
            BigDecimal share = BigDecimal.ONE;
            String cursor = region.code();
            var weights = new ArrayList<String>();
            for (int depth=0; depth<4 && !cursor.equals(root(region.code())); depth++) {
                var child = annualStats.region(cursor).orElse(null);
                if (child == null || child.parentCode() == null) { share = null; break; }
                var allocation = boundaries.allocation(cursor,child.parentCode()).orElse(null);
                if (allocation == null) { share = null; break; }
                share = share.multiply(allocation.share());
                weights.add(child.name()+"："+allocation.basis()+"="+allocation.share().stripTrailingZeros().toPlainString());
                cursor = child.parentCode();
            }
            indicators = new ArrayList<>(RegionalIndicatorAllocation.allocate(indicators,share,String.join("；",weights)));
        }
        var result = new RegionalAgricultureProfile(
                calculated.regionCode(), calculated.regionName(), calculated.administrativeLevel(),
                calculated.year(), calculated.automatic(), calculated.generatedAt(),
                calculated.coverageDescription(),
                new RegionalAgricultureProfile.RegionFacts(
                        facts.areaSquareMetres().divide(new BigDecimal("1000000"), 2, java.math.RoundingMode.HALF_UP),
                        facts.directChildCount(), facts.countyCount(), facts.townshipCount(), facts.villageCount()),
                publicContext.sources().isEmpty()
                        ? calculated.sourceSummary()
                        : "公开统计、行政区边界、逐日天气和政策证据自动融合；缺项由模型补齐",
                calculated.calculationMethod(), publicContext.refreshStatus(), publicContext.weather(),
                indicators,
                publicContext.policies(), publicContext.sources(), calculated.crops());
        completed.put(regionCode,result);
        return result;
    }

    private static String root(String code) {
        if (code.startsWith("2302")) return "230200";
        if (code.startsWith("2311")) return "231100";
        if (code.startsWith("1507")) return "150700";
        if (code.startsWith("2327")) return "232700";
        return code;
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }
}
