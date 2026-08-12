package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewIndicator;
import com.cofco.qiqihar.graintrade.overview.application.OverviewDashboard;
import com.cofco.qiqihar.graintrade.overview.application.OverviewMapScope;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRegion;
import com.cofco.qiqihar.graintrade.overview.application.OverviewOptions;
import com.cofco.qiqihar.graintrade.overview.application.OverviewService;
import com.cofco.qiqihar.graintrade.overview.application.AnnualComparisonView;
import com.cofco.qiqihar.graintrade.overview.application.AnnualComparisonDefinition;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {
    private final OverviewService service;
    public OverviewController(OverviewService service) { this.service = service; }

    @GetMapping("/api/v1/overview/options")
    ApiResponse<OverviewOptions> options() { return new ApiResponse<>(service.options()); }

    @GetMapping("/api/v1/overview/map-scope")
    ApiResponse<OverviewMapScope> mapScope() { return new ApiResponse<>(service.mapScope()); }

    @GetMapping("/api/v1/overview/regions")
    ApiResponse<List<OverviewRegion>> regions(@RequestParam(required = false) String parentCode,
            @RequestParam String productCode, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String periodCode) {
        return new ApiResponse<>(service.regions(parentCode, productCode, year, periodCode));
    }

    @GetMapping("/api/v1/overview/locations")
    ApiResponse<List<OverviewRegion>> locations(@RequestParam(required = false) String ancestorCode,
            @RequestParam String level,
            @RequestParam String productCode, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String periodCode) {
        return new ApiResponse<>(service.locations(ancestorCode, level, productCode, year, periodCode));
    }

    @GetMapping("/api/v1/overview/indicators")
    OverviewContractResponse<List<OverviewIndicator>> indicators(@RequestParam String productCode, @RequestParam String regionCode,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) String marketingYear) {
        return new OverviewContractResponse<>(service.indicators(productCode, regionCode, year, periodCode));
    }

    @GetMapping("/api/v1/overview/annual-comparisons")
    ApiResponse<AnnualComparisonView> annualComparison(@RequestParam String productCode,
            @RequestParam(required = false) String cultivarCode, @RequestParam String regionCode,
            @RequestParam(required = false) Integer surveyYear,
            @RequestParam(required = false) String periodCode, @RequestParam String indicatorCode) {
        return new ApiResponse<>(service.annualComparison(
                productCode, cultivarCode, regionCode, surveyYear, periodCode, indicatorCode));
    }

    @GetMapping("/api/v1/overview/annual-comparison-definitions")
    ApiResponse<List<AnnualComparisonDefinition>> annualComparisonDefinitions(
            @RequestParam String sourceDomain,
            @RequestParam String productCode) {
        return new ApiResponse<>(service.annualComparisonDefinitions(sourceDomain, productCode));
    }

    @GetMapping("/api/v1/overview/dashboard")
    OverviewContractResponse<OverviewDashboard> dashboard(
            @RequestParam String productCode,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String marketingYear) {
        return new OverviewContractResponse<>(service.dashboard(productCode, year, periodCode, regionCode));
    }
}
