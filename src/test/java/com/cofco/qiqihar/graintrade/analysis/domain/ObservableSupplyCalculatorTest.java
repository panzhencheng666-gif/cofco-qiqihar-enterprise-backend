package com.cofco.qiqihar.graintrade.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ObservableSupplyCalculatorTest {

    @Test
    void convertsAreaAndYieldFromKilogramsToTonnesWithoutBinaryFloatingPoint() {
        assertThat(ObservableSupplyCalculator.estimatedOutputTonnes(
                decimal("100"), decimal("500")))
                .isEqualByComparingTo("50.0000");
    }

    @Test
    void reconcilesTheProductionSourceBalanceBeforeAggregation() {
        ProductionSourceBalance balance = ObservableSupplyCalculator.productionSourceBalance(
                decimal("10"), decimal("100"), decimal("500"),
                decimal("20"), decimal("5"), decimal("35"));

        assertThat(balance.qualityState()).isEqualTo(AnalysisQualityState.AVAILABLE);
        assertThat(balance.estimatedOutputTonnes()).isEqualByComparingTo("50.0000");
        assertThat(balance.productionAvailableTonnes()).isEqualByComparingTo("60.0000");
        assertThat(balance.knownDestinationTonnes()).isEqualByComparingTo("25.0000");
        assertThat(balance.theoreticalEndingInventoryTonnes()).isEqualByComparingTo("35.0000");
        assertThat(balance.reconciliationDifferenceTonnes()).isEqualByComparingTo("0.0000");
        assertThat(balance.issues()).isEmpty();
    }

    @Test
    void calculatesTheObservableRegionalResidualFromCurrentSurveyInputs() {
        ObservableSupplyCalculation result = ObservableSupplyCalculator.calculate(
                input("10", "50", "5", "5", "15", "25", true, 6));

        assertThat(result.qualityState()).isEqualTo(AnalysisQualityState.AVAILABLE);
        assertThat(result.inferredOtherAbsorptionTonnes()).isEqualByComparingTo("20.0000");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void keepsAMissingCriticalQuantityMissingInsteadOfConvertingItToZero() {
        ObservableSupplyCalculation result = ObservableSupplyCalculator.calculate(
                input("10", "50", "5", "5", "15", null, true, 5));

        assertThat(result.qualityState()).isEqualTo(AnalysisQualityState.PARTIAL);
        assertThat(result.endingObservableInventoryTonnes()).isNull();
        assertThat(result.inferredOtherAbsorptionTonnes()).isNull();
        assertThat(result.issues()).containsExactly("ENDING_OBSERVABLE_INVENTORY_MISSING");
    }

    @Test
    void blocksANegativeInferredResidual() {
        ObservableSupplyCalculation result = ObservableSupplyCalculator.calculate(
                input("0", "10", "0", "5", "5", "5", true, 6));

        assertThat(result.qualityState()).isEqualTo(AnalysisQualityState.BLOCKED);
        assertThat(result.inferredOtherAbsorptionTonnes()).isEqualByComparingTo("-5.0000");
        assertThat(result.issues()).containsExactly("NEGATIVE_INFERRED_OTHER_ABSORPTION");
    }

    @Test
    void refusesToAddProductionAndMarketInventoryWhenMutualExclusivityIsUnknown() {
        ObservableSupplyCalculation result = ObservableSupplyCalculator.calculate(
                input("10", "50", "5", "5", "15", "25", false, 6));

        assertThat(result.qualityState()).isEqualTo(AnalysisQualityState.COVERAGE_REVIEW_REQUIRED);
        assertThat(result.inferredOtherAbsorptionTonnes()).isNull();
        assertThat(result.issues()).containsExactly("INVENTORY_MUTUAL_EXCLUSIVITY_UNPROVEN");
    }

    @Test
    void reportsNoApprovedDataBeforeAttemptingAnyCalculation() {
        ObservableSupplyCalculation result = ObservableSupplyCalculator.calculate(
                input(null, null, null, null, null, null, true, 0));

        assertThat(result.qualityState()).isEqualTo(AnalysisQualityState.NO_APPROVED_DATA);
        assertThat(result.inferredOtherAbsorptionTonnes()).isNull();
        assertThat(result.issues()).containsExactly("NO_APPROVED_DATA");
    }

    @Test
    void excludesMarketPurchasesAndSalesFromTheSupplyQuantityInputContract() {
        assertThat(Arrays.stream(ObservableQuantityInput.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("marketPurchaseTonnes", "marketSalesTonnes");
    }

    private static ObservableQuantityInput input(
            String openingInventory,
            String expectedOutput,
            String inflow,
            String selfUse,
            String outflow,
            String endingInventory,
            boolean inventoryMutuallyExclusive,
            int approvedRecordCount) {
        return new ObservableQuantityInput(
                decimal(openingInventory), decimal(expectedOutput), decimal(inflow),
                decimal(selfUse), decimal(outflow), decimal(endingInventory),
                inventoryMutuallyExclusive, approvedRecordCount);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
