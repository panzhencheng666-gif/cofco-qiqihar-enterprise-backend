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
        String sourceSummary,
        String calculationMethod,
        List<CropResponse> crops) {

    static RegionalAgricultureProfileResponse from(RegionalAgricultureProfile value) {
        return new RegionalAgricultureProfileResponse(
                value.regionCode(), value.regionName(), value.administrativeLevel(), value.year(),
                value.automatic(), value.generatedAt(), value.sourceSummary(), value.calculationMethod(),
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
            List<ForecastResponse> forecasts) {
        static CropResponse from(RegionalAgricultureProfile.Crop value) {
            return new CropResponse(
                    value.productCode(), value.productName(), value.dataKind(), decimal(value.plantedAreaMu()),
                    decimal(value.yieldPerMuKg()), decimal(value.totalOutputKg()),
                    decimal(value.structurePercent()), value.basis(),
                    value.forecasts().stream().map(ForecastResponse::from).toList());
        }
    }

    public record ForecastResponse(
            int year, String plantedAreaMu, String yieldPerMuKg, String totalOutputKg) {
        static ForecastResponse from(RegionalAgricultureProfile.Forecast value) {
            return new ForecastResponse(value.year(), decimal(value.plantedAreaMu()),
                    decimal(value.yieldPerMuKg()), decimal(value.totalOutputKg()));
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
