package com.cofco.qiqihar.riskintelligence.experttraining;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/risk/expert-training")
public class ExpertTrainingController {
    private final ExpertTrainingService service;

    public ExpertTrainingController(ExpertTrainingService service) { this.service = service; }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ExpertTrainingRepository.DatasetSnapshot> createDataset(
            @RequestBody JsonNode body, HttpServletRequest request) {
        return new ApiResponse<>(service.createDataset(body, RiskBusinessSession.require(request)));
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ExpertTrainingRepository.TaskView> createTask(
            @RequestBody JsonNode body, HttpServletRequest request) {
        return new ApiResponse<>(service.createTask(body, RiskBusinessSession.require(request)));
    }

    @GetMapping("/overview")
    ApiResponse<ExpertTrainingRepository.Overview> overview(HttpServletRequest request) {
        return new ApiResponse<>(service.overview(RiskBusinessSession.require(request)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    ApiResponse<ExpertTrainingRepository.TaskView> cancel(
            @PathVariable UUID taskId, HttpServletRequest request) {
        return new ApiResponse<>(service.cancel(taskId, RiskBusinessSession.require(request)));
    }
}
