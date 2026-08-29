package com.cofco.qiqihar.graintrade.analysis.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ObservableHeadlineMetric(
        String code,
        BigDecimal value,
        long sourceCount,
        OffsetDateTime dataCutoffAt) {

    public ObservableHeadlineMetric {
        if (code == null || code.isBlank() || sourceCount < 0
                || (sourceCount == 0) != (value == null)
                || (sourceCount == 0) != (dataCutoffAt == null)) {
            throw new IllegalArgumentException("Observable headline metric is incomplete");
        }
    }
}
