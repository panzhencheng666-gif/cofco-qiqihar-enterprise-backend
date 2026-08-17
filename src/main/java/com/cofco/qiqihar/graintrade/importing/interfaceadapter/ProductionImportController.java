package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobView;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportService;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportPhotoPackage;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
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
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "csv") String format,
                                    @RequestParam(required = false) String productCode,
                                    @RequestParam(required = false) String objectTypeCode) {
        if ("xlsx".equalsIgnoreCase(format)) {
            byte[] bytes = BusinessImportWorkbook.create(service.productWorkbook(productCode));
            return xlsx("产情-" + BusinessImportWorkbook.businessLabel(productCode)
                    + "-批量导入模板.xlsx", bytes);
        }
        return csv("production-import-template.csv", service.template().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<ImportJobView>> upload(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String productCode,
            @RequestParam(required = false) String objectTypeCode,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "photos", required = false) List<MultipartFile> photos) throws java.io.IOException {
        byte[] bytes = file == null ? null : file.getBytes();
        boolean productWorkbook = objectTypeCode == null || objectTypeCode.isBlank()
                || hasProductWorkbookContext(bytes, "PRODUCTION");
        ImportJobView job = productWorkbook
                ? service.importProductWorkbook(idempotencyKey, productCode,
                        file == null ? null : file.getOriginalFilename(),
                        file == null ? null : file.getContentType(), bytes, photoParts(photos))
                : service.importFile(idempotencyKey, productCode, objectTypeCode,
                        file == null ? null : file.getOriginalFilename(),
                        file == null ? null : file.getContentType(), bytes);
        return acceptedOrCreated(job);
    }

    private static boolean hasProductWorkbookContext(byte[] bytes, String domainCode) {
        try {
            BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(bytes, domainCode);
            return context.productCode() != null && context.objectTypeCode() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @GetMapping("/{importJobId}")
    ApiResponse<ImportJobView> status(@PathVariable UUID importJobId) {
        return new ApiResponse<>(service.status(importJobId));
    }

    @PostMapping("/{importJobId}/retries")
    ResponseEntity<ApiResponse<ImportJobView>> retry(@PathVariable UUID importJobId) {
        return acceptedOrCreated(service.retry(importJobId));
    }

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
    private static ResponseEntity<byte[]> xlsx(String name, byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString()).body(bytes);
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
