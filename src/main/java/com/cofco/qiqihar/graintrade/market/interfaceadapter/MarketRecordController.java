package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.MarketRecordReader;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.market.application.MarketListItem;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketRecordController {

    private static final Set<String> CORE_PARAMETERS =
            Set.of("productCode", "pageKind", "pageNumber", "pageSize");
    private static final Pattern FILTER_PARAMETER =
            Pattern.compile("^filter\\.([A-Za-z0-9][A-Za-z0-9_-]*)$");

    private final MarketRecordReader reader;
    private final MarketMonitoringService monitoring;

    @Autowired
    public MarketRecordController(MarketRecordReader reader, MarketMonitoringService monitoring) {
        this.reader = reader;
        this.monitoring = monitoring;
    }

    @GetMapping("/api/v1/market-records")
    ApiResponse<?> records(
            @RequestParam MultiValueMap<String, String> parameters) {
        ParsedParameters parsed = parse(parameters);
        MarketRecordQuery query = new MarketRecordQuery(
                parsed.productCode(),
                parsed.pageKind(),
                parsed.pageNumber(),
                parsed.pageSize(),
                parsed.filters());
        if ("MONITORING".equals(query.pageKind())) {
            return new ApiResponse<>(MonitoringPageResponse.from(monitoring.list(query)));
        }
        return new ApiResponse<>(PageResponse.from(reader.read(query)));
    }

    private static ParsedParameters parse(MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters,
                name -> CORE_PARAMETERS.contains(name) || FILTER_PARAMETER.matcher(name).matches(),
                MarketRecordController::invalidQuery);
        Map<String, String> filters = new LinkedHashMap<>();
        parsed.values().forEach((name, value) -> {
            Matcher filter = FILTER_PARAMETER.matcher(name);
            if (filter.matches()) {
                filters.put(filter.group(1), value);
            }
        });

        String productCode = parsed.required("productCode");
        String pageKind = parsed.required("pageKind");
        int pageNumber = parsed.integer("pageNumber", 0);
        int pageSize = parsed.integer("pageSize", -1);
        if (pageNumber < 0 || pageSize < 1) {
            throw invalidQuery();
        }
        return new ParsedParameters(productCode, pageKind, pageNumber, pageSize, filters);
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException(
                "INVALID_MARKET_RECORD_QUERY", "Market record query context is invalid");
    }

    private record ParsedParameters(
            String productCode,
            String pageKind,
            int pageNumber,
            int pageSize,
            Map<String, String> filters) {}

    record RecordResponse(String id, Map<String, Object> values) {
        static RecordResponse from(MarketRecord record) {
            return new RecordResponse(record.id(), record.values());
        }
    }

    record PageResponse(
            List<RecordResponse> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<MarketRecord> page) {
            return new PageResponse(
                    page.items().stream().map(RecordResponse::from).toList(),
                    page.pageNumber(),
                    page.pageSize(),
                    page.totalElements(),
                    page.totalPages());
        }
    }

    record MonitoringItemResponse(String id, Map<String, String> values, List<String> allowedActions, long version) {
        static MonitoringItemResponse from(MarketListItem item) {
            return new MonitoringItemResponse(
                    item.id(), item.values(), item.allowedActions(), item.version());
        }
    }

    record MonitoringPageResponse(List<MonitoringItemResponse> items, int pageNumber, int pageSize,
            long totalElements, int totalPages) {
        static MonitoringPageResponse from(PagedResult<MarketListItem> page) {
            return new MonitoringPageResponse(page.items().stream().map(MonitoringItemResponse::from).toList(),
                    page.pageNumber(), page.pageSize(), page.totalElements(), page.totalPages());
        }
    }
}
