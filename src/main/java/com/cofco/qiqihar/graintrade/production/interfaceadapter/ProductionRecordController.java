package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionListItem;
import com.cofco.qiqihar.graintrade.production.application.ProductionFactDefinition;
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
import java.util.ArrayList;
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
        try {
            Math.multiplyExact((long) pageNumber, pageSize);
        } catch (ArithmeticException exception) {
            throw invalidQuery();
        }
        return new ApiResponse<>(PageResponse.from(service.read(new ProductionRecordQuery(
                parsed.required("productCode"), parsed.required("pageKind"), pageNumber, pageSize, filters))));
    }

    @GetMapping("/api/v1/production-records/{id}")
    ApiResponse<RecordResponse> detail(@PathVariable String id) {
        return new ApiResponse<>(RecordResponse.from(service.detail(id)));
    }

    @GetMapping("/api/v1/production-record-definitions")
    ApiResponse<DefinitionResponse> definition(
            @RequestParam String productCode,
            @RequestParam(required = false) String objectTypeCode) {
        List<ProductionFactDefinition> definitions = service.factDefinitions(productCode, objectTypeCode);
        List<GroupResponse> groups = new ArrayList<>();
        for (String category : List.of("QUALITY", "COST", "INSURANCE", "SUBSIDY")) {
            groups.add(new GroupResponse(category, definitions.stream()
                    .filter(field -> field.category().equals(category)).map(FieldResponse::from).toList()));
        }
        return new ApiResponse<>(new DefinitionResponse(productCode, objectTypeCode, groups));
    }

    @PostMapping("/api/v1/production-records")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RecordResponse> create(@RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.create(request.toDraft())));
    }

    @PutMapping("/api/v1/production-records/{id}")
    ApiResponse<RecordResponse> saveDraft(@PathVariable String id, @RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.saveDraft(id, request.requiredVersion(), request.toDraft())));
    }

    @PostMapping("/api/v1/production-records/{id}/submit")
    ApiResponse<RecordResponse> submit(@PathVariable String id, @RequestBody VersionRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.submit(id, request.requiredVersion())));
    }

    @PostMapping("/api/v1/production-records/{id}/approve")
    ApiResponse<RecordResponse> approve(@PathVariable String id, @RequestBody VersionRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.approve(id, request.requiredVersion())));
    }

    @PostMapping("/api/v1/production-records/{id}/return")
    ApiResponse<RecordResponse> returnForCorrection(@PathVariable String id, @RequestBody ReturnRequest request) {
        return new ApiResponse<>(RecordResponse.from(
                service.returnForCorrection(id, request.requiredVersion(), request.reason())));
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException("INVALID_PRODUCTION_RECORD_QUERY", "Production record query context is invalid");
    }

    record DraftRequest(String productCode, String objectTypeCode, String regionCode, String cultivarCode,
                        LocalDate surveyDate, String cultivatedAreaMu, String yieldPerMuKilograms,
                        Map<String, String> quality, Map<String, String> costs, Map<String, String> insurance,
                        Map<String, String> subsidies, Long version) {
        ProductionDraft toDraft() {
            try {
                return new ProductionDraft(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                        decimal(cultivatedAreaMu), decimal(yieldPerMuKilograms), values(quality), values(costs),
                        values(insurance), values(subsidies));
            } catch (RuntimeException exception) {
                throw new ClientRequestException("INVALID_PRODUCTION_RECORD", "Production decimal values are invalid");
            }
        }
        long requiredVersion() {
            if (version == null || version < 0) throw new ClientRequestException(
                    "INVALID_PRODUCTION_RECORD", "A non-negative version is required");
            return version;
        }
        private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
        private static Map<String, BigDecimal> values(Map<String, String> values) {
            Map<String, BigDecimal> parsed = new LinkedHashMap<>();
            if (values != null) values.forEach((code, value) -> parsed.put(code, decimal(value)));
            return parsed;
        }
    }
    record VersionRequest(Long version) {
        long requiredVersion() {
            if (version == null || version < 0) throw new ClientRequestException(
                    "INVALID_PRODUCTION_RECORD", "A non-negative version is required");
            return version;
        }
    }
    record ReturnRequest(String reason, Long version) {
        long requiredVersion() { return new VersionRequest(version).requiredVersion(); }
    }
    record RecordResponse(String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
                          LocalDate surveyDate, OffsetDateTime reportedAt, String cultivatedAreaMu,
                          String yieldPerMuKilograms, String estimatedOutputKilograms, String status,
                          String returnReason, Map<String, String> quality, Map<String, String> costs,
                          Map<String, String> insurance, Map<String, String> subsidies,
                          List<String> allowedActions, long version) {
        static RecordResponse from(ProductionRecord record) {
            return new RecordResponse(record.id(), record.productCode(), record.objectTypeCode(), record.regionCode(),
                    record.cultivarCode(), record.surveyDate(), record.reportedAt(), decimal(record.cultivatedAreaMu()),
                    decimal(record.yieldPerMuKilograms()), decimal(record.estimatedOutputKilograms()),
                    record.status().name(), record.returnReason(), values(record.quality()), values(record.costs()),
                    values(record.insurance()), values(record.subsidies()), allowed(record), record.version());
        }
        private static List<String> allowed(ProductionRecord record) {
            return switch (record.status()) {
                case DRAFT, RETURNED -> List.of("SAVE", "SUBMIT");
                case PENDING_REVIEW -> List.of("APPROVE", "RETURN");
                case APPROVED -> List.of();
            };
        }
        private static String decimal(BigDecimal value) { return value.toPlainString(); }
        private static Map<String, String> values(Map<String, BigDecimal> values) {
            Map<String, String> response = new LinkedHashMap<>();
            values.forEach((code, value) -> response.put(code, decimal(value)));
            return response;
        }
    }
    record ListItemResponse(String id, Map<String, String> values, List<String> allowedActions, long version) {
        static ListItemResponse from(ProductionListItem item) {
            return new ListItemResponse(item.id(), item.values(), item.allowedActions(), item.version());
        }
    }
    record PageResponse(List<ListItemResponse> items, int pageNumber, int pageSize, long totalElements, int totalPages) {
        static PageResponse from(PagedResult<ProductionListItem> page) {
            return new PageResponse(page.items().stream().map(ListItemResponse::from).toList(), page.pageNumber(),
                    page.pageSize(), page.totalElements(), page.totalPages());
        }
    }
    record DefinitionResponse(String productCode, String objectTypeCode, List<GroupResponse> groups) { }
    record GroupResponse(String category, List<FieldResponse> fields) { }
    record FieldResponse(String code, String label, String valueType, String unit, String description,
                         int precision, int scale) {
        static FieldResponse from(ProductionFactDefinition field) {
            return new FieldResponse(field.code(), field.label(), field.valueType(), field.unit(),
                    field.description(), field.precision(), field.scale());
        }
    }
}
