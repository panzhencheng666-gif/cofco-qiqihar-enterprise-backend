package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.time.Instant;

public record RegionalCropAnnualStat(
        String regionCode,
        String regionName,
        String prefectureCode,
        int dataYear,
        String productCode,
        BigDecimal plantedAreaMu,
        BigDecimal yieldPerMuKg,
        BigDecimal totalOutputKg,
        long version,
        Instant updatedAt) {}
