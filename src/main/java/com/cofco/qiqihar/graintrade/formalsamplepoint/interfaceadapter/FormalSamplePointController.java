package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointDraft;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointImportService;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleMaintainerView;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointService;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointView;
import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.samplepoint.importing.SamplePointImportResult;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/formal-sample-points")
public class FormalSamplePointController {
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "regionCode", "keyword", "page", "pageSize");
    private final FormalSamplePointService service;
    private final FormalSamplePointImportService imports;

    public FormalSamplePointController(
            FormalSamplePointService service, FormalSamplePointImportService imports) {
        this.service = service;
        this.imports = imports;
    }

    @GetMapping("/import-template")
    ResponseEntity<byte[]> importTemplate() {
        return xlsx("正式样本批量新增模板.xlsx", imports.template());
    }

    @PostMapping("/imports")
    ResponseEntity<ApiResponse<SamplePointImportResult>> importFile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        SamplePointImportResult result = imports.importFile(
                idempotencyKey,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? null : file.getBytes());
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .body(new ApiResponse<>(result));
    }

    @GetMapping("/imports/{importId}/errors")
    ResponseEntity<byte[]> importErrors(@PathVariable UUID importId) {
        ImportErrorFile file = imports.errors(importId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(file.bytes());
    }

    @GetMapping
    ApiResponse<PageResponse> list(
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters query = StrictQueryParameters.parse(
                parameters, LIST_PARAMETERS::contains,
                FormalSamplePointController::invalid);
        return new ApiResponse<>(PageResponse.from(service.list(
                query.optional("regionCode"), query.optional("keyword"),
                query.integer("page", 0), query.integer("pageSize", 20))));
    }

    @GetMapping("/{id}")
    ApiResponse<FormalSamplePointView> get(@PathVariable String id) {
        return new ApiResponse<>(service.get(id(id)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<FormalSamplePointView>> create(@RequestBody Request request) {
        if (request == null || request.expectedVersion() != null) throw invalid();
        FormalSamplePointView created = service.create(request.draft());
        return ResponseEntity.created(URI.create(
                        "/api/v1/formal-sample-points/" + created.id()))
                .body(new ApiResponse<>(created));
    }

    @PutMapping("/{id}")
    ApiResponse<FormalSamplePointView> update(
            @PathVariable String id, @RequestBody Request request) {
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        return new ApiResponse<>(service.update(
                id(id), request.expectedVersion(), request.draft()));
    }

    @PutMapping("/{id}/maintainer")
    ApiResponse<FormalSampleMaintainerView> assignMaintainer(
            @PathVariable String id, @RequestBody MaintainerRequest request) {
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        return new ApiResponse<>(service.assignMaintainer(
                id(id), request.expectedVersion(), request.maintainerSubjectId(),
                request.maintainerChangeReason()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters query = StrictQueryParameters.parse(
                parameters, "expectedVersion"::equals,
                FormalSamplePointController::invalid);
        long expectedVersion;
        try {
            expectedVersion = Long.parseLong(query.required("expectedVersion"));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        service.delete(id(id), expectedVersion);
        return ResponseEntity.noContent().build();
    }

    record PageResponse(
            List<FormalSamplePointView> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<FormalSamplePointView> page) {
            return new PageResponse(page.items(), page.pageNumber(), page.pageSize(),
                    page.totalElements(), page.totalPages());
        }
    }

    record Request(
            String canonicalName,
            String regionCode,
            String address,
            BigDecimal longitude,
            BigDecimal latitude,
            String objectTypeCode,
            String maintainerSubjectId,
            String maintainerChangeReason,
            Long expectedVersion) {
        FormalSamplePointDraft draft() {
            return new FormalSamplePointDraft(
                    canonicalName, regionCode, address, longitude, latitude, objectTypeCode,
                    maintainerSubjectId, maintainerChangeReason);
        }
    }

    record MaintainerRequest(
            String maintainerSubjectId,
            String maintainerChangeReason,
            Long expectedVersion) {}

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_FORMAL_SAMPLE_POINT", "正式样本请求参数无效");
    }

    private static ResponseEntity<byte[]> xlsx(String filename, byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }
}
