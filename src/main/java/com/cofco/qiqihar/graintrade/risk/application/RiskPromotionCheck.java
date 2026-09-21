package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskPromotionCheck(
        UUID modelId,int modelVersion,Instant evaluationWindowStart,Instant evaluationWindowEnd,
        int minimumLabels,double minimumF1,double maximumF1Regression,
        List<RiskPredictionOutcome> outcomes) {
    public RiskPromotionCheck {
        outcomes=List.copyOf(outcomes);
    }
}
