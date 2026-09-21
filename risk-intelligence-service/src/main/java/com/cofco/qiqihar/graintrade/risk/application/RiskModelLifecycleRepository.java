package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.List;

public interface RiskModelLifecycleRepository {
    int startEligibleShadowCandidates(Instant now);
    List<RiskScoringTask> findPendingScoringTasks(Instant now,int limit);
    void recordPrediction(RiskScoringTask task,RiskModelScore score,Instant scoredAt);
    List<RiskPromotionCheck> findPromotionChecks(Instant now);
    void recordPromotionDecision(RiskPromotionCheck check,RiskPromotionDecision decision,Instant now);
    List<RiskRollbackCheck> findRollbackChecks(Instant now);
    void rollback(RiskRollbackCheck check,RiskPromotionDecision decision,Instant now);
}
