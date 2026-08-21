package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.SampleIdentityReviewService;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewView.DecisionView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewView.ReviewItem;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sample-point-identities/reviews")
public class SampleIdentityReviewController {
    private final SampleIdentityReviewService service;

    public SampleIdentityReviewController(SampleIdentityReviewService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ReviewItem>> pendingReviews() {
        return new ApiResponse<>(service.pendingReviews());
    }

    @PostMapping("/{draftId}/decisions")
    ApiResponse<DecisionView> decide(
            @PathVariable UUID draftId, @RequestBody DecisionRequest request) {
        return new ApiResponse<>(service.reviewDraft(
                draftId, request.decision(), request.targetSamplePointId(),
                request.expectedVersion(), request.reason()));
    }

    record DecisionRequest(
            String decision, UUID targetSamplePointId, int expectedVersion, String reason) {}
}
