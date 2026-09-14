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
        String coverageDescription,
        RegionFacts regionFacts,
        String sourceSummary,
        String calculationMethod,
        RefreshStatus refreshStatus,
        Weather weather,
        List<Indicator> indicators,
        List<Policy> policies,
        List<Source> sources,
        List<Crop> crops) {

    public record RegionFacts(
            BigDecimal areaSquareKilometres,
            int directChildCount,
            int countyCount,
            int townshipCount,
            int villageCount) {}

    public record Crop(
            String productCode,
            String productName,
            String dataKind,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            BigDecimal totalOutputKg,
            BigDecimal structurePercent,
            String basis,
            String formula,
            BigDecimal confidencePercent,
            BigDecimal uncertaintyLowKg,
            BigDecimal uncertaintyHighKg,
            List<Forecast> forecasts) {}

    public record Forecast(
            int year,
            BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg,
            BigDecimal totalOutputKg,
            String formula,
            BigDecimal confidencePercent) {}

    public record RefreshStatus(
            String cadence, String status, String lastAttemptAt,
            String lastSuccessAt, String nextRefreshAt) {}

    public record Weather(
            BigDecimal meanTemperatureC, BigDecimal precipitationMm,
            BigDecimal soilMoisturePercent, String risk, String assessment,
            String observedAt, String sourceId) {}

    public record Indicator(
            String category, String label, BigDecimal value, String unit,
            int dataYear, String dataKind, String method,
            String sourceName, String sourceUrl, String verifiedAt) {}

    public record Policy(
            String title, String publishedOn, String sourceName,
            String sourceUrl, String affectedCrops, String impact) {}

    public record Source(
            String id, String type, String name, String url,
            String sourceClass, BigDecimal reliabilityWeight,
            String publishedOn, String fetchedAt, String status, String evidence) {}
}
