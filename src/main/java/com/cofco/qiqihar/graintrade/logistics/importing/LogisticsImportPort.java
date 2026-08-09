package com.cofco.qiqihar.graintrade.logistics.importing;

/** Explicit logistics-module boundary used by the shared import workflow. */
public interface LogisticsImportPort {
    LogisticsImportDefinition definition(String productCode);
    void validate(LogisticsImportRow row);
    String importRow(LogisticsImportRow row);
}
