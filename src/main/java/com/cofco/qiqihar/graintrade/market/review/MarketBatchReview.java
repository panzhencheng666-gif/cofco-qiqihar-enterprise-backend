package com.cofco.qiqihar.graintrade.market.review;

import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import org.springframework.stereotype.Component;

/** Public market-module command used by governed cross-domain review orchestration. */
@Component
public class MarketBatchReview {
    private final MarketMonitoringService records;

    public MarketBatchReview(MarketMonitoringService records) {
        this.records = records;
    }

    public void approve(String recordId) {
        long version = records.detail(recordId).record().version();
        records.approve(recordId, version);
    }
}
