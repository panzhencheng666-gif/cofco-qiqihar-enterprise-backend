package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import com.cofco.qiqihar.graintrade.reporting.application.ReportExportView;
import com.cofco.qiqihar.graintrade.reporting.application.ReportParameterOptionsView;
import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewCommand;
import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewView;
import com.cofco.qiqihar.graintrade.reporting.application.ReportPublicationView;
import com.cofco.qiqihar.graintrade.reporting.application.ReportingService;
import com.cofco.qiqihar.graintrade.reporting.domain.ReportExportContent;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {
    private final ReportingService service;
    public ReportingController(ReportingService service) { this.service = service; }

    @GetMapping("/parameter-options")
    ApiResponse<ReportParameterOptionsView> options() { return new ApiResponse<>(service.options()); }

    @PostMapping("/previews")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ReportPreviewView> preview(@RequestBody PreviewRequest request) {
        return new ApiResponse<>(service.preview(request.command()));
    }

    @PostMapping("/previews/{previewId}/exports")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ReportExportView> export(@PathVariable String previewId, @RequestBody ExportRequest request) {
        return new ApiResponse<>(service.export(previewId, request.formatCode()));
    }

    @GetMapping("/exports/{exportTaskId}/content")
    ResponseEntity<byte[]> download(@PathVariable String exportTaskId) {
        ReportExportContent export = service.download(exportTaskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .body(export.bytes());
    }

    @PostMapping("/previews/{previewId}/publications")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ReportPublicationView> publish(@PathVariable String previewId, @RequestBody PublicationRequest request) {
        if (request == null || request.exportTaskId() == null || request.expectedVersion() == null) throw invalid();
        return new ApiResponse<>(service.publish(previewId, request.exportTaskId(), request.expectedVersion()));
    }

    record PreviewRequest(String definitionCode, String productCode, String cultivarCode,
            String regionLevel, String regionCode, String periodCode) {
        ReportPreviewCommand command() { return new ReportPreviewCommand(definitionCode, productCode, cultivarCode, regionLevel, regionCode, periodCode); }
    }
    record ExportRequest(String formatCode) {}
    record PublicationRequest(String exportTaskId, Long expectedVersion) {}
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_REPORT_REQUEST", "Report request is invalid"); }
}
