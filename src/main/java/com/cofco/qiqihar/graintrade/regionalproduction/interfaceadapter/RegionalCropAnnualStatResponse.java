package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropAnnualStat;
import java.time.Instant;

public record RegionalCropAnnualStatResponse(
        String regionCode,
        String regionName,
        String prefectureCode,
        int dataYear,
        String productCode,
        String plantedAreaMu,
        String yieldPerMuKg,
        String totalOutputKg,
        long version,
        Instant updatedAt) {

    public static RegionalCropAnnualStatResponse from(RegionalCropAnnualStat value) {
        return new RegionalCropAnnualStatResponse(
                value.regionCode(), value.regionName(), value.prefectureCode(), value.dataYear(),
                value.productCode(), decimal(value.plantedAreaMu()), decimal(value.yieldPerMuKg()),
                decimal(value.totalOutputKg()), value.version(), value.updatedAt());
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
