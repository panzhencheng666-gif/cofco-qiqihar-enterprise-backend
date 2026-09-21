package com.cofco.qiqihar.graintrade.risk.interfaceadapter;

import com.cofco.qiqihar.graintrade.risk.application.RiskAssessmentDetail;
import com.cofco.qiqihar.graintrade.risk.application.RiskAssessmentSummary;
import com.cofco.qiqihar.graintrade.risk.application.RiskFeedback;
import com.cofco.qiqihar.graintrade.risk.application.RiskWorkbenchService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.riskintelligence.security.RiskRequestIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk/workbench")
public class RiskWorkbenchController {
    private final RiskWorkbenchService service;

    public RiskWorkbenchController(RiskWorkbenchService service){this.service=service;}

    @GetMapping("/assessments")
    ApiResponse<List<RiskAssessmentSummary>> assessments(
            @RequestParam(defaultValue="") String domain,
            @RequestParam(defaultValue="") String level,
            @RequestParam(defaultValue="OPEN") String status,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="100") int limit,
            @RequestHeader("X-Actor") String actor) {
        RiskRequestIdentity.requireActor(actor);
        return new ApiResponse<>(service.assessments(domain,level,status,search,limit));
    }

    @GetMapping("/assessments/{assessmentId}")
    ApiResponse<RiskAssessmentDetail> assessment(
            @PathVariable UUID assessmentId,@RequestHeader("X-Actor") String actor) {
        RiskRequestIdentity.requireActor(actor);
        return new ApiResponse<>(service.assessment(assessmentId));
    }

    @PostMapping("/assessments/{assessmentId}/feedback")
    ApiResponse<RiskFeedback> feedback(
            @PathVariable UUID assessmentId,@RequestBody FeedbackRequest request,
            @RequestHeader("X-Actor") String actor) {
        return new ApiResponse<>(service.submitFeedback(
                assessmentId,request.conclusionCode(),request.reasonCode(),request.dispositionNote(),
                RiskRequestIdentity.requireActor(actor)));
    }

    record FeedbackRequest(String conclusionCode,String reasonCode,String dispositionNote) { }
}
