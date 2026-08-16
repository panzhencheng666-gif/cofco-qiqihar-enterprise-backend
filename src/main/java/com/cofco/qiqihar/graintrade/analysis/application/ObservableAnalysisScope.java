package com.cofco.qiqihar.graintrade.analysis.application;

public record ObservableAnalysisScope(
        String productCode,
        String regionCode,
        int surveyYear,
        Integer surveyMonth,
        String cultivarCode,
        String subjectTypeCode) {

    public ObservableAnalysisScope {
        productCode = required(productCode, "Product code");
        regionCode = required(regionCode, "Region code");
        if (surveyYear < 1900 || surveyYear > 2200
                || (surveyMonth != null && (surveyMonth < 1 || surveyMonth > 12))) {
            throw new IllegalArgumentException("Survey period is invalid");
        }
        cultivarCode = optional(cultivarCode);
        subjectTypeCode = optional(subjectTypeCode);
    }

    public String canonicalKey() {
        return String.join("|",
                productCode,
                regionCode,
                Integer.toString(surveyYear),
                surveyMonth == null ? "YEAR" : "%02d".formatted(surveyMonth),
                cultivarCode == null ? "*" : cultivarCode,
                subjectTypeCode == null ? "*" : subjectTypeCode);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
