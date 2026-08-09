package com.cofco.qiqihar.graintrade.logistics.importing;

import java.util.List;

public record LogisticsImportDefinition(String productCode, List<Field> fields) {
    public LogisticsImportDefinition { fields = List.copyOf(fields); }
    public record Field(String code, String label, String unit, boolean required, boolean readOnly) {}
}
