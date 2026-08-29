package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropAnnualStatService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/production/regional-annual-stats")
public class RegionalCropAnnualStatController {
    private final RegionalCropAnnualStatService service;

    public RegionalCropAnnualStatController(RegionalCropAnnualStatService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<RegionalCropAnnualStatResponse>> findAll(
            @RequestParam int year,
            @RequestParam String productCode,
            @RequestParam String prefectureCode) {
        return new ApiResponse<>(service.findAll(year, productCode, prefectureCode).stream()
                .map(RegionalCropAnnualStatResponse::from).toList());
    }

    @PutMapping("/{regionCode}")
    ApiResponse<RegionalCropAnnualStatResponse> upsert(
            @PathVariable String regionCode,
            @RequestBody RegionalCropAnnualStatRequest request) {
        return new ApiResponse<>(RegionalCropAnnualStatResponse.from(service.upsert(
                regionCode, request.dataYear(), request.productCode(), request.plantedAreaMu(),
                request.yieldPerMuKg(), request.expectedVersion())));
    }
}
