package com.cofco.qiqihar.graintrade.risk.application;

import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import org.springframework.http.HttpStatus;

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
    public RiskModelOverview overview(RiskBusinessSession session) {
        requireGlobalModelAccess(session);
        return new RiskModelOverview(repository.findModels(),repository.findRecentExecutions(50),
                repository.findRecentActivationEvents(50),clock.instant());
    }

    @Transactional
    public RiskTrainingRequest requestTraining(UUID modelId,RiskBusinessSession session) {
        requireGlobalModelAccess(session);
        Instant now=clock.instant();
        UUID executionId=repository.enqueueManualExecution(modelId,session.subjectId(),now);
        return new RiskTrainingRequest(executionId,modelId,"QUEUED",now);
    }

    private static void requireGlobalModelAccess(RiskBusinessSession session) {
        if (session == null || !session.rootAdministrator()) {
            throw new RiskApiException(HttpStatus.FORBIDDEN, "RISK_GLOBAL_MODEL_FORBIDDEN",
                    "全域模型仅允许根管理员访问");
        }
    }
}
