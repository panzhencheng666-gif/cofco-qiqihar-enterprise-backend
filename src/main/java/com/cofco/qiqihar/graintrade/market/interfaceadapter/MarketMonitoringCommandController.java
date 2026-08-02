package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.MarketCoreFieldDefinition;
import com.cofco.qiqihar.graintrade.market.application.MarketFactDefinition;
import com.cofco.qiqihar.graintrade.market.application.MarketFactGroup;
import com.cofco.qiqihar.graintrade.market.application.MarketFieldOption;
import com.cofco.qiqihar.graintrade.market.application.MarketFormDefinition;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringDraft;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.market.application.MarketRecordView;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class MarketMonitoringCommandController {
    private final MarketMonitoringService service;

    public MarketMonitoringCommandController(MarketMonitoringService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/market-records/{id}")
    ApiResponse<RecordResponse> detail(@PathVariable String id) {
        return new ApiResponse<>(RecordResponse.from(service.detail(id)));
    }

    @GetMapping("/api/v1/market-record-definitions")
    ApiResponse<DefinitionResponse> definition(
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters,
                name -> name.equals("productCode") || name.equals("objectTypeCode"),
                () -> invalid("Invalid market definition context"));
        return new ApiResponse<>(DefinitionResponse.from(service.definition(
                parsed.required("productCode"), parsed.optional("objectTypeCode"))));
    }

    @PostMapping("/api/v1/market-records")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RecordResponse> create(@RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.create(request.toDraft())));
    }

    @PutMapping("/api/v1/market-records/{id}")
    ApiResponse<RecordResponse> save(@PathVariable String id, @RequestBody DraftRequest request) {
        return new ApiResponse<>(RecordResponse.from(
                service.save(id, request.requiredVersion(), request.toDraft())));
    }

    @PostMapping("/api/v1/market-records/{id}/submit")
    ApiResponse<RecordResponse> submit(@PathVariable String id, @RequestBody VersionRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.submit(id, request.requiredVersion())));
    }

    @PostMapping("/api/v1/market-records/{id}/approve")
    ApiResponse<RecordResponse> approve(@PathVariable String id, @RequestBody VersionRequest request) {
        return new ApiResponse<>(RecordResponse.from(service.approve(id, request.requiredVersion())));
    }

    @PostMapping("/api/v1/market-records/{id}/return")
    ApiResponse<RecordResponse> returned(@PathVariable String id, @RequestBody ReturnRequest request) {
        return new ApiResponse<>(RecordResponse.from(
                service.returnForCorrection(id, request.requiredVersion(), request.reason())));
    }

    record DraftRequest(
            String productCode, Map<String, String> coreValues,
            Map<String, String> facts, Long version) {
        MarketMonitoringDraft toDraft() {
            try {
                return new MarketMonitoringDraft(productCode, coreValues, parseDecimals(facts));
            } catch (RuntimeException exception) {
                if (exception instanceof ClientRequestException clientRequestException) {
                    throw clientRequestException;
                }
                throw invalid("Market fact values are invalid");
            }
        }

        long requiredVersion() {
            if (version == null || version < 0) throw invalid("A non-negative version is required");
            return version;
        }
    }

    record VersionRequest(Long version) {
        long requiredVersion() {
            if (version == null || version < 0) throw invalid("A non-negative version is required");
            return version;
        }
    }

    record ReturnRequest(String reason, Long version) {
        long requiredVersion() {
            return new VersionRequest(version).requiredVersion();
        }
    }

    record DefinitionResponse(
            String productCode, String objectTypeCode,
            List<CoreFieldResponse> coreFields, List<GroupResponse> groups) {
        static DefinitionResponse from(MarketFormDefinition definition) {
            return new DefinitionResponse(
                    definition.productCode(), definition.objectTypeCode(),
                    definition.coreFields().stream().map(CoreFieldResponse::from).toList(),
                    definition.groups().stream().map(GroupResponse::from).toList());
        }
    }

    record CoreFieldResponse(
            String code, String label, String controlType, String unit, String description,
            String capability, boolean required,
            Integer precision, Integer scale, int sortOrder, List<OptionResponse> options) {
        static CoreFieldResponse from(MarketCoreFieldDefinition field) {
            return new CoreFieldResponse(
                    field.code(), field.label(), field.controlType(), field.unit(), field.description(),
                    field.capability(), field.required(),
                    field.precision(), field.scale(), field.sortOrder(),
                    field.options().stream().map(OptionResponse::from).toList());
        }
    }

    record OptionResponse(String value, String label, int sortOrder) {
        static OptionResponse from(MarketFieldOption option) {
            return new OptionResponse(option.value(), option.label(), option.sortOrder());
        }
    }

    record GroupResponse(String category, String label, int sortOrder, List<FieldResponse> fields) {
        static GroupResponse from(MarketFactGroup group) {
            return new GroupResponse(
                    group.category(), group.label(), group.sortOrder(),
                    group.fields().stream().map(FieldResponse::from).toList());
        }
    }

    record FieldResponse(
            String code, String label, String valueType, String unit, String description,
            int precision, int scale, int sortOrder) {
        static FieldResponse from(MarketFactDefinition field) {
            return new FieldResponse(
                    field.code(), field.label(), field.valueType(), field.unit(), field.description(),
                    field.precision(), field.scale(), field.sortOrder());
        }
    }

    record RecordResponse(
            String id, String productCode, Map<String, String> coreValues,
            String status, String returnReason,
            Map<String, String> facts, List<String> allowedActions, long version) {
        static RecordResponse from(MarketRecordView view) {
            return new RecordResponse(
                    view.record().id(), view.record().productCode(), view.coreValues(),
                    view.record().status().name(), view.record().returnReason(),
                    formatDecimals(view.record().facts()), view.allowedActions(), view.record().version());
        }
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Map<String, BigDecimal> parseDecimals(Map<String, String> values) {
        Map<String, BigDecimal> parsed = new LinkedHashMap<>();
        if (values != null) values.forEach((code, value) -> parsed.put(code, decimal(value)));
        return parsed;
    }

    private static Map<String, String> formatDecimals(Map<String, BigDecimal> values) {
        Map<String, String> response = new LinkedHashMap<>();
        values.forEach((code, value) -> response.put(code, value == null ? null : value.toPlainString()));
        return response;
    }

    private static ClientRequestException invalid(String message) {
        return new ClientRequestException("INVALID_MARKET_RECORD", message);
    }
}
