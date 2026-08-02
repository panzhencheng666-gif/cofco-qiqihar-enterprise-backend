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
            @RequestParam MultiValueMap<String, String> parameters) {
        ParsedParameters parsed = parse(parameters);
        MarketRecordQuery query = new MarketRecordQuery(
                parsed.productCode(),
                parsed.pageKind(),
                parsed.pageNumber(),
                parsed.pageSize(),
                parsed.filters());
        return new ApiResponse<>(PageResponse.from(reader.read(query)));
    }

    private static ParsedParameters parse(MultiValueMap<String, String> parameters) {
        Map<String, String> core = new LinkedHashMap<>();
        Map<String, String> filters = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (values == null || values.size() != 1) {
                throw invalidQuery();
            }
            String value = values.get(0);
            if (value == null || value.isBlank()) {
                throw invalidQuery();
            }
            if (CORE_PARAMETERS.contains(name)) {
                core.put(name, value);
                return;
            }
            Matcher filter = FILTER_PARAMETER.matcher(name);
            if (!filter.matches()) {
                throw invalidQuery();
            }
            filters.put(filter.group(1), value);
        });

        String productCode = required(core, "productCode");
        String pageKind = required(core, "pageKind");
        int pageNumber = parseInteger(core.getOrDefault("pageNumber", "0"));
        int pageSize = parseInteger(required(core, "pageSize"));
        if (pageNumber < 0 || pageSize < 1) {
            throw invalidQuery();
        }
        return new ParsedParameters(productCode, pageKind, pageNumber, pageSize, filters);
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null) {
            throw invalidQuery();
        }
        return value;
    }

    private static int parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidQuery();
        }
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
}
