package com.cofco.qiqihar.graintrade.designsample.metadata.domain;

import java.util.List;
import java.util.Objects;

public record DesignSampleFieldDefinition(
        String code,
        String sectionCode,
        String label,
        String description,
        String valueType,
        Integer precision,
        Integer scale,
        Integer maxLength,
        String unit,
        List<String> enumOptions,
        boolean required,
        boolean nullable,
        Object defaultValue,
        boolean editable,
        String minimumValue,
        String maximumValue,
        String groupCode,
        int sortOrder,
        String analysisRole) {
    public DesignSampleFieldDefinition {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(sectionCode, "sectionCode must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");
        enumOptions = List.copyOf(Objects.requireNonNull(enumOptions, "enumOptions must not be null"));
        Objects.requireNonNull(groupCode, "groupCode must not be null");
        Objects.requireNonNull(analysisRole, "analysisRole must not be null");
    }
}
