package com.cofco.qiqihar.graintrade.supplybalance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SupplyBalanceView(
        String regionCode,
        String regionName,
        String administrativeLevel,
        int surveyYear,
        String productCode,
        boolean regionalProductionAvailable,
        long version,
        Instant updatedAt,
        List<Row> rows) {

    public SupplyBalanceView {
        rows = List.copyOf(rows);
    }

    public BigDecimal value(String code) {
        return rows.stream().filter(row -> row.code().equals(code)).findFirst().map(Row::value).orElse(null);
    }

    public String display(String code) {
        return rows.stream().filter(row -> row.code().equals(code)).findFirst().map(Row::display).orElse(null);
    }

    public record Row(
            String code, String label, SupplyBalanceProductContract.FieldKind kind,
            String unit, String requirement, BigDecimal value, String display, String note) {}
}
