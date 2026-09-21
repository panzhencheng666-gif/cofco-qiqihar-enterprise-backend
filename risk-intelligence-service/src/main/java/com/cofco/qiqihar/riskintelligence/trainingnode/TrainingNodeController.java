package com.cofco.qiqihar.riskintelligence.trainingnode;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/risk-intelligence/training-node")
class TrainingNodeController {
    private final RemoteTrainingCoordinator coordinator;
    private final RemoteScoringCoordinator scoring;
    private final RemoteArtifactStore artifacts;
    private final String token;

    TrainingNodeController(RemoteTrainingCoordinator coordinator,RemoteScoringCoordinator scoring,
            RemoteArtifactStore artifacts,
            @Value("${qiqihar.risk.training.remote-node.token:}") String token) {
        this.coordinator=coordinator;
        this.scoring=scoring;
        this.artifacts=artifacts;
        this.token=token;
    }

    @PostMapping("/scoring-claims")
    ResponseEntity<RemoteScoringJob> claimScoring(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId) {
        authorize(authorization);
        String validated=TrainingNodeCredential.requireNodeId(nodeId);
        return scoring.claimNext(validated).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/scoring-completion")
    ResponseEntity<Void> completeScoring(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId,
            @RequestBody RemoteScoringCoordinator.RemoteScoreCompletion body) {
        authorize(authorization);
        if (!scoring.complete(TrainingNodeCredential.requireNodeId(nodeId),body)) throw conflict();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims")
    ResponseEntity<RemoteTrainingJob> claim(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId) {
        authorize(authorization);
        String validated=TrainingNodeCredential.requireNodeId(nodeId);
        return coordinator.claimNext(validated).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/executions/{executionId}/heartbeat")
    ResponseEntity<Void> heartbeat(
            @PathVariable UUID executionId,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId,
            @RequestBody Heartbeat body) {
        authorize(authorization);
        if (!coordinator.renewLease(TrainingNodeCredential.requireNodeId(nodeId),
                executionId,body.trainingRunId())) throw conflict();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/executions/{executionId}/completion")
    ResponseEntity<Void> complete(
            @PathVariable UUID executionId,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId,
            @RequestBody Completion body) {
        authorize(authorization);
        RemoteTrainingArtifact artifact=new RemoteTrainingArtifact(
                body.artifactReference(),body.artifactSha256(),body.metrics(),body.thresholds());
        if (!artifacts.matches(body.trainingRunId(),body.artifactReference(),body.artifactSha256())) {
            throw conflict();
        }
        if (!coordinator.complete(TrainingNodeCredential.requireNodeId(nodeId),executionId,
                body.trainingRunId(),body.modelVersion(),artifact)) throw conflict();
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value="/artifacts",consumes="application/octet-stream")
    ResponseEntity<StoredRemoteArtifact> uploadArtifact(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Risk-Training-Node-Id",required=false) String nodeId,
            @RequestHeader("X-Risk-Training-Execution-Id") UUID executionId,
            @RequestHeader("X-Risk-Training-Run-Id") UUID trainingRunId,
            @RequestHeader("X-Risk-Artifact-Sha256") String bundleSha256,
            @RequestHeader("X-Risk-Artifact-Content-Sha256") String contentSha256,
            HttpServletRequest request) {
        authorize(authorization);
        String validatedNodeId=TrainingNodeCredential.requireNodeId(nodeId);
        if (!coordinator.ownsLease(validatedNodeId,executionId,trainingRunId)) throw conflict();
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(artifacts.store(trainingRunId,
                    bundleSha256,contentSha256,request.getInputStream(),request.getContentLengthLong()));
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"训练工件包读取失败",exception);
        }
    }

    private void authorize(String authorization) {
        if (!TrainingNodeCredential.authorized(authorization,token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"训练节点凭据无效");
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,"训练租约已失效或不属于当前节点");
    }

    record Heartbeat(UUID trainingRunId) { }
    record Completion(UUID trainingRunId,int modelVersion,String artifactReference,
            String artifactSha256,java.util.Map<String,Object> metrics,
            java.util.Map<String,Object> thresholds) { }
}
