package com.cofco.qiqihar.graintrade.supply.application;

import com.cofco.qiqihar.graintrade.supply.domain.SupplyFormula;
import java.math.BigDecimal;
import java.util.List;

public record SupplyCalculationMaterial(
        FormulaDefinition formula,
        InputSet inputSet,
        DecisionState decision,
        int nextResultVersion) {

    public record FormulaDefinition(long id, String name, SupplyFormula formula) {}

    public record InputSet(
            String id,
            long version,
            String productCode,
            String regionCode,
            String marketingYear,
            String reason,
            List<Source> sources) {
        public InputSet { sources = List.copyOf(sources); }
    }

    public record Source(
            String releaseId,
            String domain,
            String recordId,
            long sourceVersion,
            String approvedAt,
            String qualityState,
            String roleCode,
            String roleLabel,
            String groupCode,
            int sortOrder,
            String sourceFieldCode,
            BigDecimal accountValue,
            String accountUnit) {}

    public record DecisionState(boolean exists, long version) {}
}
