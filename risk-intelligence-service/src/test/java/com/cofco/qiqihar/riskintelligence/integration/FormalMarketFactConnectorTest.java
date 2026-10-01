package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class FormalMarketFactConnectorTest {
    @Test
    void projectsAFormalMarketVersionWithoutPersonalFields() {
        var row = new FormalMarketFactConnector.MarketFactRow(
                "formal-market-1", 7, "230221", "CORN", "TRADER",
                LocalDate.parse("2026-09-20"),
                OffsetDateTime.parse("2026-09-21T09:00:00+08:00"),
                OffsetDateTime.parse("2026-09-21T10:00:00+08:00"),
                OffsetDateTime.parse("2026-09-22T10:00:00+08:00"),
                "APPROVED", "PURCHASE", new BigDecimal("2300.0000"), null,
                new BigDecimal("2310.0000"));

        SourceFact fact = row.sourceFact();

        assertThat(fact.sourceSystem()).isEqualTo("QIQIHAR_ENTERPRISE");
        assertThat(fact.sourceRecordType()).isEqualTo("MARKET_RECORD");
        assertThat(fact.sourceRecordId()).isEqualTo("formal-market-1");
        assertThat(fact.sourceVersion()).isEqualTo("7");
        assertThat(fact.businessOccurredAt()).isEqualTo("2026-09-21T01:00:00Z");
        assertThat(fact.payload()).containsEntry("regionCode", "230221")
                .containsEntry("purchaseBasePrice", "2300.0000")
                .containsEntry("actualTradePrice", "2310.0000")
                .doesNotContainKeys("saleBasePrice", "sampleName", "sampleContact", "phone");
    }

    @Test
    void preservesAWithdrawnVersionForDownstreamReevaluation() {
        var row = new FormalMarketFactConnector.MarketFactRow(
                "formal-market-1", 8, "230221", "CORN", "TRADER",
                LocalDate.parse("2026-09-20"),
                OffsetDateTime.parse("2026-09-21T09:00:00+08:00"),
                OffsetDateTime.parse("2026-09-21T10:00:00+08:00"),
                OffsetDateTime.parse("2026-09-23T10:00:00+08:00"),
                "VOIDED", "PURCHASE", new BigDecimal("2300"), null,
                new BigDecimal("2310"));

        SourceFact fact = row.sourceFact();

        assertThat(fact.sourceVersion()).isEqualTo("8");
        assertThat(fact.payload()).containsEntry("statusCode", "VOIDED");
    }
}
