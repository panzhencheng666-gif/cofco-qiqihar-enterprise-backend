package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.AccessReviewCampaign;
import com.cofco.qiqihar.graintrade.identity.application.AccessReviewDecision;
import com.cofco.qiqihar.graintrade.identity.application.AccessReviewService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity/access-reviews")
public class AccessReviewController {
    private final AccessReviewService service;

    public AccessReviewController(AccessReviewService service) { this.service=service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AccessReviewCampaign> create(@RequestBody CreateRequest request) {
        if(request==null)throw invalid();
        return new ApiResponse<>(service.create(request.name(),request.workUnitCode(),request.dueAt()));
    }

    @GetMapping
    ApiResponse<List<AccessReviewCampaign>> reviews(@org.springframework.web.bind.annotation.RequestParam String workUnitCode) {
        return new ApiResponse<>(service.reviews(workUnitCode));
    }

    @GetMapping("/{reviewId}")
    ApiResponse<AccessReviewCampaign> review(@PathVariable UUID reviewId) {
        return new ApiResponse<>(service.review(reviewId));
    }

    @PostMapping("/{reviewId}/decisions")
    ApiResponse<AccessReviewCampaign> decide(@PathVariable UUID reviewId,@RequestBody DecisionsRequest request) {
        if(request==null)throw invalid();
        return new ApiResponse<>(service.decide(reviewId,request.decisions()));
    }

    record CreateRequest(String name,String workUnitCode,Instant dueAt) {}
    record DecisionsRequest(List<AccessReviewDecision> decisions) {}

    private static ClientRequestException invalid(){return new ClientRequestException(
            "INVALID_ACCESS_REVIEW_REQUEST","Access review request is invalid");}
}
