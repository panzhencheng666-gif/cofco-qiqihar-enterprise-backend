package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FaoFoodPriceController {
    private final FaoFoodPriceAnalysis analysis;
    public FaoFoodPriceController(FaoFoodPriceAnalysis analysis) { this.analysis = analysis; }

    @GetMapping("/api/v1/market-intelligence/fao-food-price/monthly")
    public ApiResponse<FaoFoodPriceAnalysis.Snapshot> monthly(
            @RequestParam String series, @RequestParam(defaultValue = "24") int months) {
        return new ApiResponse<>(analysis.snapshot(series, months));
    }

    @GetMapping("/api/v1/market-intelligence/fao-food-price/overview")
    public ApiResponse<List<FaoFoodPriceAnalysis.Snapshot>> overview() {
        return new ApiResponse<>(Arrays.stream(FaoFoodPriceSeries.values())
                .map(series -> analysis.snapshot(series.code, 13)).toList());
    }
}
