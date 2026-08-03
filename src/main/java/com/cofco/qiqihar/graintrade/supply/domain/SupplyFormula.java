package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record SupplyFormula(
        String code,
        int version,
        int precision,
        int scale,
        RoundingMode roundingMode,
        BigDecimal tolerance,
        List<Result> results) {

    public SupplyFormula {
        if (code == null || code.isBlank() || version < 1 || precision < 1 || scale < 0
                || scale > precision || roundingMode == null || tolerance == null
                || tolerance.signum() < 0 || results == null) {
            throw new IllegalArgumentException("Invalid supply formula metadata");
        }
        results = List.copyOf(results);
    }

    public record Result(String role, String label, boolean required, int order, List<Term> terms) {
        public Result {
            if (role == null || role.isBlank() || label == null || label.isBlank() || order < 0
                    || terms == null || terms.isEmpty()) {
                throw new IllegalArgumentException("Invalid supply formula result");
            }
            terms = List.copyOf(terms);
        }
    }

    public record Term(String operandRole, BigDecimal coefficient, int order) {
        public Term {
            if (operandRole == null || operandRole.isBlank() || coefficient == null
                    || coefficient.signum() == 0 || order < 0) {
                throw new IllegalArgumentException("Invalid supply formula term");
            }
        }
    }
}
