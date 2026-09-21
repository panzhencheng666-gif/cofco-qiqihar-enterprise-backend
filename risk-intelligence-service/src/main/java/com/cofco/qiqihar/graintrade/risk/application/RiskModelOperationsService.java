package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskModelOperationsService {
    private final RiskTrainingRepository repository;
    private final Clock clock;

    public RiskModelOperationsService(RiskTrainingRepository repository,Clock clock) {
        this.repository=repository;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public RiskModelOverview overview() {
        return new RiskModelOverview(repository.findModels(),repository.findRecentExecutions(50),
                repository.findRecentActivationEvents(50),clock.instant());
    }

    @Transactional
    public RiskTrainingRequest requestTraining(UUID modelId,String actorSubject) {
        Instant now=clock.instant();
        UUID executionId=repository.enqueueManualExecution(modelId,actorSubject,now);
        return new RiskTrainingRequest(executionId,modelId,"QUEUED",now);
    }
}
