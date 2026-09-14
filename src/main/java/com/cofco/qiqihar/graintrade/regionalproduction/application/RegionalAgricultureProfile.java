package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.util.List;

public record RegionalAgricultureProfile(
        String regionCode,
        String regionName,
        String administrativeLevel,
        int year,
        boolean automatic,
        String generatedAt,
        String sourceSummary,
        String calculationMethod,
        List<Crop> crops) {

    public record Crop(
            String productCode,
            String productName,
            String dataKind,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            BigDecimal totalOutputKg,
            BigDecimal structurePercent,
            String basis,
            List<Forecast> forecasts) {}

    public record Forecast(
            int year,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            BigDecimal totalOutputKg) {}
}
