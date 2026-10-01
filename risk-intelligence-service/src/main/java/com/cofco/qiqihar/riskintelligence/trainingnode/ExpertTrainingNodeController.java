package com.cofco.qiqihar.riskintelligence.trainingnode;

import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingNodeService;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/risk-intelligence/training-node")
class ExpertTrainingNodeController {
    private static final Set<String> FAILURE_CODES = Set.of(
            "DATASET_PREPARATION_FAILED", "MODEL_LOAD_FAILED", "LOCAL_TRAINING_FAILED",
            "OUT_OF_MEMORY", "PACKAGING_FAILED", "UPLOAD_FAILED", "NODE_SHUTDOWN");
    private final ExpertTrainingNodeService service;
    private final RemoteArtifactStore artifacts;
    private final String token;

    ExpertTrainingNodeController(ExpertTrainingNodeService service, RemoteArtifactStore artifacts,
            @Value("${qiqihar.risk.training.remote-node.token:}") String token) {
        this.service = service;
        this.artifacts = artifacts;
        this.token = token;
    }

    @PostMapping("/expert-claims")
    ResponseEntity<ExpertTrainingRepository.Claim> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId) {
        authorize(authorization);
        return service.claim(TrainingNodeCredential.requireNodeId(nodeId)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/expert-tasks/{taskId}/heartbeat")
    ExpertTrainingRepository.Heartbeat heartbeat(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId) {
        authorize(authorization);
        return service.heartbeat(taskId, TrainingNodeCredential.requireNodeId(nodeId))
                .orElseThrow(ExpertTrainingNodeController::conflict);
    }

    @PostMapping("/expert-tasks/{taskId}/progress")
    ResponseEntity<Void> progress(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestBody Progress body) {
        authorize(authorization);
        if (!service.progress(taskId, TrainingNodeCredential.requireNodeId(nodeId),
                body.percent(), body.phase())) throw conflict();
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/expert-tasks/{taskId}/artifacts", consumes = "application/octet-stream")
    ResponseEntity<StoredRemoteArtifact> artifact(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestHeader("X-Risk-Artifact-Sha256") String bundleSha256,
            @RequestHeader("X-Risk-Artifact-Content-Sha256") String contentSha256,
            HttpServletRequest request) {
        authorize(authorization);
        String owner = TrainingNodeCredential.requireNodeId(nodeId);
        if (!service.ownsLease(taskId, owner)) throw conflict();
        try {
            StoredRemoteArtifact stored = artifacts.store(taskId, bundleSha256, contentSha256,
                    request.getInputStream(), request.getContentLengthLong());
            if (!service.recordUpload(taskId, owner, stored.artifactReference(), stored.bundleSha256())) {
                throw conflict();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "训练工件包读取失败", exception);
        }
    }

    @PostMapping("/expert-tasks/{taskId}/completion")
    ResponseEntity<Void> complete(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestBody Completion body) {
        authorize(authorization);
        String owner = TrainingNodeCredential.requireNodeId(nodeId);
        if (!artifacts.matches(taskId, body.artifactReference(), body.artifactSha256())
                || !boundedObject(body.metrics(), 4096)
                || !service.complete(taskId, owner, body.artifactReference(), body.artifactSha256(), body.metrics())) {
            throw conflict();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/expert-tasks/{taskId}/failure")
    ResponseEntity<Void> fail(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestBody Failure body) {
        authorize(authorization);
        if (!FAILURE_CODES.contains(body.code()) || body.message() == null || body.message().isBlank()
                || body.message().length() > 500 || body.message().chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "训练失败信息不符合约束");
        }
        if (!service.fail(taskId, TrainingNodeCredential.requireNodeId(nodeId), body.code(), body.message())) {
            throw conflict();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/expert-tasks/{taskId}/cancelled")
    ResponseEntity<Void> cancelled(@PathVariable UUID taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId) {
        authorize(authorization);
        if (!service.acknowledgeCancelled(taskId, TrainingNodeCredential.requireNodeId(nodeId))) {
            throw conflict();
        }
        return ResponseEntity.noContent().build();
    }

    private void authorize(String authorization) {
        if (!TrainingNodeCredential.authorized(authorization, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "训练节点凭据无效");
        }
    }

    private static boolean boundedObject(JsonNode value, int maximumBytes) {
        return value != null && value.isObject()
                && value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maximumBytes;
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "专家训练租约已失效或不属于当前节点");
    }

    record Progress(int percent, String phase) { }
    record Completion(String artifactReference, String artifactSha256, JsonNode metrics) { }
    record Failure(String code, String message) { }
}
