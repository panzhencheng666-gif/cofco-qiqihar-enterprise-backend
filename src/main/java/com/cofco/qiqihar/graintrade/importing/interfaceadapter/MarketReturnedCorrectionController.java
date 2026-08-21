package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.importing.application.MarketReturnedCorrectionService;
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
@RequestMapping("/api/v1/imports/market/returned-corrections")
public class MarketReturnedCorrectionController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final MarketReturnedCorrectionService service;

    public MarketReturnedCorrectionController(MarketReturnedCorrectionService service) {
        this.service = service;
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam String productCode) {
        var workbook = service.download(productCode);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(workbook.filename(), StandardCharsets.UTF_8).build().toString())
                .body(workbook.bytes());
    }

    @PostMapping
    ResponseEntity<ApiResponse<ImportJobView>> upload(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String productCode,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        ImportJobView job = service.upload(idempotencyKey, productCode,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        boolean pending = "QUEUED".equals(job.statusCode()) || "PROCESSING".equals(job.statusCode());
        return ResponseEntity.status(pending ? 202 : 201).body(new ApiResponse<>(job));
    }

    @GetMapping("/{importJobId}")
    ApiResponse<ImportJobView> status(@PathVariable UUID importJobId) {
        return new ApiResponse<>(service.status(importJobId));
    }

    @GetMapping("/{importJobId}/errors")
    ResponseEntity<byte[]> errors(@PathVariable UUID importJobId) {
        ImportErrorFile file = service.errors(importJobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
}
