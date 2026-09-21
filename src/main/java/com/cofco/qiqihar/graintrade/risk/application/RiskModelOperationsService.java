package com.cofco.qiqihar.graintrade.risk.application;

import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskModelOperationsService {
    private final RiskTrainingRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public RiskModelOperationsService(RiskTrainingRepository repository,AccessControl access,
            BusinessAuditRecorder audit,Clock clock) {
        this.repository=repository;
        this.access=access;
        this.audit=audit;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public RiskModelOverview overview() {
        access.requireBusinessReadScope();
        return new RiskModelOverview(repository.findModels(),repository.findRecentExecutions(50),
                clock.instant());
    }

    @Transactional
    public RiskTrainingRequest requestTraining(UUID modelId) {
        var actor=access.require("BUSINESS_UPDATE",null);
        Instant now=clock.instant();
        UUID executionId=repository.enqueueManualExecution(modelId,actor.subjectId(),now);
        audit.record(actor,"RISK_AI_MODEL",modelId.toString(),"RISK_TRAINING_REQUESTED",now,
                "{\"executionId\":\""+executionId+"\"}");
        return new RiskTrainingRequest(executionId,modelId,"QUEUED",now);
    }
}
