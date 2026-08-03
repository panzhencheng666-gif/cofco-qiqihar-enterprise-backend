package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;

public record SupplyFormula(String version, int precision, int scale, BigDecimal tolerance) {
    public SupplyFormula {
        if (version == null || version.isBlank() || precision < 1 || scale < 0 || scale > precision
                || tolerance == null || tolerance.signum() < 0) {
            throw new IllegalArgumentException("Invalid supply formula metadata");
        }
    }
}
