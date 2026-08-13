package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;

/** Stable product/object-specific production field contract for bulk imports. */
public record ProductionImportDefinition(
        String productCode, String objectTypeCode, String contractVersion,
        List<ProductionSurveyField> fields, List<Group> groups) {
    public ProductionImportDefinition {
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
    }

    public ProductionImportDefinition(String productCode, String objectTypeCode, List<Group> groups) {
        this(productCode, objectTypeCode, ProductionSurveyFieldContract.VERSION, List.of(), groups);
    }

    public record Group(String code, String label, List<Field> fields) {
        public Group {
            fields = List.copyOf(fields);
        }
    }

    public record Field(
            String code, String label, String unit, int precision, int scale) {
        public String displayLabel() {
            return unit == null || unit.isBlank() ? label : label + "（" + unit + "）";
        }
    }
}
