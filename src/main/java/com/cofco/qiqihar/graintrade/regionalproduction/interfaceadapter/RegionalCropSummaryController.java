package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropSummaryService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/overview/regional-crop-summary")
public class RegionalCropSummaryController {
    private final RegionalCropSummaryService service;

    public RegionalCropSummaryController(RegionalCropSummaryService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<RegionalCropSummaryResponse> summarize(
            @RequestParam int year,
            @RequestParam String productCode,
            @RequestParam String regionCode) {
        return new ApiResponse<>(RegionalCropSummaryResponse.from(
                service.summarize(year, productCode, regionCode)));
    }
}
