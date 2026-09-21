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
    private final com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRepository railways;
    private final com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatchService batches;
    private final com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalHierarchyRefresh hierarchy;

    public RegionalAgricultureProfileController(RegionalAgricultureProfileService service,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatchService batches,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalHierarchyRefresh hierarchy,
            com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRepository railways) {
        this.service = service;
        this.railways = railways;
        this.batches = batches;
        this.hierarchy = hierarchy;
    }

    @GetMapping
    ApiResponse<RegionalAgricultureProfileResponse> profile(
            @RequestParam int year,
            @RequestParam String regionCode) {
        var profile = service.profile(year, regionCode);
        var batch = "PREFECTURE".equals(profile.administrativeLevel())
                ? batches.load(profile.regionCode(), year) : null;
        return new ApiResponse<>(RegionalAgricultureProfileResponse.from(
                profile, batch, hierarchy.status(profile.regionCode(), year), railways.find(profile.regionCode())));
    }
}
