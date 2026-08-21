package com.cofco.qiqihar.graintrade.logistics.review;

import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import org.springframework.stereotype.Component;

/** Public logistics-module command used by governed cross-domain review orchestration. */
@Component
public class LogisticsBatchReview {
    private final LogisticsService records;

    public LogisticsBatchReview(LogisticsService records) {
        this.records = records;
    }

    public void approve(String recordId) {
        long version = records.detail(recordId).version();
        records.approve(recordId, version);
    }
}
