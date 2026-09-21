package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public record OperationalFacilityImportResult(int importedRows, List<RowError> rowErrors) {
    public OperationalFacilityImportResult {
        rowErrors = rowErrors == null ? List.of() : List.copyOf(rowErrors);
    }

    public record RowError(int rowNumber, String field, String message) {}
}
