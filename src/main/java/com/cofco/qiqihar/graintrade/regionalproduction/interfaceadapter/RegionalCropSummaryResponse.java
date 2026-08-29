package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropSummary;
import java.math.BigDecimal;

public record RegionalCropSummaryResponse(
        String regionCode,
        String regionName,
        String administrativeLevel,
        int year,
        String productCode,
        String plantedAreaMu,
        String yieldPerMuKg,
        String totalOutputKg,
        String areaChangeWanMu,
        String areaChangeRatePercent,
        boolean currentDataAvailable,
        boolean comparisonAvailable,
        boolean areaChangeRateAvailable,
        String comparisonMessage) {

    static RegionalCropSummaryResponse from(RegionalCropSummary value) {
        return new RegionalCropSummaryResponse(
                value.regionCode(), value.regionName(), value.administrativeLevel(), value.year(),
                value.productCode(), decimal(value.plantedAreaMu()), decimal(value.yieldPerMuKg()),
                decimal(value.totalOutputKg()), decimal(value.areaChangeWanMu()),
                decimal(value.areaChangeRatePercent()), value.currentDataAvailable(),
                value.comparisonAvailable(), value.areaChangeRateAvailable(), value.comparisonMessage());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
