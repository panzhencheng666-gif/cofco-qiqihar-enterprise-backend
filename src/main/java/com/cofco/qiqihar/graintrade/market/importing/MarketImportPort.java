package com.cofco.qiqihar.graintrade.market.importing;

/** Explicit market-module boundary used by the shared import workflow. */
public interface MarketImportPort {
    MarketImportDefinition definition(String productCode, String objectTypeCode);

    void validate(MarketImportRow row);

    String importRow(MarketImportRow row);
}
