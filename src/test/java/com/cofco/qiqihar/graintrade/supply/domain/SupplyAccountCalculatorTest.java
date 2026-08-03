package com.cofco.qiqihar.graintrade.supply.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyAccountCalculatorTest {
    private static final SupplyFormula FORMULA = new SupplyFormula(
            "GRAIN_BALANCE_V1", 18, 3, new BigDecimal("0.500"));

    @Test
    void calculatesTheAuthoritativeAccountAndSurveyMinusAdoptedDifference() {
        SupplyAccountCalculation result = SupplyAccountCalculator.calculate(FORMULA, List.of(
                source("OPENING_INVENTORY", "10.125"), source("LOCAL_PRODUCTION", "20.125"),
                source("EXTERNAL_INFLOW", "3.100"), source("IMPORTS", "1.000"),
                source("OTHER_SUPPLY", "0.000"), source("FOOD_USE", "2.000"),
                source("FEED_USE", "4.000"), source("SEED_USE", "1.000"),
                source("PROCESSING_USE", "5.000"), source("LOSS", "0.500"),
                source("EXTERNAL_OUTFLOW", "2.000"), source("EXPORTS", "1.000"),
                source("OTHER_USE", "0.000"), source("SURVEYED_ENDING_INVENTORY", "19.000")),
                new BigDecimal("0.250"));

        assertThat(result.totalSupply().toPlainString()).isEqualTo("34.350");
        assertThat(result.totalUse().toPlainString()).isEqualTo("15.500");
        assertThat(result.calculatedEndingInventory().toPlainString()).isEqualTo("18.850");
        assertThat(result.adoptedEndingInventory().toPlainString()).isEqualTo("19.100");
        assertThat(result.inventoryReconciliationDifference().toPlainString()).isEqualTo("-0.100");
        assertThat(result.balanced()).isTrue();
    }

    @Test
    void keepsTrialForIneligibleOrDuplicateSourcesAndRequiresReasonsForDecisions() {
        List<SupplySource> sources = completeSources();
        sources.set(0, new SupplySource("OPENING_INVENTORY", "PRODUCTION", "r1", 1,
                ApprovalState.DRAFT, QualityState.PASSED, new BigDecimal("1.000"), "采用核定值", "/source/r1"));
        assertThat(SupplyAccountCalculator.validate(sources)).contains("UNAPPROVED_SOURCE");
        sources.set(0, source("OPENING_INVENTORY", "1.000"));
        sources.add(source("OPENING_INVENTORY", "2.000"));
        assertThat(SupplyAccountCalculator.validate(sources)).contains("DUPLICATE_ROLE_MAPPING");
        assertThatThrownBy(() -> AdoptionDecision.create(new BigDecimal("1.000"), " ", "actor", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<SupplySource> completeSources() {
        return new java.util.ArrayList<>(List.of(
                source("OPENING_INVENTORY", "1"), source("LOCAL_PRODUCTION", "1"),
                source("EXTERNAL_INFLOW", "1"), source("IMPORTS", "1"), source("OTHER_SUPPLY", "1"),
                source("FOOD_USE", "1"), source("FEED_USE", "1"), source("SEED_USE", "1"),
                source("PROCESSING_USE", "1"), source("LOSS", "1"), source("EXTERNAL_OUTFLOW", "1"),
                source("EXPORTS", "1"), source("OTHER_USE", "1"), source("SURVEYED_ENDING_INVENTORY", "1")));
    }

    private static SupplySource source(String role, String value) {
        return new SupplySource(role, role.equals("EXTERNAL_INFLOW") || role.equals("EXTERNAL_OUTFLOW")
                ? "LOGISTICS" : "PRODUCTION", role + "-record", 1, ApprovalState.APPROVED,
                QualityState.PASSED, new BigDecimal(value), "采用已核定来源", "/sources/" + role);
    }
}
