package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import com.cofco.qiqihar.graintrade.market.importing.MarketReturnedCorrectionPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies one returned-record correction atomically without creating an import draft. */
@Service
public class MarketReturnedCorrectionRowService {
    private final MarketReturnedCorrectionPort port;

    public MarketReturnedCorrectionRowService(MarketReturnedCorrectionPort port) {
        this.port = port;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String correctAndSubmit(
            String originalId, long originalVersion, MarketImportRow row) {
        return port.correctAndSubmit(originalId, originalVersion, row);
    }
}
