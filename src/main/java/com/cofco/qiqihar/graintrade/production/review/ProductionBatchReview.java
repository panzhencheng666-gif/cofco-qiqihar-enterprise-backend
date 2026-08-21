package com.cofco.qiqihar.graintrade.production.review;

import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import org.springframework.stereotype.Component;

/** Public production-module command used by governed cross-domain review orchestration. */
@Component
public class ProductionBatchReview {
    private final ProductionRecordService records;

    public ProductionBatchReview(ProductionRecordService records) {
        this.records = records;
    }

    public void approve(String recordId) {
        long version = records.detail(recordId).record().version();
        records.approve(recordId, version);
    }
}
