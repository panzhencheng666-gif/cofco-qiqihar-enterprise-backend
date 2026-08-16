package com.cofco.qiqihar.graintrade.analysis.application;

public record ObservableMetric(
        String code,
        String label,
        String value,
        String unit,
        String aggregation,
        int sourceCount,
        String missingReason) {

    public ObservableMetric {
        if (blank(code) || blank(label) || blank(unit) || blank(aggregation) || sourceCount < 0) {
            throw new IllegalArgumentException("Observable metric metadata is invalid");
        }
        if (value == null && blank(missingReason)) {
            throw new IllegalArgumentException("A missing observable metric requires a reason");
        }
        if (value != null && missingReason != null) {
            throw new IllegalArgumentException("An available observable metric cannot have a missing reason");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
