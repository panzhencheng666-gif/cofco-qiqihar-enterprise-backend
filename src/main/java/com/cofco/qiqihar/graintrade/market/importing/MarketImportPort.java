package com.cofco.qiqihar.graintrade.market.importing;

/** Explicit market-module boundary used by the shared import workflow. */
public interface MarketImportPort {
    void validate(MarketImportRow row);

    String importRow(MarketImportRow row);
}
