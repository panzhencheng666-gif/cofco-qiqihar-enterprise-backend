package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;

/** One field in the versioned, cross-entry production survey contract. */
public record ProductionSurveyField(
        String code,
        String label,
        String groupCode,
        String groupLabel,
        int groupOrder,
        int sortOrder,
        String valueType,
        String controlType,
        String unit,
        boolean required,
        List<String> options,
        boolean readOnly,
        boolean calculated,
        boolean importable,
        boolean displayed,
        String description,
        int precision,
        int scale) {

    public ProductionSurveyField {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public String displayLabel() {
        return unit == null || unit.isBlank() ? label : label + "（" + unit + "）";
    }
}
