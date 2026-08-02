package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductionRecordController {
    private static final Set<String> CORE = Set.of("productCode", "pageKind", "pageNumber", "pageSize");
    private static final Pattern FILTER = Pattern.compile("^filter\\.([A-Za-z0-9][A-Za-z0-9_-]*)$");
    private final ProductionRecordService service;

    public ProductionRecordController(ProductionRecordService service) { this.service = service; }

    @GetMapping("/api/v1/production-records")
    ApiResponse<PageResponse> records(@RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(parameters,
                name -> CORE.contains(name) || FILTER.matcher(name).matches(), ProductionRecordController::invalidQuery);
        Map<String, String> filters = new LinkedHashMap<>();
        parsed.values().forEach((name, value) -> {
            Matcher matcher = FILTER.matcher(name);
            if (matcher.matches()) filters.put(matcher.group(1), value);
        });
        int pageNumber = parsed.integer("pageNumber", 0);
        int pageSize = parsed.integer("pageSize", -1);
        if (pageNumber < 0 || pageSize < 1) throw invalidQuery();
        return new ApiResponse<>(PageResponse.from(service.read(new ProductionRecordQuery(
                parsed.required("productCode"), parsed.required("pageKind"), pageNumber, pageSize, filters))));
    }

    @GetMapping("/api/v1/production-records/{id}")
    ApiResponse<RecordResponse> detail(@PathVariable String id) { return new ApiResponse<>(RecordResponse.from(service.detail(id))); }

    @PostMapping("/api/v1/production-records")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RecordResponse> create(@RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.create(request.toDraft())));
    }

    @PutMapping("/api/v1/production-records/{id}")
    ApiResponse<RecordResponse> saveDraft(@PathVariable String id, @RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.saveDraft(id, request.toDraft())));
    }

    @PostMapping("/api/v1/production-records/{id}/submit")
    ApiResponse<RecordResponse> submit(@PathVariable String id) { return new ApiResponse<>(RecordResponse.from(service.submit(id))); }

    @PostMapping("/api/v1/production-records/{id}/approve")
    ApiResponse<RecordResponse> approve(@PathVariable String id) { return new ApiResponse<>(RecordResponse.from(service.approve(id))); }

    @PostMapping("/api/v1/production-records/{id}/return")
    ApiResponse<RecordResponse> returnForCorrection(@PathVariable String id, @RequestBody ReturnRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.returnForCorrection(id, request.reason())));
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException("INVALID_PRODUCTION_RECORD_QUERY", "Production record query context is invalid");
    }

    record DraftRequest(String productCode, String objectTypeCode, String regionCode, String cultivarCode,
                        LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
                        BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
                        Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance,
                        Map<String, BigDecimal> subsidies) {
        ProductionDraft toDraft() {
            return new ProductionDraft(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, reportedAt,
                    cultivatedAreaMu, yieldPerMuKilograms, values(quality), values(costs), values(insurance), values(subsidies));
        }
        private static Map<String, BigDecimal> values(Map<String, BigDecimal> values) { return values == null ? Map.of() : values; }
    }
    record ReturnRequest(String reason) { }
    record RecordResponse(String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
                          LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
                          BigDecimal yieldPerMuKilograms, BigDecimal estimatedOutputKilograms, String status,
                          String returnReason, Map<String, BigDecimal> quality) {
        static RecordResponse from(ProductionRecord record) { return new RecordResponse(record.id(), record.productCode(),
                record.objectTypeCode(), record.regionCode(), record.cultivarCode(), record.surveyDate(), record.reportedAt(),
                record.cultivatedAreaMu(), record.yieldPerMuKilograms(), record.estimatedOutputKilograms(),
                record.status().name(), record.returnReason(), record.quality()); }
    }
    record PageResponse(List<RecordResponse> items, int pageNumber, int pageSize, long totalElements, int totalPages) {
        static PageResponse from(PagedResult<ProductionRecord> page) { return new PageResponse(
                page.items().stream().map(RecordResponse::from).toList(), page.pageNumber(), page.pageSize(),
                page.totalElements(), page.totalPages()); }
    }
}
