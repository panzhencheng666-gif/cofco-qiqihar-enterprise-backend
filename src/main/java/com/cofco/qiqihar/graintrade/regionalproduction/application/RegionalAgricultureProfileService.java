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
        if (year < 2001 || year > 2100) {
            throw invalid("REGIONAL_PROFILE_YEAR_INVALID", "地区农业档案年度必须在2001至2100之间");
        }
        var region = annualStats.region(regionCode)
                .orElseThrow(() -> invalid("REGIONAL_PROFILE_REGION_INVALID", "地区代码不存在"));
        if (!SUPPORTED_ROOTS.contains(root(region.code()))) {
            throw invalid("REGIONAL_PROFILE_REGION_UNSUPPORTED", "地区不在齐齐哈尔、黑河、呼伦贝尔或大兴安岭范围内");
        }
        var scope = access.requireBusinessReadScope();
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
        var calculated = calculator.calculate(region.code(), region.name(), region.administrativeLevel(),
                year, boundaryArea, observations);
        return new RegionalAgricultureProfile(
                calculated.regionCode(), calculated.regionName(), calculated.administrativeLevel(),
                calculated.year(), calculated.automatic(), calculated.generatedAt(),
                calculated.coverageDescription(),
                publicContext.sources().isEmpty()
                        ? calculated.sourceSummary()
                        : "公开统计、行政区边界、逐日天气和政策证据自动融合；缺项由模型补齐",
                calculated.calculationMethod(), publicContext.refreshStatus(), publicContext.weather(),
                publicContext.policies(), publicContext.sources(), calculated.crops());
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
