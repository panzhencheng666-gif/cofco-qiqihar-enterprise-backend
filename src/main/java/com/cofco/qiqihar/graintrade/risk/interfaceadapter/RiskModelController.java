package com.cofco.qiqihar.graintrade.risk.interfaceadapter;

import com.cofco.qiqihar.graintrade.risk.application.RiskModelOperationsService;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelOverview;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRequest;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk/models")
public class RiskModelController {
    private final RiskModelOperationsService service;

    public RiskModelController(RiskModelOperationsService service) {
        this.service=service;
    }

    @GetMapping("/overview")
    ApiResponse<RiskModelOverview> overview() {
        return new ApiResponse<>(service.overview());
    }

    @PostMapping("/{modelId}/training-requests")
    ApiResponse<RiskTrainingRequest> requestTraining(@PathVariable UUID modelId) {
        return new ApiResponse<>(service.requestTraining(modelId));
    }
}
