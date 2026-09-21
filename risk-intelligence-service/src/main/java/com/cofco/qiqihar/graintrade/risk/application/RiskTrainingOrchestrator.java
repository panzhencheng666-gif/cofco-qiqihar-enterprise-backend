package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class RiskTrainingOrchestrator {
    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(RiskTrainingOrchestrator.class);
    private final RiskTrainingRepository repository;
    private final RiskModelTrainer trainer;
    @SuppressWarnings("unused")
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public RiskTrainingOrchestrator(RiskTrainingRepository repository,RiskModelTrainer trainer,
            ObjectMapper json) {
        this(repository,trainer,json,Clock.systemUTC(),Duration.ofMinutes(35));
    }

    RiskTrainingOrchestrator(RiskTrainingRepository repository,RiskModelTrainer trainer,
            ObjectMapper json,Clock clock,Duration leaseDuration) {
        this.repository=repository;
        this.trainer=trainer;
        this.json=json;
        this.clock=clock;
        this.leaseDuration=leaseDuration;
    }

    public int enqueueDueDailyExecutions() {
        return repository.enqueueDueDailyExecutions(clock.instant());
    }

    public boolean processNext(String workerId) {
        Instant now=clock.instant();
        int expired=repository.failExpiredExecutions(now);
        if (expired>0) LOG.warn("Marked abandoned risk model training as failed [count={}]",expired);
        var optional=repository.claimNext(now,workerId,leaseDuration);
        if (optional.isEmpty()) return false;
        RiskTrainingClaim claim=optional.get();
        UUID runId=null;
        try {
            RiskTrainingSnapshot snapshot=repository.freezeTrainingSnapshot(claim,now);
            int labels=snapshot.examples().size();
            if (labels<claim.minimumNewLabels()) {
                repository.skipExecution(claim.executionId(),"INSUFFICIENT_LABELS",
                        "可用监督标签 %d 条，低于策略门槛 %d 条"
                                .formatted(labels,claim.minimumNewLabels()),now);
                return true;
            }
            if (!trainer.supports(claim.modelKind())) {
                repository.skipExecution(claim.executionId(),"TRAINER_NOT_CONFIGURED",
                        "模型类型 %s 尚未配置独立训练器".formatted(claim.modelKind()),now);
                return true;
            }
            int version=repository.nextModelVersion(claim.modelId());
            runId=repository.createTrainingRun(claim,snapshot,now,
                    algorithmCode(claim.modelKind()));
            repository.markRunRunning(runId,now);
            RiskTrainingArtifact artifact=trainer.train(new RiskTrainingJob(
                    claim.modelId(),claim.modelCode(),claim.modelKind(),claim.domainCode(),
                    claim.baseModelReference(),version,
                    snapshot.trainingSnapshotId(),claim.randomSeed(),snapshot.examples()));
            repository.completeRunAndCreateCandidate(claim.executionId(),runId,claim.modelId(),
                    claim.domainCode(),version,artifact,clock.instant());
        } catch (Exception exception) {
            String message=safeMessage(exception);
            LOG.error("Risk model training failed [executionId={}, modelCode={}]",
                    claim.executionId(),claim.modelCode(),exception);
            if (runId==null) {
                repository.skipExecution(claim.executionId(),"TRAINING_SETUP_FAILED",message,
                        clock.instant());
            } else {
                repository.failRunAndExecution(claim.executionId(),runId,"TRAINING_FAILED",message,
                        clock.instant());
            }
        }
        return true;
    }

    private static String safeMessage(Exception exception) {
        String message=exception.getMessage();
        if (message==null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length()<=1000?message:message.substring(0,1000);
    }

    private static String algorithmCode(String modelKind) {
        return "DOMAIN_LLM".equals(modelKind)
                ? "external-lora-adapter-v1" : "builtin-bernoulli-naive-bayes";
    }
}
