package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointDetail;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointIcon;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointList;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointService;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointSnapshot;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

@RestController
public class OverviewSamplePointController {
    private final OverviewSamplePointService service;

    public OverviewSamplePointController(OverviewSamplePointService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/overview/sample-point-aggregates")
    ApiResponse<List<OverviewSamplePointAggregate>> aggregates(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String parentCode) {
        return new ApiResponse<>(service.aggregates(year, productCode, parentCode));
    }

    @GetMapping("/api/v1/overview/sample-points")
    ApiResponse<OverviewSamplePointList> list(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String productCode,
            @RequestParam String regionCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String query) {
        return new ApiResponse<>(service.list(year, productCode, regionCode, categoryCode, typeCode, query));
    }

    @GetMapping("/api/v1/overview/sample-points/export")
    ResponseEntity<byte[]> export(
            @RequestParam Integer year,
            @RequestParam(required = false) String regionCode) {
        OverviewSamplePointService.ExportFile file = service.export(year, regionCode);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @GetMapping("/api/v1/overview/sample-point-icons")
    ApiResponse<List<OverviewSamplePointIcon>> icons(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String productCode,
            @RequestParam String regionCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String query) {
        return new ApiResponse<>(service.icons(year, productCode, regionCode, categoryCode, typeCode, query));
    }

    @GetMapping("/api/v1/overview/sample-point-snapshot")
    ApiResponse<OverviewSamplePointSnapshot> snapshot(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String productCode,
            @RequestParam String regionCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String query) {
        return new ApiResponse<>(service.snapshot(
                year, productCode, regionCode, categoryCode, typeCode, query));
    }

    @GetMapping("/api/v1/overview/sample-points/{samplePointId}")
    ApiResponse<OverviewSamplePointDetail> detail(
            @PathVariable UUID samplePointId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String productCode,
            @RequestParam String regionCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String typeCode) {
        return new ApiResponse<>(service.detail(
                year, productCode, samplePointId, regionCode, categoryCode, typeCode));
    }
}
