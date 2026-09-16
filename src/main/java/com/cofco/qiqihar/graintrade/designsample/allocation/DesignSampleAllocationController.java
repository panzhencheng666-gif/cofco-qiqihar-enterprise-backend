package com.cofco.qiqihar.graintrade.designsample.allocation;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/design-sample-allocation")
public class DesignSampleAllocationController {
    private final DesignSampleAllocationService service;private final AccessControl access;
    public DesignSampleAllocationController(DesignSampleAllocationService service,AccessControl access){this.service=service;this.access=access;}
    @GetMapping("/preflight") ApiResponse<DesignSampleAllocationPreflight> preflight(){access.requireAdministrator();return new ApiResponse<>(service.preflight());}
    @PostMapping("/apply") ApiResponse<DesignSampleAllocationService.ApplyBatch> apply(){String actor=access.requireAdministrator().subjectId();return new ApiResponse<>(service.apply(actor));}
}
