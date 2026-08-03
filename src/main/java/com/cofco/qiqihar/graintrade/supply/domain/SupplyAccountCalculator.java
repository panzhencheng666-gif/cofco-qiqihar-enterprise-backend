package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SupplyAccountCalculator {
    public static final String DIFFERENCE_CODE = "INVENTORY_RECONCILIATION_DIFFERENCE";
    public static final String DIFFERENCE_LABEL = "库存核对差额（调查期末库存－采用后账面期末库存）";
    private static final Set<String> REQUIRED_OUTPUTS = Set.of(
            "TOTAL_SUPPLY", "TOTAL_USE", "CALCULATED_ENDING_INVENTORY",
            "ADOPTED_ENDING_INVENTORY", DIFFERENCE_CODE);
    private static final String ADJUSTMENT = "APPROVED_ADJUSTMENT";

    private SupplyAccountCalculator() { }

    public static SupplyAccountCalculation calculate(
            SupplyFormula formula, List<SupplySource> sources, BigDecimal approvedAdjustment) {
        validateFormula(formula);
        List<String> errors = validate(formula, sources);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(",", errors));
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        sources.forEach(source -> values.put(source.role(), normalize(source.adoptedValue(), formula)));
        values.put(ADJUSTMENT, normalize(approvedAdjustment, formula));

        List<SupplyFormula.Result> unresolved = new ArrayList<>(formula.results());
        while (!unresolved.isEmpty()) {
            int before = unresolved.size();
            unresolved.removeIf(result -> {
                if (result.terms().stream().anyMatch(term -> !values.containsKey(term.operandRole()))) {
                    return false;
                }
                BigDecimal value = result.terms().stream()
                        .map(term -> values.get(term.operandRole()).multiply(term.coefficient()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                values.put(result.role(), normalize(value, formula));
                return true;
            });
            if (unresolved.size() == before) {
                throw new IllegalArgumentException("Invalid supply formula dependency cycle");
            }
        }

        BigDecimal difference = values.get(DIFFERENCE_CODE);
        return new SupplyAccountCalculation(
                values.get("TOTAL_SUPPLY"), values.get("TOTAL_USE"),
                values.get("CALCULATED_ENDING_INVENTORY"), values.get(ADJUSTMENT),
                values.get("ADOPTED_ENDING_INVENTORY"), values.get("SURVEYED_ENDING_INVENTORY"),
                difference, difference.abs().compareTo(normalize(formula.tolerance(), formula)) <= 0,
                SupplyResultState.FORMAL_CANDIDATE);
    }

    public static List<String> validate(SupplyFormula formula, List<SupplySource> sources) {
        validateFormula(formula);
        List<String> errors = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SupplySource source : sources) {
            counts.merge(source.role(), 1, Integer::sum);
            if (source.approvalState() != ApprovalState.APPROVED) errors.add("UNAPPROVED_SOURCE");
            if (source.qualityState() == QualityState.BLOCKING) errors.add("QUALITY_BLOCKING_SOURCE");
        }
        Set<String> resultRoles = formula.results().stream().map(SupplyFormula.Result::role)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requiredInputs = new HashSet<>();
        formula.results().stream().flatMap(result -> result.terms().stream())
                .map(SupplyFormula.Term::operandRole)
                .filter(role -> !resultRoles.contains(role) && !ADJUSTMENT.equals(role))
                .forEach(requiredInputs::add);
        if (!counts.keySet().containsAll(requiredInputs)) errors.add("MISSING_REQUIRED_SOURCE");
        if (counts.values().stream().anyMatch(count -> count > 1)) errors.add("DUPLICATE_ROLE_MAPPING");
        return errors.stream().distinct().toList();
    }

    public static void validateFormula(SupplyFormula formula) {
        Map<String, SupplyFormula.Result> results = new LinkedHashMap<>();
        Set<Integer> orders = new HashSet<>();
        for (SupplyFormula.Result result : formula.results()) {
            if (results.put(result.role(), result) != null || !orders.add(result.order())) {
                throw new IllegalArgumentException("Invalid supply formula duplicate result");
            }
            Set<Integer> termOrders = new HashSet<>();
            if (result.terms().stream().anyMatch(term -> !termOrders.add(term.order()))) {
                throw new IllegalArgumentException("Invalid supply formula duplicate term order");
            }
        }
        if (!results.keySet().containsAll(REQUIRED_OUTPUTS)
                || REQUIRED_OUTPUTS.stream().anyMatch(role -> !results.get(role).required())) {
            throw new IllegalArgumentException("Invalid supply formula missing required output");
        }
        Set<String> inputs = formula.results().stream().flatMap(result -> result.terms().stream())
                .map(SupplyFormula.Term::operandRole).filter(role -> !results.containsKey(role))
                .collect(java.util.stream.Collectors.toSet());
        if (inputs.stream().anyMatch(role -> role.isBlank())) {
            throw new IllegalArgumentException("Invalid supply formula operand");
        }
        detectCycles(results);
    }

    private static void detectCycles(Map<String, SupplyFormula.Result> results) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String role : results.keySet()) visit(role, results, visiting, visited);
    }

    private static void visit(String role, Map<String, SupplyFormula.Result> results,
            Set<String> visiting, Set<String> visited) {
        if (visited.contains(role)) return;
        if (!visiting.add(role)) throw new IllegalArgumentException("Invalid supply formula dependency cycle");
        for (SupplyFormula.Term term : results.get(role).terms()) {
            if (results.containsKey(term.operandRole())) visit(term.operandRole(), results, visiting, visited);
        }
        visiting.remove(role);
        visited.add(role);
    }

    private static BigDecimal normalize(BigDecimal value, SupplyFormula formula) {
        if (value == null) throw new IllegalArgumentException("Supply decimal is required");
        BigDecimal normalized = value.setScale(formula.scale(), formula.roundingMode());
        if (normalized.precision() > formula.precision()) {
            throw new IllegalArgumentException("Supply decimal exceeds precision");
        }
        return normalized;
    }
}
