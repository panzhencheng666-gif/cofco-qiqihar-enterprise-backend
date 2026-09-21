package com.cofco.qiqihar.riskintelligence.trainingnode;

import com.cofco.qiqihar.graintrade.risk.application.RiskModelLifecycleRepository;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelScore;
import com.cofco.qiqihar.graintrade.risk.application.RiskScoringTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class RemoteScoringCoordinator {
    private final RiskModelLifecycleRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;
    private final ConcurrentHashMap<String,Lease> leases=new ConcurrentHashMap<>();

    @Autowired
    RemoteScoringCoordinator(RiskModelLifecycleRepository repository,Clock clock,
            @Value("${qiqihar.risk.training.remote-node.scoring-lease-duration:35m}")
            Duration leaseDuration) {
        this.repository=repository;
        this.clock=clock;
        this.leaseDuration=leaseDuration;
    }

    Optional<RemoteScoringJob> claimNext(String nodeId) {
        Instant now=clock.instant();
        leases.entrySet().removeIf(entry -> !entry.getValue().leaseUntil().isAfter(now));
        for (RiskScoringTask task:repository.findPendingScoringTasks(now,200)) {
            if (!"DOMAIN_LLM".equals(task.modelKind())) continue;
            String key=key(task);
            Lease lease=new Lease(nodeId,task,now.plus(leaseDuration));
            if (leases.putIfAbsent(key,lease)==null) {
                return Optional.of(new RemoteScoringJob(task.modelId(),task.modelVersion(),
                        task.baseModelReference(),task.artifactReference(),task.artifactSha256(),
                        task.assessmentId(),task.canonicalEvidence(),task.lifecyclePhase(),
                        lease.leaseUntil()));
            }
        }
        return Optional.empty();
    }

    boolean complete(String nodeId,RemoteScoreCompletion completion) {
        if (!Double.isFinite(completion.positiveProbability())
                || completion.positiveProbability()<0d || completion.positiveProbability()>1d) {
            return false;
        }
        String key=key(completion.modelId(),completion.modelVersion(),completion.assessmentId());
        Lease lease=leases.get(key);
        Instant now=clock.instant();
        if (lease==null || !lease.nodeId().equals(nodeId) || !lease.leaseUntil().isAfter(now)
                || !lease.task().artifactReference().equals(completion.artifactReference())
                || !lease.task().artifactSha256().equals(completion.artifactSha256())) return false;
        repository.recordPrediction(lease.task(),new RiskModelScore(
                completion.predictedPositive(),completion.positiveProbability()),now);
        leases.remove(key,lease);
        return true;
    }

    private static String key(RiskScoringTask task) {
        return key(task.modelId(),task.modelVersion(),task.assessmentId());
    }

    private static String key(java.util.UUID modelId,int version,java.util.UUID assessmentId) {
        return modelId+":"+version+":"+assessmentId;
    }

    record RemoteScoreCompletion(java.util.UUID modelId,int modelVersion,
            java.util.UUID assessmentId,String artifactReference,String artifactSha256,
            boolean predictedPositive,double positiveProbability) { }
    private record Lease(String nodeId,RiskScoringTask task,Instant leaseUntil) { }
}
