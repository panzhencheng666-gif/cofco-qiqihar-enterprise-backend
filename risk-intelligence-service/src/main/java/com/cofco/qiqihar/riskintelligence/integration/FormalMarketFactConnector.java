package com.cofco.qiqihar.riskintelligence.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls a narrow, read-only business projection into immutable risk facts. */
@Component
@ConditionalOnProperty(name = "qiqihar.risk.integration.market.enabled", havingValue = "true")
class FormalMarketFactConnector {
    private static final int BATCH_SIZE = 100;
    private static final String SOURCE_SYSTEM = "QIQIHAR_ENTERPRISE";
    private static final String RECORD_TYPE = "MARKET_RECORD";

    private final JdbcClient jdbc;
    private final SourceFactIngestionService ingestion;

    FormalMarketFactConnector(JdbcClient jdbc, SourceFactIngestionService ingestion) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
    }

    @Scheduled(fixedDelayString = "${qiqihar.risk.integration.market.poll-delay:PT1M}")
    void poll() {
        List<MarketFactRow> rows = jdbc.sql("""
                SELECT source.record_id,source.version,source.region_code,source.product_code,
                       source.object_type_code,source.trade_date,source.reported_at,
                       source.submitted_at,source.updated_at,source.status_code,
                       source.trade_direction,source.purchase_base_price,
                       source.sale_base_price,source.actual_trade_price
                FROM risk.formal_market_fact_source source
                WHERE NOT EXISTS (
                    SELECT 1 FROM risk.source_fact_snapshot fact
                    WHERE fact.source_system=:sourceSystem
                      AND fact.source_record_type=:recordType
                      AND fact.source_record_id=source.record_id
                      AND fact.source_version=source.version::text)
                ORDER BY source.updated_at,source.record_id
                LIMIT :limit
                """)
                .param("sourceSystem", SOURCE_SYSTEM)
                .param("recordType", RECORD_TYPE)
                .param("limit", BATCH_SIZE)
                .query((row, ignored) -> new MarketFactRow(
                        row.getString("record_id"), row.getLong("version"),
                        row.getString("region_code"), row.getString("product_code"),
                        row.getString("object_type_code"), row.getObject("trade_date", LocalDate.class),
                        row.getObject("reported_at", OffsetDateTime.class),
                        row.getObject("submitted_at", OffsetDateTime.class),
                        row.getObject("updated_at", OffsetDateTime.class),
                        row.getString("status_code"), row.getString("trade_direction"),
                        row.getBigDecimal("purchase_base_price"),
                        row.getBigDecimal("sale_base_price"),
                        row.getBigDecimal("actual_trade_price"))).list();
        for (MarketFactRow row : rows) {
            ingestion.ingestFromTrustedProjection(row.sourceFact());
        }
    }

    record MarketFactRow(
            String recordId, long version, String regionCode, String productCode,
            String objectTypeCode, LocalDate tradeDate, OffsetDateTime reportedAt,
            OffsetDateTime submittedAt, OffsetDateTime updatedAt, String statusCode,
            String tradeDirection, BigDecimal purchaseBasePrice,
            BigDecimal saleBasePrice, BigDecimal actualTradePrice) {
        SourceFact sourceFact() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("regionCode", regionCode);
            payload.put("productCode", productCode);
            payload.put("objectTypeCode", objectTypeCode);
            payload.put("tradeDate", tradeDate.toString());
            payload.put("reportedAt", reportedAt.toInstant().toString());
            payload.put("submittedAt", submittedAt.toInstant().toString());
            payload.put("updatedAt", updatedAt.toInstant().toString());
            payload.put("statusCode", statusCode);
            payload.put("tradeDirection", tradeDirection);
            putDecimal(payload, "purchaseBasePrice", purchaseBasePrice);
            putDecimal(payload, "saleBasePrice", saleBasePrice);
            putDecimal(payload, "actualTradePrice", actualTradePrice);
            return new SourceFact(SOURCE_SYSTEM, RECORD_TYPE, recordId,
                    Long.toString(version), reportedAt.toInstant(), payload);
        }

        private static void putDecimal(Map<String, Object> payload, String key, BigDecimal value) {
            if (value != null) payload.put(key, value.toPlainString());
        }
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "qiqihar.risk.integration.market.enabled", havingValue = "true")
class FormalMarketFactSchedulingConfiguration {}
