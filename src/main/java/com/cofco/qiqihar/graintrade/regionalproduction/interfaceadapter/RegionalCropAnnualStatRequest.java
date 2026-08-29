package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import java.math.BigDecimal;

public record RegionalCropAnnualStatRequest(
        int dataYear,
        String productCode,
        BigDecimal plantedAreaMu,
        BigDecimal yieldPerMuKg,
        long expectedVersion) {}
