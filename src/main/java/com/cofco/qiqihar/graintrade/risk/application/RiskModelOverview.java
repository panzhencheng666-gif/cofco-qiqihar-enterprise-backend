package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.List;

public record RiskModelOverview(
        List<RiskModelSummary> models,
        List<RiskTrainingExecutionSummary> recentExecutions,
        List<RiskModelActivationSummary> recentActivationEvents,
        Instant readAt) {
    public RiskModelOverview {
        models=List.copyOf(models);
        recentExecutions=List.copyOf(recentExecutions);
        recentActivationEvents=List.copyOf(recentActivationEvents);
    }
}
