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
        String sourceSummary,
        String calculationMethod,
        RegionalAgricultureProfile.RefreshStatus refreshStatus,
        RegionalAgricultureProfile.Weather weather,
        List<RegionalAgricultureProfile.Policy> policies,
        List<RegionalAgricultureProfile.Source> sources,
        List<CropResponse> crops) {

    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value) {
        return new RegionalAgricultureProfileResponse(
                value.regionCode(), value.regionName(), value.administrativeLevel(), value.year(),
                value.automatic(), value.generatedAt(), value.coverageDescription(),
                value.sourceSummary(), value.calculationMethod(), value.refreshStatus(),
                value.weather(), value.policies(), value.sources(),
                value.crops().stream().map(CropResponse::from).toList());
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
