package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiskModelLifecycleOrchestrator {
    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(RiskModelLifecycleOrchestrator.class);
    private final RiskModelLifecycleRepository repository;
    private final RiskLiveScorer scorer;
    private final RiskPromotionGate gate;
    private final Clock clock;

    @Autowired
    public RiskModelLifecycleOrchestrator(RiskModelLifecycleRepository repository,
            RiskLiveScorer scorer,RiskPromotionGate gate) {
        this(repository,scorer,gate,Clock.systemUTC());
    }

    public RiskModelLifecycleOrchestrator(RiskModelLifecycleRepository repository,
            RiskLiveScorer scorer,RiskPromotionGate gate,Clock clock) {
        this.repository=repository;
        this.scorer=scorer;
        this.gate=gate;
        this.clock=clock;
    }

    public int process() {
        Instant now=clock.instant();
        int changed=repository.startEligibleShadowCandidates(now);
        for (RiskScoringTask task:repository.findPendingScoringTasks(now,200)) {
            try {
                if (!scorer.supports(task.modelKind())) continue;
                RiskModelScore score=scorer.score(task);
                repository.recordPrediction(task,score,now);
                changed++;
            } catch (Exception exception) {
                LOG.error("Risk model live scoring failed [modelId={}, version={}, assessmentId={}]",
                        task.modelId(),task.modelVersion(),task.assessmentId(),exception);
            }
        }
        for (RiskPromotionCheck check:repository.findPromotionChecks(now)) {
            RiskPromotionDecision decision=gate.evaluate(check.outcomes(),check.minimumLabels(),
                    check.minimumF1(),check.maximumF1Regression());
            if (decision.status()!=RiskPromotionDecision.Status.WAITING) {
                repository.recordPromotionDecision(check,decision,now);
                changed++;
            }
        }
        for (RiskRollbackCheck check:repository.findRollbackChecks(now)) {
            RiskPromotionDecision decision=gate.evaluate(check.outcomes(),check.minimumLabels(),
                    check.minimumF1(),check.rollbackF1Drop());
            if (decision.status()==RiskPromotionDecision.Status.REJECTED) {
                repository.rollback(check,decision,now);
                changed++;
            }
        }
        return changed;
    }
}
