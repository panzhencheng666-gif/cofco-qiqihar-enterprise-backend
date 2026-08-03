package com.cofco.qiqihar.graintrade.supply.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyAccountCalculatorTest {
    private static final SupplyFormula FORMULA = formula("1");

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
        assertThat(SupplyAccountCalculator.validate(FORMULA, sources)).contains("UNAPPROVED_SOURCE");
        sources.set(0, source("OPENING_INVENTORY", "1.000"));
        sources.add(source("OPENING_INVENTORY", "2.000"));
        assertThat(SupplyAccountCalculator.validate(FORMULA, sources)).contains("DUPLICATE_ROLE_MAPPING");
        assertThatThrownBy(() -> AdoptionDecision.create(new BigDecimal("1.000"), " ", "actor", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executesVersionedTermsInsteadOfFrozenJavaArithmetic() {
        SupplyAccountCalculation changed = SupplyAccountCalculator.calculate(
                formula("2"), completeSources(), BigDecimal.ZERO);
        assertThat(changed.totalSupply().toPlainString()).isEqualTo("6.000");
    }

    @Test
    void failsClosedForACyclicDefinition() {
        SupplyFormula cyclic = new SupplyFormula("GRAIN_BALANCE", 3, 18, 3,
                RoundingMode.HALF_UP, new BigDecimal("0.500"), List.of(
                result("TOTAL_SUPPLY", 10, term("CALCULATED_ENDING_INVENTORY", "1", 10)),
                result("TOTAL_USE", 20, term("FOOD_USE", "1", 10)),
                result("CALCULATED_ENDING_INVENTORY", 30,
                        term("TOTAL_SUPPLY", "1", 10), term("TOTAL_USE", "-1", 20)),
                result("ADOPTED_ENDING_INVENTORY", 40,
                        term("CALCULATED_ENDING_INVENTORY", "1", 10), term("APPROVED_ADJUSTMENT", "1", 20)),
                result("INVENTORY_RECONCILIATION_DIFFERENCE", 50,
                        term("SURVEYED_ENDING_INVENTORY", "1", 10), term("ADOPTED_ENDING_INVENTORY", "-1", 20))));
        assertThatThrownBy(() -> SupplyAccountCalculator.calculate(cyclic, completeSources(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formula");
    }

    @Test
    void failsClosedWhenARequiredOperandRoleIsMissing() {
        List<SupplySource> missing = completeSources();
        missing.removeIf(source -> source.role().equals("IMPORTS"));

        assertThat(SupplyAccountCalculator.validate(FORMULA, missing))
                .contains("MISSING_REQUIRED_SOURCE");
        assertThatThrownBy(() -> SupplyAccountCalculator.calculate(FORMULA, missing, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_REQUIRED_SOURCE");
    }

    @Test
    void marksAnAccountOutsideToleranceAsUnbalanced() {
        List<SupplySource> sources = completeSources();
        for (int index = 0; index < 13; index++) {
            sources.set(index, source(sources.get(index).role(), "0"));
        }
        sources.set(13, source("SURVEYED_ENDING_INVENTORY", ".5005"));
        SupplyAccountCalculation result = SupplyAccountCalculator.calculate(FORMULA, sources, BigDecimal.ZERO);
        assertThat(result.inventoryReconciliationDifference().toPlainString()).isEqualTo("0.501");
        assertThat(result.balanced()).isFalse();
    }

    private static SupplyFormula formula(String productionCoefficient) {
        return new SupplyFormula("GRAIN_BALANCE", 1, 18, 3, RoundingMode.HALF_UP,
                new BigDecimal("0.500"), List.of(
                result("TOTAL_SUPPLY", 10, term("OPENING_INVENTORY", "1", 10),
                        term("LOCAL_PRODUCTION", productionCoefficient, 20), term("EXTERNAL_INFLOW", "1", 30),
                        term("IMPORTS", "1", 40), term("OTHER_SUPPLY", "1", 50)),
                result("TOTAL_USE", 20, term("FOOD_USE", "1", 10), term("FEED_USE", "1", 20),
                        term("SEED_USE", "1", 30), term("PROCESSING_USE", "1", 40), term("LOSS", "1", 50),
                        term("EXTERNAL_OUTFLOW", "1", 60), term("EXPORTS", "1", 70), term("OTHER_USE", "1", 80)),
                result("CALCULATED_ENDING_INVENTORY", 30, term("TOTAL_SUPPLY", "1", 10), term("TOTAL_USE", "-1", 20)),
                result("ADOPTED_ENDING_INVENTORY", 40, term("CALCULATED_ENDING_INVENTORY", "1", 10), term("APPROVED_ADJUSTMENT", "1", 20)),
                result("INVENTORY_RECONCILIATION_DIFFERENCE", 50, term("SURVEYED_ENDING_INVENTORY", "1", 10), term("ADOPTED_ENDING_INVENTORY", "-1", 20))));
    }

    private static SupplyFormula.Result result(String role, int order, SupplyFormula.Term... terms) {
        return new SupplyFormula.Result(role, role, true, order, List.of(terms));
    }

    private static SupplyFormula.Term term(String role, String coefficient, int order) {
        return new SupplyFormula.Term(role, new BigDecimal(coefficient), order);
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
