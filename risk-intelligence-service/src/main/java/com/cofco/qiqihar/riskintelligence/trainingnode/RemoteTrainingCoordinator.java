package com.cofco.qiqihar.riskintelligence.trainingnode;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RemoteTrainingCoordinator {
    private static final String MODEL_KIND="DOMAIN_LLM";
    private final RiskTrainingRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public RemoteTrainingCoordinator(RiskTrainingRepository repository,Clock clock,
            @Value("${qiqihar.risk.training.remote-node.lease-duration:35m}")
            Duration leaseDuration) {
        this.repository=repository;
        this.clock=clock;
        this.leaseDuration=leaseDuration;
    }

    public Optional<RemoteTrainingJob> claimNext(String nodeId) {
        var now=clock.instant();
        repository.failExpiredExecutions(now);
        var claimed=repository.claimNextByKind(now,workerId(nodeId),leaseDuration,MODEL_KIND);
        if (claimed.isEmpty()) return Optional.empty();
        var claim=claimed.orElseThrow();
        int newLabels=repository.countNewLabelsSinceLastSuccessfulRun(claim,now);
        if (newLabels<claim.minimumNewLabels()) {
            repository.skipExecution(claim.executionId(),"INSUFFICIENT_NEW_LABELS",
                    "上次成功训练后新增监督标签 %d 条，低于策略门槛 %d 条".formatted(
                            newLabels,claim.minimumNewLabels()),now);
            return Optional.empty();
        }
        var snapshot=repository.freezeTrainingSnapshot(claim,now);
        int version=repository.nextModelVersion(claim.modelId());
        UUID runId=repository.createTrainingRun(
                claim,snapshot,now,"external-lora-adapter-v1");
        repository.markRunRunning(runId,now);
        return Optional.of(new RemoteTrainingJob(
                claim.executionId(),runId,claim.modelId(),claim.modelCode(),claim.modelKind(),
                claim.domainCode(),claim.baseModelReference(),version,
                snapshot.trainingSnapshotId(),snapshot.dataSha256(),claim.randomSeed(),
                now.plus(leaseDuration),snapshot.examples().stream()
                .map(example -> new RemoteTrainingExample(
                        example.resolvedAt(),example.canonicalText(),example.positive()))
                .toList()));
    }

    public boolean renewLease(String nodeId,UUID executionId,UUID trainingRunId) {
        return repository.renewRemoteLease(executionId,trainingRunId,workerId(nodeId),
                clock.instant(),leaseDuration);
    }

    public boolean ownsLease(String nodeId,UUID executionId,UUID trainingRunId) {
        return repository.ownsRemoteLease(
                executionId,trainingRunId,workerId(nodeId),clock.instant());
    }

    public boolean complete(String nodeId,UUID executionId,UUID trainingRunId,
            int modelVersion,RemoteTrainingArtifact artifact) {
        return repository.completeRemoteRun(executionId,trainingRunId,workerId(nodeId),
                modelVersion,artifact.toArtifact(),clock.instant());
    }

    private static String workerId(String nodeId) {
        return "training-node:"+TrainingNodeCredential.requireNodeId(nodeId);
    }
}
