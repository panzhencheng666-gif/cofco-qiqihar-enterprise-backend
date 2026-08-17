package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportService;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportPhotoPackage;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
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
@RequestMapping("/api/v1/imports/market")
public class MarketImportController {
    private final MarketImportService service;

    public MarketImportController(MarketImportService service) { this.service = service; }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "csv") String format,
                                    @RequestParam(required = false) String productCode,
                                    @RequestParam(required = false) String objectTypeCode) {
        if ("xlsx".equalsIgnoreCase(format)) {
            byte[] bytes = BusinessImportWorkbook.create(service.productWorkbook(productCode));
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("市场-" + BusinessImportWorkbook.businessLabel(productCode)
                                    + "-批量导入模板.xlsx",
                                    StandardCharsets.UTF_8).build().toString())
                    .body(bytes);
        }
        return csv("market-import-template.csv", service.template().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping
    ResponseEntity<ApiResponse<ImportJobView>> importFile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String productCode,
            @RequestParam(required = false) String objectTypeCode,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "photos", required = false) List<MultipartFile> photos) throws java.io.IOException {
        ImportJobView job = objectTypeCode == null || objectTypeCode.isBlank()
                ? service.importProductWorkbook(idempotencyKey, productCode, file.getOriginalFilename(),
                        file.getContentType(), file.getBytes(), photoParts(photos))
                : service.importFile(idempotencyKey, productCode, objectTypeCode,
                        file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return acceptedOrCreated(job);
    }

    @GetMapping("/{importJobId}")
    ApiResponse<ImportJobView> status(@PathVariable UUID importJobId) {
        return new ApiResponse<>(service.status(importJobId));
    }

    @GetMapping("/{importJobId}/errors")
    ResponseEntity<byte[]> errors(@PathVariable UUID importJobId) {
        ImportErrorFile file = service.errors(importJobId);
        return csv(file.filename(), file.bytes());
    }

    @PostMapping("/{importJobId}/retries")
    ResponseEntity<ApiResponse<ImportJobView>> retry(@PathVariable UUID importJobId) {
        ImportJobView job = service.retry(importJobId);
        boolean pending = job.statusCode().equals("QUEUED") || job.statusCode().equals("PROCESSING");
        return ResponseEntity.status(pending ? 202 : 200).body(new ApiResponse<>(job));
    }

    private static ResponseEntity<byte[]> csv(String filename, byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }
    private static ResponseEntity<ApiResponse<ImportJobView>> acceptedOrCreated(ImportJobView job) {
        boolean pending = job.statusCode().equals("QUEUED") || job.statusCode().equals("PROCESSING");
        return ResponseEntity.status(pending ? 202 : 201).body(new ApiResponse<>(job));
    }

    private static List<BusinessImportPhotoPackage.PhotoPart> photoParts(List<MultipartFile> files)
            throws java.io.IOException {
        if (files == null) return List.of();
        java.util.ArrayList<BusinessImportPhotoPackage.PhotoPart> parts = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            parts.add(new BusinessImportPhotoPackage.PhotoPart(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        return List.copyOf(parts);
    }
}
