package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewIndicator;
import com.cofco.qiqihar.graintrade.overview.application.OverviewRegion;
import com.cofco.qiqihar.graintrade.overview.application.OverviewService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {
    private final OverviewService service;
    public OverviewController(OverviewService service) { this.service = service; }

    @GetMapping("/api/v1/overview/regions")
    ApiResponse<List<OverviewRegion>> regions(@RequestParam(required = false) String parentCode,
            @RequestParam String productCode, @RequestParam String periodCode) {
        return new ApiResponse<>(service.regions(parentCode, productCode, periodCode));
    }

    @GetMapping("/api/v1/overview/indicators")
    ApiResponse<List<OverviewIndicator>> indicators(@RequestParam String productCode, @RequestParam String regionCode,
            @RequestParam String periodCode, @RequestParam String marketingYear) {
        return new ApiResponse<>(service.indicators(productCode, regionCode, periodCode, marketingYear));
    }
}
