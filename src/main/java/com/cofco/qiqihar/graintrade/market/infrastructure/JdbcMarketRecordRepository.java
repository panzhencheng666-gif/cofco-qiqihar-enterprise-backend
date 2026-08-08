package com.cofco.qiqihar.graintrade.market.infrastructure;

import com.cofco.qiqihar.graintrade.market.application.MarketRecordRepository;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcMarketRecordRepository implements MarketRecordRepository {

    private static final TypeReference<Map<String, Object>> VALUES_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMarketRecordRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = JdbcClient.create(dataSource);
        this.objectMapper = objectMapper;
    }

    @Override
    public PagedResult<MarketRecord> findPage(MarketRecordQuery query) {
        if (query.authorizedRegionCodes().isEmpty()) {
            return new PagedResult<>(List.of(), query.pageNumber(), query.pageSize(), 0);
        }
        String filtersJson = json(query.filters());
        long totalElements = statement("""
                        SELECT count(*)
                        FROM market.market_record_projection
                        WHERE product_code = :productCode
                          AND business_domain = 'MARKET'
                          AND page_kind = :pageKind
                          AND values @> CAST(:filters AS jsonb)
                          AND (:unrestricted OR EXISTS(
                            SELECT 1 FROM market.market_record source
                            WHERE source.record_id=market_record_projection.record_id
                              AND source.region_code IN (:authorizedRegionCodes)))
                        """, query, filtersJson)
                .query(Long.class)
                .single();
        List<MarketRecord> items = statement("""
                        SELECT record_id, values
                        FROM market.market_record_projection
                        WHERE product_code = :productCode
                          AND business_domain = 'MARKET'
                          AND page_kind = :pageKind
                          AND values @> CAST(:filters AS jsonb)
                          AND (:unrestricted OR EXISTS(
                            SELECT 1 FROM market.market_record source
                            WHERE source.record_id=market_record_projection.record_id
                              AND source.region_code IN (:authorizedRegionCodes)))
                        ORDER BY observed_at, record_id
                        LIMIT :pageSize OFFSET :offset
                        """, query, filtersJson)
                .param("pageSize", query.pageSize())
                .param("offset", (long) query.pageNumber() * query.pageSize())
                .query((row, rowNumber) -> new MarketRecord(
                        row.getString("record_id"),
                        values(row.getString("values"))))
                .list();
        return new PagedResult<>(
                items, query.pageNumber(), query.pageSize(), totalElements);
    }

    private JdbcClient.StatementSpec statement(
            String sql, MarketRecordQuery query, String filtersJson) {
        return jdbc.sql(sql)
                .param("productCode", query.productCode())
                .param("pageKind", query.pageKind())
                .param("filters", filtersJson)
                .param("unrestricted", query.authorizedRegionCodes().contains("*"))
                .param("authorizedRegionCodes", query.authorizedRegionCodes());
    }

    private String json(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not encode market filters", exception);
        }
    }

    private Map<String, Object> values(String json) {
        try {
            return objectMapper.readValue(json, VALUES_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not decode market projection values", exception);
        }
    }
}
