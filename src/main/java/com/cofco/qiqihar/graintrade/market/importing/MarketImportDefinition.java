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
            Integer scale,
            List<Option> options) {
        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public Field(String code, String label, String controlType, String unit,
                boolean required, Integer precision, Integer scale) {
            this(code, label, controlType, unit, required, precision, scale, List.of());
        }

        public boolean readOnly() {
            return controlType != null && controlType.startsWith("READONLY");
        }

        public String displayLabel() {
            return unit == null || unit.isBlank() ? label : label + "（" + unit + "）";
        }
    }

    public record Option(String value, String label) {
        public Option {
            if (value == null || value.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("INVALID_MARKET_IMPORT_OPTION");
            }
            value = value.trim();
            label = label.trim();
        }
    }
}
