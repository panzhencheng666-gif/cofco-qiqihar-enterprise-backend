package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk/assistant/questions")
public class AiAssistantController {
    private final AiAssistantService service;

    public AiAssistantController(AiAssistantService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<AiAssistantRepository.RequestView> ask(
            @RequestBody Question body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return new ApiResponse<>(service.ask(
                body.question(), idempotencyKey, RiskBusinessSession.require(request)));
    }

    @GetMapping("/{requestId}")
    ApiResponse<AiAssistantRepository.RequestView> get(
            @PathVariable UUID requestId, HttpServletRequest request) {
        return new ApiResponse<>(service.get(requestId, RiskBusinessSession.require(request)));
    }

    record Question(String question) { }
}
