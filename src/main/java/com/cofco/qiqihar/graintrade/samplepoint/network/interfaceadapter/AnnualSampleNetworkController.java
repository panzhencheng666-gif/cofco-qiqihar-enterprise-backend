package com.cofco.qiqihar.graintrade.samplepoint.network.interfaceadapter;

import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkService;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.AnnualSampleNetworkView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.DesignSamplePointView;
import com.cofco.qiqihar.graintrade.samplepoint.network.application.SampleNetworkComparisonView;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sample-networks")
public class AnnualSampleNetworkController {
    private final AnnualSampleNetworkService service;

    public AnnualSampleNetworkController(AnnualSampleNetworkService service) {
        this.service = service;
    }

    @GetMapping("/design-points")
    ApiResponse<List<DesignSamplePointView>> designPoints(
            @RequestParam(required = false) String regionCode) {
        return new ApiResponse<>(service.designPoints(regionCode));
    }

    @GetMapping("/{year}")
    ApiResponse<AnnualSampleNetworkView> network(@PathVariable int year) {
        return new ApiResponse<>(service.find(year));
    }

    @GetMapping("/{year}/comparison")
    ApiResponse<SampleNetworkComparisonView> comparison(
            @PathVariable int year,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String productCode) {
        return new ApiResponse<>(service.comparison(year, regionCode, productCode));
    }

    @PostMapping("/{year}")
    ResponseEntity<ApiResponse<AnnualSampleNetworkView>> create(
            @PathVariable int year, @RequestBody CreateRequest request) {
        return ResponseEntity.status(201)
                .body(new ApiResponse<>(service.create(year, request.carriedFromYear())));
    }

    @PutMapping("/{year}/members/{samplePointId}")
    ApiResponse<AnnualSampleNetworkView> decideMembership(
            @PathVariable int year,
            @PathVariable UUID samplePointId,
            @RequestBody MemberDecisionRequest request) {
        return new ApiResponse<>(service.decideMembership(
                year, samplePointId, request.designVillageRegionCode(), request.relationType(),
                request.evidenceReference(), request.statusCode(), request.sourceCode(),
                request.reason(), request.version()));
    }

    @PostMapping("/{year}/submit")
    ApiResponse<AnnualSampleNetworkView> submit(
            @PathVariable int year, @RequestBody VersionRequest request) {
        return new ApiResponse<>(service.submit(year, request.version()));
    }

    @PostMapping("/{year}/review")
    ApiResponse<AnnualSampleNetworkView> review(
            @PathVariable int year, @RequestBody ReviewRequest request) {
        return new ApiResponse<>(service.review(
                year, request.version(), request.decision(), request.reason()));
    }

    record CreateRequest(Integer carriedFromYear) {}

    record MemberDecisionRequest(
            String designVillageRegionCode,
            String relationType,
            String evidenceReference,
            String statusCode,
            String sourceCode,
            String reason,
            long version) {}

    record VersionRequest(long version) {}

    record ReviewRequest(long version, String decision, String reason) {}
}
