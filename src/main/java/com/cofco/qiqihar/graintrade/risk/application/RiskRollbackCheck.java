package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import java.util.UUID;

public record RiskRollbackCheck(
        UUID modelId,int activeVersion,int standbyVersion,int minimumLabels,
        double minimumF1,double rollbackF1Drop,List<RiskPredictionOutcome> outcomes) {
    public RiskRollbackCheck {
        outcomes=List.copyOf(outcomes);
    }
}
