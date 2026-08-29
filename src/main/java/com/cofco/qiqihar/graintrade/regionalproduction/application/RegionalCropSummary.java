package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;

public record RegionalCropSummary(
        String regionCode,
        String regionName,
        String administrativeLevel,
        int year,
        String productCode,
        BigDecimal plantedAreaMu,
        BigDecimal yieldPerMuKg,
        BigDecimal totalOutputKg,
        BigDecimal areaChangeWanMu,
        BigDecimal areaChangeRatePercent,
        boolean currentDataAvailable,
        boolean comparisonAvailable,
        boolean areaChangeRateAvailable,
        String comparisonMessage) {}
