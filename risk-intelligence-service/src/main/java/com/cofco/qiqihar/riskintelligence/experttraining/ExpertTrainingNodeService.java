package com.cofco.qiqihar.riskintelligence.experttraining;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ExpertTrainingNodeService {
    private final ExpertTrainingRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;

    public ExpertTrainingNodeService(ExpertTrainingRepository repository, Clock clock,
            @Value("${qiqihar.risk.training.remote-node.lease-duration:35m}") Duration leaseDuration) {
        this.repository = repository;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    @Transactional
    public Optional<ExpertTrainingRepository.Claim> claim(String nodeId) {
        return repository.claim(nodeId, clock.instant(), leaseDuration);
    }

    @Transactional
    public Optional<ExpertTrainingRepository.Heartbeat> heartbeat(UUID taskId, String nodeId) {
        return repository.heartbeat(taskId, nodeId, clock.instant(), leaseDuration);
    }

    @Transactional
    public boolean progress(UUID taskId, String nodeId, int percent, String phase) {
        try {
            ExpertTrainingStateMachine.requireProgress(0, percent, phase);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return repository.progress(taskId, nodeId, percent, phase, clock.instant());
    }

    @Transactional(readOnly = true)
    public boolean ownsLease(UUID taskId, String nodeId) {
        return repository.ownsLease(taskId, nodeId, clock.instant());
    }

    @Transactional
    public boolean recordUpload(UUID taskId, String nodeId, String reference, String sha256) {
        return repository.recordUpload(taskId, nodeId, reference, sha256, clock.instant());
    }

    @Transactional
    public boolean complete(UUID taskId, String nodeId, String reference, String sha256,
            JsonNode metrics) {
        return repository.complete(taskId, nodeId, reference, sha256, metrics, clock.instant());
    }

    @Transactional
    public boolean fail(UUID taskId, String nodeId, String code, String message) {
        return repository.fail(taskId, nodeId, code, message, clock.instant());
    }

    @Transactional
    public boolean acknowledgeCancelled(UUID taskId, String nodeId) {
        return repository.acknowledgeCancelled(taskId, nodeId, clock.instant());
    }
}
