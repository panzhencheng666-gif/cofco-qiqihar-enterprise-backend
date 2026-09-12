package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class HistoricalFormalSampleController {
    private final HistoricalFormalSampleService service;
    public HistoricalFormalSampleController(HistoricalFormalSampleService service) {this.service=service;}
    @GetMapping("/api/v1/formal-sample-points/history")
    ApiResponse<Page> list(@RequestParam String domain,@RequestParam String productCode,
            @RequestParam(required=false) Integer year,@RequestParam(required=false) String regionCode,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="0") int pageNumber,
            @RequestParam(defaultValue="20") int pageSize) {
        var result=service.list(domain,productCode,year,regionCode,keyword,pageNumber,pageSize);
        return new ApiResponse<>(new Page(result.items(),result.pageNumber(),result.pageSize(),result.totalElements(),result.totalPages()));
    }
    record Page(List<HistoricalFormalSample> items,int pageNumber,int pageSize,long totalElements,int totalPages) {}
}
