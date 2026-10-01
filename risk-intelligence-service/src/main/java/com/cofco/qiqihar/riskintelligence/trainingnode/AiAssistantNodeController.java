package com.cofco.qiqihar.riskintelligence.trainingnode;

import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantNodeService;
import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
class AiAssistantNodeController {
    private final AiAssistantNodeService service;
    private final String token;

    AiAssistantNodeController(AiAssistantNodeService service,
            @Value("${qiqihar.risk.training.remote-node.token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping("/assistant-claims")
    ResponseEntity<AiAssistantRepository.NodeClaim> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId) {
        authorize(authorization);
        return service.claim(TrainingNodeCredential.requireNodeId(nodeId)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/assistant-requests/{requestId}/completion")
    ResponseEntity<Void> complete(@PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestBody JsonNode response) {
        authorize(authorization);
        if (!service.complete(requestId, TrainingNodeCredential.requireNodeId(nodeId), response)) {
            throw conflict();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assistant-requests/{requestId}/failure")
    ResponseEntity<Void> fail(@PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Risk-Training-Node-Id", required = false) String nodeId,
            @RequestBody Failure failure) {
        authorize(authorization);
        if (!service.fail(requestId, TrainingNodeCredential.requireNodeId(nodeId), failure.code())) {
            throw conflict();
        }
        return ResponseEntity.noContent().build();
    }

    private void authorize(String authorization) {
        if (!TrainingNodeCredential.authorized(authorization, token)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "训练节点凭据无效");
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "AI助手短租约已失效或不属于当前节点");
    }

    record Failure(String code) { }
}
