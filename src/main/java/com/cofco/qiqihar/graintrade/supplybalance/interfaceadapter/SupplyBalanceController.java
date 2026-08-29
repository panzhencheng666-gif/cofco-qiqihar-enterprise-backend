package com.cofco.qiqihar.graintrade.supplybalance.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceRepository;
import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/supply-balances")
public class SupplyBalanceController {
    private final SupplyBalanceService service;

    public SupplyBalanceController(SupplyBalanceService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<SupplyBalanceResponse> find(
            @RequestParam String regionCode,
            @RequestParam int surveyYear,
            @RequestParam String productCode) {
        return new ApiResponse<>(SupplyBalanceResponse.from(
                service.find(regionCode, surveyYear, productCode)));
    }

    @PutMapping("/{regionCode}/{surveyYear}/{productCode}")
    ApiResponse<SupplyBalanceResponse> upsert(
            @PathVariable String regionCode,
            @PathVariable int surveyYear,
            @PathVariable String productCode,
            @RequestBody SaveRequest request) {
        return new ApiResponse<>(SupplyBalanceResponse.from(service.upsert(
                regionCode, surveyYear, productCode, request.manualValues(), request.notes(),
                request.version())));
    }

    @GetMapping("/{regionCode}/{surveyYear}/{productCode}/history")
    ApiResponse<List<SupplyBalanceRepository.HistoryEntry>> history(
            @PathVariable String regionCode,
            @PathVariable int surveyYear,
            @PathVariable String productCode) {
        return new ApiResponse<>(service.history(regionCode, surveyYear, productCode));
    }

    record SaveRequest(long version, Map<String, BigDecimal> manualValues, Map<String, String> notes) {}
}
