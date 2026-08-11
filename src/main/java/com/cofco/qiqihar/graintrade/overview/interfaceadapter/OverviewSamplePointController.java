package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointDetail;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointIcon;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointList;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewSamplePointController {
    private final OverviewSamplePointService service;

    public OverviewSamplePointController(OverviewSamplePointService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/overview/sample-point-aggregates")
    ApiResponse<List<OverviewSamplePointAggregate>> aggregates(
            @RequestParam(required = false) String parentCode) {
        return new ApiResponse<>(service.aggregates(parentCode));
    }

    @GetMapping("/api/v1/overview/sample-points")
    ApiResponse<OverviewSamplePointList> list(
            @RequestParam String regionCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String query) {
        return new ApiResponse<>(service.list(regionCode, categoryCode, typeCode, query));
    }

    @GetMapping("/api/v1/overview/sample-point-icons")
    ApiResponse<List<OverviewSamplePointIcon>> icons(
            @RequestParam String regionCode,
            @RequestParam String categoryCode,
            @RequestParam(required = false) String typeCode) {
        return new ApiResponse<>(service.icons(regionCode, categoryCode, typeCode));
    }

    @GetMapping("/api/v1/overview/sample-points/{samplePointId}")
    ApiResponse<OverviewSamplePointDetail> detail(
            @PathVariable UUID samplePointId,
            @RequestParam String regionCode) {
        return new ApiResponse<>(service.detail(samplePointId, regionCode));
    }
}
