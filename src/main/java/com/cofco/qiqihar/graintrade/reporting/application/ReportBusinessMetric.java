package com.cofco.qiqihar.graintrade.reporting.application;

public record ReportBusinessMetric(
        String code,
        String label,
        String value,
        String unit,
        String aggregation,
        long sourceCount,
        String missingReason) {

    public ReportBusinessMetric {
        if (blank(code) || blank(label) || blank(unit) || blank(aggregation)
                || sourceCount < 0 || (value == null) == (missingReason == null)) {
            throw new IllegalArgumentException("Report business metric is invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
