package com.cofco.qiqihar.graintrade.designsample.point.interfaceadapter;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointDraft;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointImportService;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointRepository;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointService;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointView;
import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.SamplePointImportResult;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/design-sample-points")
public class DesignSamplePointController {
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "domainCode", "productCode", "objectTypeCode", "regionCode", "keyword",
            "page", "pageSize");
    private final DesignSamplePointService service;
    private final DesignSamplePointImportService imports;

    public DesignSamplePointController(
            DesignSamplePointService service, DesignSamplePointImportService imports) {
        this.service = service;
        this.imports = imports;
    }

    @GetMapping("/import-template")
    ResponseEntity<byte[]> importTemplate(
            @RequestParam(required = false) String domain) {
        return xlsx("设计样本点批量新增模板.xlsx", imports.template(domain));
    }

    @PostMapping("/imports")
    ResponseEntity<ApiResponse<SamplePointImportResult>> importFile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(required = false) String domain,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        SamplePointImportResult result = imports.importFile(
                domain,
                idempotencyKey,
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? null : file.getBytes());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
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
    ApiResponse<PageResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters query = StrictQueryParameters.parse(
                parameters, LIST_PARAMETERS::contains, DesignSamplePointController::invalid);
        return new ApiResponse<>(PageResponse.from(service.list(
                query.optional("domainCode"), query.optional("productCode"),
                query.optional("objectTypeCode"), query.optional("regionCode"),
                query.optional("keyword"), query.integer("page", 0),
                query.integer("pageSize", 20))));
    }

    @GetMapping("/{id}")
    ApiResponse<DesignSamplePointView> get(@PathVariable String id) {
        return new ApiResponse<>(service.get(id(id)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<DesignSamplePointView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Request request) {
        if (request == null || request.expectedVersion() != null) throw invalid();
        DesignSamplePointRepository.CreateResult result = service.create(
                idempotencyKey, request.draft());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new ApiResponse<>(result.point()));
    }

    @PutMapping("/{id}")
    ApiResponse<DesignSamplePointView> update(
            @PathVariable String id, @RequestBody Request request) {
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        return new ApiResponse<>(service.update(
                id(id), request.expectedVersion(), request.draft()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters query = StrictQueryParameters.parse(
                parameters, "expectedVersion"::equals, DesignSamplePointController::invalid);
        long expectedVersion;
        try {
            expectedVersion = Long.parseLong(query.required("expectedVersion"));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        service.delete(id(id), expectedVersion);
        return ResponseEntity.noContent().build();
    }

    record Request(
            String contractVersion,
            String contractDigest,
            DesignSampleContext context,
            Map<String, JsonNode> values,
            Long expectedVersion) {
        DesignSamplePointDraft draft() {
            return new DesignSamplePointDraft(
                    contractVersion, contractDigest, context, values);
        }
    }

    record PageResponse(
            List<DesignSamplePointView> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<DesignSamplePointView> page) {
            return new PageResponse(page.items(), page.pageNumber(), page.pageSize(),
                    page.totalElements(), page.totalPages());
        }
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_DESIGN_SAMPLE_POINT", "设计样本点请求参数无效");
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
