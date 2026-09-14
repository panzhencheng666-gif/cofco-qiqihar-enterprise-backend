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

    public RegionalAgricultureProfileController(RegionalAgricultureProfileService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<RegionalAgricultureProfileResponse> profile(
            @RequestParam int year,
            @RequestParam String regionCode) {
        return new ApiResponse<>(RegionalAgricultureProfileResponse.from(service.profile(year, regionCode)));
    }
}
