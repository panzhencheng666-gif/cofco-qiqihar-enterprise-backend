package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SupplyAccountCalculator {
    public static final String DIFFERENCE_CODE = "INVENTORY_RECONCILIATION_DIFFERENCE";
    public static final String DIFFERENCE_LABEL = "库存核对差额（调查期末库存－采用后账面期末库存）";
    private static final Set<String> REQUIRED_ROLES = Set.of(
            "OPENING_INVENTORY", "LOCAL_PRODUCTION", "EXTERNAL_INFLOW", "IMPORTS", "OTHER_SUPPLY",
            "FOOD_USE", "FEED_USE", "SEED_USE", "PROCESSING_USE", "LOSS",
            "EXTERNAL_OUTFLOW", "EXPORTS", "OTHER_USE", "SURVEYED_ENDING_INVENTORY");
    private static final List<String> SUPPLY_ROLES = List.of(
            "OPENING_INVENTORY", "LOCAL_PRODUCTION", "EXTERNAL_INFLOW", "IMPORTS", "OTHER_SUPPLY");
    private static final List<String> USE_ROLES = List.of(
            "FOOD_USE", "FEED_USE", "SEED_USE", "PROCESSING_USE", "LOSS",
            "EXTERNAL_OUTFLOW", "EXPORTS", "OTHER_USE");

    private SupplyAccountCalculator() { }

    public static SupplyAccountCalculation calculate(
            SupplyFormula formula, List<SupplySource> sources, BigDecimal approvedAdjustment) {
        List<String> errors = validate(sources);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(",", errors));
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        sources.forEach(source -> values.put(source.role(), normalize(source.adoptedValue(), formula)));
        BigDecimal supply = sum(SUPPLY_ROLES, values, formula);
        BigDecimal use = sum(USE_ROLES, values, formula);
        BigDecimal calculated = normalize(supply.subtract(use), formula);
        BigDecimal adjustment = normalize(approvedAdjustment, formula);
        BigDecimal adopted = normalize(calculated.add(adjustment), formula);
        BigDecimal surveyed = values.get("SURVEYED_ENDING_INVENTORY");
        // Single authoritative sign: surveyed ending minus adopted book ending.
        BigDecimal difference = normalize(surveyed.subtract(adopted), formula);
        return new SupplyAccountCalculation(supply, use, calculated, adjustment, adopted, surveyed,
                difference, difference.abs().compareTo(formula.tolerance()) <= 0,
                SupplyResultState.FORMAL_CANDIDATE);
    }

    public static List<String> validate(List<SupplySource> sources) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SupplySource source : sources) {
            counts.merge(source.role(), 1, Integer::sum);
            if (source.approvalState() != ApprovalState.APPROVED) errors.add("UNAPPROVED_SOURCE");
            if (source.qualityState() == QualityState.BLOCKING) errors.add("QUALITY_BLOCKING_SOURCE");
        }
        if (!counts.keySet().containsAll(REQUIRED_ROLES)) errors.add("MISSING_REQUIRED_SOURCE");
        if (counts.values().stream().anyMatch(count -> count > 1)) errors.add("DUPLICATE_ROLE_MAPPING");
        return errors.stream().distinct().toList();
    }

    private static BigDecimal sum(List<String> roles, Map<String, BigDecimal> values, SupplyFormula formula) {
        return normalize(roles.stream().map(values::get).reduce(BigDecimal.ZERO, BigDecimal::add), formula);
    }

    private static BigDecimal normalize(BigDecimal value, SupplyFormula formula) {
        if (value == null) throw new IllegalArgumentException("Supply decimal is required");
        BigDecimal normalized = value.setScale(formula.scale(), RoundingMode.HALF_UP);
        if (normalized.precision() > formula.precision()) throw new IllegalArgumentException("Supply decimal exceeds precision");
        return normalized;
    }
}
