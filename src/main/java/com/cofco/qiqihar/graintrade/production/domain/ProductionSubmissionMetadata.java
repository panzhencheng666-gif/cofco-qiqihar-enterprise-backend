package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

/** Required provenance for every newly created production sample. */
public record ProductionSubmissionMetadata(
        String reporterName,
        String surveyorName,
        String surveyorPhone,
        String sampleContact,
        String sampleLatitude,
        String sampleLongitude,
        Map<String, String> surveyDetails) {

    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\- ]{6,32}$");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SURVEYOR_NAME", "PROD_SURVEYOR_PHONE",
            "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE", "PROD_SAMPLE_NAME",
            "PROD_CULTIVAR_NAME",
            "PROD_HARVEST_AREA_MU", "PROD_AFFECTED_AREA_MU", "PROD_GROWTH_STATUS",
            "PROD_GROWTH_STAGE", "PROD_OPENING_INVENTORY", "PROD_SALES_VOLUME",
            "PROD_SELF_USE", "PROD_ENDING_INVENTORY", "PROD_INTENDED_AREA_MU",
            "PROD_INTENTION_REASON");
    private static final Set<String> DECIMAL_DETAILS = Set.of(
            "PROD_HARVEST_AREA_MU", "PROD_AFFECTED_AREA_MU", "PROD_OPENING_INVENTORY",
            "PROD_SALES_VOLUME", "PROD_SELF_USE", "PROD_ENDING_INVENTORY", "PROD_INTENDED_AREA_MU");

    public ProductionSubmissionMetadata {
        reporterName = required(reporterName, "reporter name");
        surveyorName = optional(surveyorName);
        surveyorPhone = optionalPhone(surveyorPhone, "surveyor phone");
        sampleContact = required(sampleContact, "sample contact");
        if (!PHONE.matcher(sampleContact).matches()) {
            throw new IllegalArgumentException("contact value is invalid");
        }
        sampleLatitude = coordinate(sampleLatitude, new BigDecimal("-90"), new BigDecimal("90"), "latitude");
        sampleLongitude = coordinate(sampleLongitude, new BigDecimal("-180"), new BigDecimal("180"), "longitude");
        Map<String, String> normalized = new LinkedHashMap<>();
        if (surveyDetails != null) surveyDetails.forEach((code, value) -> {
            if (value == null || value.isBlank()) return;
            String text = required(value, code);
            if (DECIMAL_DETAILS.contains(code)) {
                try {
                    BigDecimal decimal = new BigDecimal(text);
                    if (decimal.signum() < 0 || decimal.precision() > 18 || decimal.scale() > 4) {
                        throw new IllegalArgumentException(code + " is outside range");
                    }
                    text = decimal.stripTrailingZeros().toPlainString();
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(code + " is invalid", exception);
                }
            }
            normalized.put(code, text);
        });
        surveyDetails = Map.copyOf(normalized);
    }

    public static ProductionSubmissionMetadata from(Map<String, String> values) {
        if (values == null) throw new IllegalArgumentException("submission metadata is required");
        if (!ALLOWED_KEYS.containsAll(values.keySet())) {
            throw new IllegalArgumentException("submission metadata contains unknown field");
        }
        Map<String, String> surveyDetails = new LinkedHashMap<>(values);
        surveyDetails.keySet().removeAll(Set.of("PROD_REPORTER_NAME", "PROD_REPORTER_PHONE",
                "PROD_SURVEYOR_NAME", "PROD_SURVEYOR_PHONE",
                "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE"));
        return new ProductionSubmissionMetadata(
                values.get("PROD_REPORTER_NAME"), values.get("PROD_SURVEYOR_NAME"),
                firstPresent(values.get("PROD_SURVEYOR_PHONE"), values.get("PROD_REPORTER_PHONE")),
                values.get("PROD_SAMPLE_CONTACT"), values.get("PROD_SAMPLE_LATITUDE"),
                values.get("PROD_SAMPLE_LONGITUDE"), surveyDetails);
    }

    public Map<String, String> asMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROD_REPORTER_NAME", reporterName);
        putIfPresent(values, "PROD_SURVEYOR_NAME", surveyorName);
        putIfPresent(values, "PROD_SURVEYOR_PHONE", surveyorPhone);
        values.put("PROD_SAMPLE_CONTACT", sampleContact);
        values.put("PROD_SAMPLE_LATITUDE", sampleLatitude);
        values.put("PROD_SAMPLE_LONGITUDE", sampleLongitude);
        values.putAll(surveyDetails);
        return Map.copyOf(values);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > 500) throw new IllegalArgumentException(name + " is too long");
        return value.trim();
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 500) throw new IllegalArgumentException("value is too long");
        return value.trim();
    }

    private static String optionalPhone(String value, String name) {
        String normalized = optional(value);
        if (normalized != null && !PHONE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String firstPresent(String preferred, String legacy) {
        return preferred == null || preferred.isBlank() ? legacy : preferred;
    }

    private static void putIfPresent(Map<String, String> values, String code, String value) {
        if (value != null && !value.isBlank()) values.put(code, value);
    }

    private static String coordinate(String value, BigDecimal min, BigDecimal max, String name) {
        String normalized = required(value, name);
        try {
            BigDecimal parsed = new BigDecimal(normalized);
            if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
                throw new IllegalArgumentException(name + " is outside range");
            }
            return normalized;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is invalid", exception);
        }
    }
}
