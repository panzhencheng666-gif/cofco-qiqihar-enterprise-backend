package com.cofco.qiqihar.graintrade.market.importing;

import java.util.List;

/** Stable, database-owned market field contract exposed to the import workflow. */
public record MarketImportDefinition(
        String productCode,
        String objectTypeCode,
        List<Field> coreFields,
        List<Field> factFields) {
    public MarketImportDefinition {
        coreFields = List.copyOf(coreFields);
        factFields = List.copyOf(factFields);
    }

    public record Field(
            String code,
            String label,
            String controlType,
            String unit,
            boolean required,
            Integer precision,
            Integer scale) {
        public boolean readOnly() {
            return controlType != null && controlType.startsWith("READONLY");
        }

        public String displayLabel() {
            return unit == null || unit.isBlank() ? label : label + "（" + unit + "）";
        }
    }
}
