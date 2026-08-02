package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.MarketRecordReader;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
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

    public MarketRecordController(MarketRecordReader reader) {
        this.reader = reader;
    }

    @GetMapping("/api/v1/market-records")
    ApiResponse<PageResponse> records(
            @RequestParam String productCode,
            @RequestParam String pageKind,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam int pageSize,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (productCode.isBlank() || pageKind.isBlank() || pageNumber < 0 || pageSize < 1) {
            throw invalidQuery();
        }
        MarketRecordQuery query = new MarketRecordQuery(
                productCode, pageKind, pageNumber, pageSize, filters(parameters));
        return new ApiResponse<>(PageResponse.from(reader.read(query)));
    }

    private static Map<String, String> filters(MultiValueMap<String, String> parameters) {
        Map<String, String> filters = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (values == null || values.size() != 1) {
                throw invalidQuery();
            }
            if (CORE_PARAMETERS.contains(name)) {
                return;
            }
            Matcher filter = FILTER_PARAMETER.matcher(name);
            String value = values.get(0);
            if (!filter.matches() || value == null || value.isBlank()) {
                throw invalidQuery();
            }
            filters.put(filter.group(1), value);
        });
        return filters;
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException(
                "INVALID_MARKET_RECORD_QUERY", "Market record query context is invalid");
    }

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
}
