package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointDraft;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointService;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointView;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/formal-sample-points")
public class FormalSamplePointController {
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "regionCode", "keyword", "page", "pageSize");
    private final FormalSamplePointService service;

    public FormalSamplePointController(FormalSamplePointService service) {
        this.service = service;
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
}
