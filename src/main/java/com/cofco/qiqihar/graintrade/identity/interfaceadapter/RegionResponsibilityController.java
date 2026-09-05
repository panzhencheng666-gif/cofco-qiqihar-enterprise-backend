package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.RegionResponsibility;
import com.cofco.qiqihar.graintrade.identity.application.RegionResponsibilityService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/employees/{subjectId}/region-responsibility")
public class RegionResponsibilityController {
    private final RegionResponsibilityService service;
    public RegionResponsibilityController(RegionResponsibilityService service){this.service=service;}
    @GetMapping ApiResponse<RegionResponsibility.Preview> current(@PathVariable String subjectId){return new ApiResponse<>(service.current(subjectId));}
    @PostMapping("/preview") ApiResponse<RegionResponsibility.Preview> preview(@PathVariable String subjectId,@RequestBody Request request){
        return new ApiResponse<>(service.preview(subjectId,request.regionCodes()));
    }
    @PutMapping ApiResponse<RegionResponsibility.Preview> save(@PathVariable String subjectId,@RequestBody Request request){
        return new ApiResponse<>(service.save(subjectId,request.regionCodes(),request.previewToken(),request.reason()));
    }
    record Request(List<String> regionCodes,String previewToken,String reason) {}
}
