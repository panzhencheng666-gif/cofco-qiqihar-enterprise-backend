package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record RegionalAgricultureProfileResponse(
        String regionCode,
        String regionName,
        String administrativeLevel,
        int year,
        boolean automatic,
        String generatedAt,
        String coverageDescription,
        RegionalAgricultureProfile.RegionFacts regionFacts,
        String sourceSummary,
        String calculationMethod,
        RegionalAgricultureProfile.RefreshStatus refreshStatus,
        RegionalAgricultureProfile.Weather weather,
        List<RegionalAgricultureProfile.Indicator> indicators,
        List<RegionalAgricultureProfile.Policy> policies,
        List<RegionalAgricultureProfile.Source> sources,
        List<CropResponse> crops,
        com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatch estimateBatch,
        com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalHierarchyRefresh.CalculationStatus regionalCalculation,
        com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailways railway) {

    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value) {
        return from(value, null);
    }
    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatch batch) {
        return from(value,batch,null);
    }
    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatch batch,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalHierarchyRefresh.CalculationStatus status) {
        return from(value,batch,status,null);
    }
    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatch batch,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalHierarchyRefresh.CalculationStatus status,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailways railway) {
        return new RegionalAgricultureProfileResponse(
                value.regionCode(), value.regionName(), value.administrativeLevel(), value.year(),
                value.automatic(), value.generatedAt(), value.coverageDescription(),
                value.regionFacts(),
                value.sourceSummary(), value.calculationMethod(), value.refreshStatus(),
                value.weather(), value.indicators(), value.policies(), value.sources(),
                value.crops().stream().map(CropResponse::from).toList(),
                batch != null && value.regionCode().equals(batch.rootRegionCode()) && value.year() == batch.year()
                        ? batch : null,
                status, railway);
    }

    public record CropResponse(
            String productCode,
            String productName,
            String dataKind,
            String plantedAreaMu,
            String yieldPerMuKg,
            String totalOutputKg,
            String structurePercent,
            String basis,
            String formula,
            String confidencePercent,
            String uncertaintyLowKg,
            String uncertaintyHighKg,
            List<ForecastResponse> forecasts) {
        static CropResponse from(RegionalAgricultureProfile.Crop value) {
            return new CropResponse(
                    value.productCode(), value.productName(), value.dataKind(), decimal(value.plantedAreaMu()),
                    decimal(value.yieldPerMuKg()), decimal(value.totalOutputKg()),
                    decimal(value.structurePercent()), value.basis(), value.formula(),
                    decimal(value.confidencePercent()), decimal(value.uncertaintyLowKg()),
                    decimal(value.uncertaintyHighKg()),
                    value.forecasts().stream().map(ForecastResponse::from).toList());
        }
    }

    public record ForecastResponse(
            int year, String plantedAreaMu, String yieldPerMuKg, String totalOutputKg,
            String formula, String confidencePercent) {
        static ForecastResponse from(RegionalAgricultureProfile.Forecast value) {
            return new ForecastResponse(value.year(), decimal(value.plantedAreaMu()),
                    decimal(value.yieldPerMuKg()), decimal(value.totalOutputKg()),
                    value.formula(), decimal(value.confidencePercent()));
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
