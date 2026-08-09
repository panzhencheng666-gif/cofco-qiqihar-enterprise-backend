package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportService;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports/logistics")
public class LogisticsImportController {
    private final LogisticsImportService service;

    public LogisticsImportController(LogisticsImportService service) { this.service = service; }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam String productCode) {
        byte[] bytes = BusinessImportWorkbook.create(service.template(productCode));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("物流-" + productCode + "-批量导入模板.xlsx", StandardCharsets.UTF_8)
                        .build().toString()).body(bytes);
    }

    @PostMapping
    ResponseEntity<ApiResponse<ImportJobView>> importFile(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String productCode,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return acceptedOrCreated(service.importFile(idempotencyKey, productCode, file.getOriginalFilename(),
                file.getContentType(), file.getBytes()));
    }

    @GetMapping("/{importJobId}")
    ApiResponse<ImportJobView> status(@PathVariable UUID importJobId) {
        return new ApiResponse<>(service.status(importJobId));
    }

    @GetMapping("/{importJobId}/errors")
    ResponseEntity<byte[]> errors(@PathVariable UUID importJobId) {
        ImportErrorFile file = service.errors(importJobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    @PostMapping("/{importJobId}/retries")
    ResponseEntity<ApiResponse<ImportJobView>> retry(@PathVariable UUID importJobId) {
        ImportJobView job = service.retry(importJobId);
        boolean pending = job.statusCode().equals("QUEUED") || job.statusCode().equals("PROCESSING");
        return ResponseEntity.status(pending ? 202 : 200).body(new ApiResponse<>(job));
    }

    private static ResponseEntity<ApiResponse<ImportJobView>> acceptedOrCreated(ImportJobView job) {
        boolean pending = job.statusCode().equals("QUEUED") || job.statusCode().equals("PROCESSING");
        return ResponseEntity.status(pending ? 202 : 201).body(new ApiResponse<>(job));
    }
}
