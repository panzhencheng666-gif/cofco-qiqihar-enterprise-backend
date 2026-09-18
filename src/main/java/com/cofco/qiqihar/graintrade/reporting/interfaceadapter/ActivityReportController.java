package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import com.cofco.qiqihar.graintrade.reporting.application.ActivityReport;
import com.cofco.qiqihar.graintrade.reporting.application.ActivityReportExport;
import com.cofco.qiqihar.graintrade.reporting.application.ActivityReportService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity-reports")
public class ActivityReportController {
    private final ActivityReportService service;

    public ActivityReportController(ActivityReportService service) {
        this.service = service;
    }

    @GetMapping("/personal")
    ApiResponse<ActivityReport> personal(@RequestParam(defaultValue = "7") int days) {
        return new ApiResponse<>(service.personal(days));
    }

    @GetMapping("/system")
    ApiResponse<ActivityReport> system(@RequestParam(defaultValue = "7") int days) {
        return new ApiResponse<>(service.system(days));
    }

    @PostMapping("/system/exports")
    ApiResponse<ActivityReportExport> export(@RequestParam(defaultValue = "7") int days) {
        return new ApiResponse<>(service.exportSystem(days));
    }

    @GetMapping("/system/exports/{exportId}/content")
    ResponseEntity<byte[]> download(@PathVariable String exportId) {
        ActivityReportExport.Content content = service.download(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }
}
