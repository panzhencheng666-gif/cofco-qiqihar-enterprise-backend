package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfileService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/overview/regional-agriculture-profile")
public class RegionalAgricultureProfileController {
    private final RegionalAgricultureProfileService service;
    private final com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatchService batches;

    public RegionalAgricultureProfileController(RegionalAgricultureProfileService service,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatchService batches) {
        this.service = service;
        this.batches = batches;
    }

    @GetMapping
    ApiResponse<RegionalAgricultureProfileResponse> profile(
            @RequestParam int year,
            @RequestParam String regionCode) {
        var profile = service.profile(year, regionCode);
        return new ApiResponse<>(RegionalAgricultureProfileResponse.from(profile, batches.load(regionCode.substring(0,4) + "00",year)));
    }
}
