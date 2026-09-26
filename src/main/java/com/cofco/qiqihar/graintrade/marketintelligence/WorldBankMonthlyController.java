package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorldBankMonthlyController {
    private final WorldBankMonthlyAnalysis analysis;

    public WorldBankMonthlyController(WorldBankMonthlyAnalysis analysis) { this.analysis = analysis; }

    @GetMapping("/api/v1/market-intelligence/world-bank/monthly")
    public ApiResponse<WorldBankMonthlyAnalysis.Snapshot> monthly(
            @RequestParam String series, @RequestParam(defaultValue = "24") int months) {
        return new ApiResponse<>(analysis.snapshot(series, months));
    }

    @GetMapping("/api/v1/market-intelligence/world-bank/overview")
    public ApiResponse<List<WorldBankMonthlyAnalysis.Snapshot>> overview() {
        return new ApiResponse<>(Arrays.stream(WorldBankMonthlySeries.values())
                .map(series -> analysis.snapshot(series.code, 13)).toList());
    }
}
