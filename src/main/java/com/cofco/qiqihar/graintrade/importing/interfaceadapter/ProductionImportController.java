package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports/production")
public class ProductionImportController {
    private final ProductionImportService service;
    public ProductionImportController(ProductionImportService service) { this.service = service; }

    @GetMapping("/template")
    ResponseEntity<byte[]> template() { return csv("production-import-template.csv", service.template().getBytes(StandardCharsets.UTF_8)); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ApiResponse<ImportJobView> upload(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return new ApiResponse<>(service.importFile(idempotencyKey,
                file == null ? null : file.getOriginalFilename(), file == null ? null : file.getContentType(),
                file == null ? null : file.getBytes()));
    }

    @PostMapping("/{importJobId}/retries")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ApiResponse<ImportJobView> retry(@PathVariable UUID importJobId) { return new ApiResponse<>(service.retry(importJobId)); }

    @GetMapping("/{importJobId}/errors")
    ResponseEntity<byte[]> errors(@PathVariable UUID importJobId) {
        ImportErrorFile file = service.errors(importJobId);
        return csv(file.filename(), file.bytes());
    }

    private static ResponseEntity<byte[]> csv(String name, byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
}
