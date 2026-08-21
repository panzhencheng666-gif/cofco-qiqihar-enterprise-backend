package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class ObservableSupplyCalculator {
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal KILOGRAMS_PER_TONNE = new BigDecimal("1000");

    private ObservableSupplyCalculator() { }

    public static BigDecimal estimatedOutputTonnes(
            BigDecimal cultivatedAreaMu, BigDecimal yieldPerMuKilograms) {
        requireNonNegative(cultivatedAreaMu, "Cultivated area");
        requireNonNegative(yieldPerMuKilograms, "Yield per mu");
        return normalize(cultivatedAreaMu.multiply(yieldPerMuKilograms)
                .divide(KILOGRAMS_PER_TONNE));
    }

    public static ProductionSourceBalance productionSourceBalance(
            BigDecimal openingInventoryTonnes,
            BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms,
            BigDecimal salesTonnes,
            BigDecimal selfUseTonnes,
            BigDecimal reportedEndingInventoryTonnes) {
        List<String> missing = new ArrayList<>();
        addMissing(missing, openingInventoryTonnes, "OPENING_INVENTORY_MISSING");
        addMissing(missing, cultivatedAreaMu, "CULTIVATED_AREA_MISSING");
        addMissing(missing, yieldPerMuKilograms, "YIELD_PER_MU_MISSING");
        addMissing(missing, salesTonnes, "SALES_VOLUME_MISSING");
        addMissing(missing, selfUseTonnes, "SELF_USE_MISSING");
        addMissing(missing, reportedEndingInventoryTonnes, "REPORTED_ENDING_INVENTORY_MISSING");
        if (!missing.isEmpty()) {
            return new ProductionSourceBalance(
                    AnalysisQualityState.PARTIAL, null, null, null, null,
                    normalizeNullable(reportedEndingInventoryTonnes), null, missing);
        }

        requireNonNegative(openingInventoryTonnes, "Opening inventory");
        requireNonNegative(salesTonnes, "Sales volume");
        requireNonNegative(selfUseTonnes, "Self use");
        requireNonNegative(reportedEndingInventoryTonnes, "Reported ending inventory");
        BigDecimal estimatedOutput = estimatedOutputTonnes(cultivatedAreaMu, yieldPerMuKilograms);
        BigDecimal productionAvailable = normalize(openingInventoryTonnes.add(estimatedOutput));
        BigDecimal knownDestination = normalize(salesTonnes.add(selfUseTonnes));
        BigDecimal theoreticalEnding = normalize(productionAvailable.subtract(knownDestination));
        BigDecimal difference = normalize(reportedEndingInventoryTonnes.subtract(theoreticalEnding));
        if (theoreticalEnding.signum() < 0) {
            return new ProductionSourceBalance(
                    AnalysisQualityState.BLOCKED, estimatedOutput, productionAvailable,
                    knownDestination, theoreticalEnding, normalize(reportedEndingInventoryTonnes),
                    difference, List.of("NEGATIVE_THEORETICAL_ENDING_INVENTORY"));
        }
        return new ProductionSourceBalance(
                AnalysisQualityState.AVAILABLE, estimatedOutput, productionAvailable,
                knownDestination, theoreticalEnding, normalize(reportedEndingInventoryTonnes),
                difference, List.of());
    }

    public static ObservableSupplyCalculation calculate(ObservableQuantityInput input) {
        if (input == null) throw new IllegalArgumentException("Observable quantity input is required");
        if (input.approvedRecordCount() == 0) {
            return result(input, AnalysisQualityState.NO_APPROVED_DATA, null, null, null,
                    List.of("NO_APPROVED_DATA"));
        }

        requireNonNegativeIfPresent(
                input.openingObservableInventoryTonnes(), "Opening observable inventory");
        requireNonNegativeIfPresent(input.expectedOutputTonnes(), "Expected output");
        requireNonNegativeIfPresent(input.inflowTonnes(), "Inflow");
        requireNonNegativeIfPresent(input.selfUseTonnes(), "Self use");
        requireNonNegativeIfPresent(input.outflowTonnes(), "Outflow");
        requireNonNegativeIfPresent(
                input.endingObservableInventoryTonnes(), "Ending observable inventory");

        List<String> missing = new ArrayList<>();
        addMissing(missing, input.openingObservableInventoryTonnes(), "OPENING_OBSERVABLE_INVENTORY_MISSING");
        addMissing(missing, input.expectedOutputTonnes(), "EXPECTED_OUTPUT_MISSING");
        addMissing(missing, input.inflowTonnes(), "INFLOW_MISSING");
        addMissing(missing, input.selfUseTonnes(), "SELF_USE_MISSING");
        addMissing(missing, input.outflowTonnes(), "OUTFLOW_MISSING");
        addMissing(missing, input.endingObservableInventoryTonnes(), "ENDING_OBSERVABLE_INVENTORY_MISSING");
        if (input.openingObservableInventoryTonnes() != null && !input.openingInventoryComplete()) {
            missing.add("OPENING_OBSERVABLE_INVENTORY_INCOMPLETE");
        }
        if (input.endingObservableInventoryTonnes() != null && !input.endingInventoryComplete()) {
            missing.add("ENDING_OBSERVABLE_INVENTORY_INCOMPLETE");
        }
        if (input.inventoryReviewGroupCount() > 0) {
            missing.add("INVENTORY_POSITION_REVIEW_REQUIRED");
            return result(input, AnalysisQualityState.COVERAGE_REVIEW_REQUIRED, null, null, null, missing);
        }
        if (!missing.isEmpty()) {
            return result(input, AnalysisQualityState.PARTIAL, null, null, null, missing);
        }

        BigDecimal totalSupply = normalize(
                input.openingObservableInventoryTonnes()
                        .add(input.expectedOutputTonnes())
                        .add(input.inflowTonnes()));
        BigDecimal inferredOtherAbsorption = normalize(
                totalSupply
                        .subtract(input.selfUseTonnes())
                        .subtract(input.outflowTonnes())
                        .subtract(input.endingObservableInventoryTonnes()));
        if (inferredOtherAbsorption.signum() < 0) {
            return result(input, AnalysisQualityState.BLOCKED, inferredOtherAbsorption,
                    totalSupply, null,
                    List.of("NEGATIVE_INFERRED_OTHER_ABSORPTION"));
        }
        BigDecimal totalUse = normalize(input.selfUseTonnes()
                .add(input.outflowTonnes())
                .add(inferredOtherAbsorption));
        return result(input, AnalysisQualityState.AVAILABLE, inferredOtherAbsorption,
                totalSupply, totalUse, List.of());
    }

    private static ObservableSupplyCalculation result(
            ObservableQuantityInput input,
            AnalysisQualityState state,
            BigDecimal inferredOtherAbsorption,
            BigDecimal totalSupply,
            BigDecimal totalUse,
            List<String> issues) {
        return new ObservableSupplyCalculation(
                state,
                normalizeNullable(input.openingObservableInventoryTonnes()),
                normalizeNullable(input.expectedOutputTonnes()),
                normalizeNullable(input.inflowTonnes()),
                normalizeNullable(input.selfUseTonnes()),
                normalizeNullable(input.outflowTonnes()),
                normalizeNullable(input.endingObservableInventoryTonnes()),
                normalizeNullable(inferredOtherAbsorption),
                normalizeNullable(totalSupply),
                normalizeNullable(totalUse),
                issues);
    }

    private static void addMissing(
            List<String> issues, BigDecimal value, String issue) {
        if (value == null) issues.add(issue);
    }

    private static BigDecimal normalizeNullable(BigDecimal value) {
        return value == null ? null : normalize(value);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    private static void requireNonNegativeIfPresent(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }
}
