package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultMarketRecordReaderTest {

    @Test
    void rejectsPageSizesOutsideTheLoadedDefinition() {
        DefaultMarketRecordReader reader = reader(definition(List.of("subjectName")));

        assertThatThrownBy(() -> reader.read(query(7, Map.of())))
                .isInstanceOf(ClientRequestException.class)
                .satisfies(error -> assertThat(((ClientRequestException) error).code())
                        .isEqualTo("INVALID_MARKET_RECORD_QUERY"));
    }

    @Test
    void rejectsFiltersOutsideTheLoadedDefinition() {
        DefaultMarketRecordReader reader = reader(definition(List.of("subjectName")));

        assertThatThrownBy(() -> reader.read(query(20, Map.of("unknown", "value"))))
                .isInstanceOf(ClientRequestException.class)
                .satisfies(error -> assertThat(((ClientRequestException) error).code())
                        .isEqualTo("INVALID_MARKET_RECORD_QUERY"));
    }

    private DefaultMarketRecordReader reader(BusinessPageDefinition definition) {
        PageDefinitionQuery definitions = key -> definition;
        return new DefaultMarketRecordReader(new MarketRecordRepository() {
            @Override
            public PagedResult<MarketRecord> findPage(MarketRecordQuery query) {
                throw new AssertionError("invalid query must not reach PostgreSQL");
            }
        }, definitions);
    }

    private MarketRecordQuery query(int pageSize, Map<String, String> filters) {
        return new MarketRecordQuery("SOYBEAN", "QUALITY", 0, pageSize, filters);
    }

    private BusinessPageDefinition definition(List<String> filterCodes) {
        return new BusinessPageDefinition(
                new BusinessPageKey("MARKET", "QUALITY", "SOYBEAN"),
                "大豆质量指标",
                List.of(),
                filterCodes.stream()
                        .map(code -> new BusinessPageDefinition.Filter(
                                code,
                                code,
                                BusinessPageDefinition.FilterControl.TEXT,
                                "",
                                List.of()))
                        .toList(),
                Map.of(),
                List.of(),
                List.of(),
                new BusinessPageDefinition.Pagination(20, List.of(20, 50, 100)));
    }
}
