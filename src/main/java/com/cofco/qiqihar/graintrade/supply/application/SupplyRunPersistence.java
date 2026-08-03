package com.cofco.qiqihar.graintrade.supply.application;

import com.cofco.qiqihar.graintrade.supply.domain.SupplyAccountCalculation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SupplyRunPersistence(
        SupplyCalculationMaterial material,
        String formulaSnapshot,
        String productCode,
        String regionCode,
        String marketingYear,
        String resultState,
        List<String> validationCodes,
        SupplyAccountCalculation calculation,
        BigDecimal proposalValue,
        String proposalReason,
        String actor,
        Instant occurredAt,
        long decisionVersion) {
    public SupplyRunPersistence { validationCodes = List.copyOf(validationCodes); }
}
