package com.cofco.qiqihar.graintrade.market.importing;

import java.util.List;

/** Authorized read boundary for market records eligible for in-place correction. */
public interface MarketReturnedCorrectionPort {
    List<MarketReturnedCorrectionRecord> returned(String productCode);

    String correctAndSubmit(String originalId, long originalVersion, MarketImportRow row);
}
