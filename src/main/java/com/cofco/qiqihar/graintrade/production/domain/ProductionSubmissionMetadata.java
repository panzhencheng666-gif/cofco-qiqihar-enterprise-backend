package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

/** Required provenance for every newly created production sample. */
public record ProductionSubmissionMetadata(
        String reporterName,
        String reporterPhone,
        String sampleContact,
        String sampleLatitude,
        String sampleLongitude) {

    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\- ]{6,32}$");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE");

    public ProductionSubmissionMetadata {
        reporterName = required(reporterName, "reporter name");
        reporterPhone = required(reporterPhone, "reporter phone");
        sampleContact = required(sampleContact, "sample contact");
        if (!PHONE.matcher(reporterPhone).matches() || !PHONE.matcher(sampleContact).matches()) {
            throw new IllegalArgumentException("contact value is invalid");
        }
        sampleLatitude = coordinate(sampleLatitude, new BigDecimal("-90"), new BigDecimal("90"), "latitude");
        sampleLongitude = coordinate(sampleLongitude, new BigDecimal("-180"), new BigDecimal("180"), "longitude");
    }

    public static ProductionSubmissionMetadata from(Map<String, String> values) {
        if (values == null) throw new IllegalArgumentException("submission metadata is required");
        if (!ALLOWED_KEYS.containsAll(values.keySet())) {
            throw new IllegalArgumentException("submission metadata contains unknown field");
        }
        return new ProductionSubmissionMetadata(
                values.get("PROD_REPORTER_NAME"), values.get("PROD_REPORTER_PHONE"),
                values.get("PROD_SAMPLE_CONTACT"), values.get("PROD_SAMPLE_LATITUDE"),
                values.get("PROD_SAMPLE_LONGITUDE"));
    }

    public Map<String, String> asMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROD_REPORTER_NAME", reporterName);
        values.put("PROD_REPORTER_PHONE", reporterPhone);
        values.put("PROD_SAMPLE_CONTACT", sampleContact);
        values.put("PROD_SAMPLE_LATITUDE", sampleLatitude);
        values.put("PROD_SAMPLE_LONGITUDE", sampleLongitude);
        return Map.copyOf(values);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > 500) throw new IllegalArgumentException(name + " is too long");
        return value.trim();
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
