package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewMapRevisionService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewMapRevisionController {
    private final OverviewMapRevisionService service;
    public OverviewMapRevisionController(OverviewMapRevisionService service) { this.service = service; }

    @GetMapping("/api/v1/overview/map-revision")
    ResponseEntity<ApiResponse<String>> revision() {
        String revision = service.currentRevision();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(revision));
    }
}
