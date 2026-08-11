package com.cofco.qiqihar.graintrade.market.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRecordTest {

    @Test
    void comparesTradeDateUsingReportingTimezoneAfterTimestampRoundTrip() {
        MarketMonitoringRecord record = MarketMonitoringRecord.draft(
                "record-1", "CORN", "TRADER", "230200", LocalDate.of(2026, 8, 7),
                OffsetDateTime.parse("2026-08-06T23:30:00Z"), MarketTradeDirection.PURCHASE,
                new BigDecimal("2100"), null, new BigDecimal("2200"), BigDecimal.ZERO,
                new BigDecimal("100"), "BULK", Map.of());

        assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void acceptsOnlyStringNumberAndNullCellValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", "北安");
        values.put("score", 98.5);
        values.put("note", null);

        MarketRecord record = new MarketRecord("record-1", values);

        assertThat(record.values()).containsEntry("label", "北安")
                .containsEntry("score", 98.5)
                .containsEntry("note", null);
    }

    @Test
    void rejectsNonScalarCellValues() {
        assertThatThrownBy(() -> new MarketRecord("record-1", Map.of("active", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string, number or null");
        assertThatThrownBy(() -> new MarketRecord("record-1", Map.of("tags", List.of("a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string, number or null");
        assertThatThrownBy(() -> new MarketRecord("record-1", Map.of("nested", Map.of("a", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string, number or null");
    }

    @Test
    void voidsOnlyDraftOrReturnedMarketRecordsAndKeepsThemTerminal() {
        MarketMonitoringRecord draft = MarketMonitoringRecord.draft(
                "record-void", "CORN", "TRADER", "230200", LocalDate.of(2026, 8, 7),
                OffsetDateTime.parse("2026-08-07T08:00:00+08:00"), MarketTradeDirection.PURCHASE,
                new BigDecimal("2100"), null, new BigDecimal("20"), BigDecimal.ZERO,
                new BigDecimal("10"), "BULK", Map.of());

        assertThat(draft.voidRecord().status()).isEqualTo(MarketStatus.VOIDED);
        assertThat(draft.submit().returnForCorrection("补充").voidRecord().status())
                .isEqualTo(MarketStatus.VOIDED);
        assertThatThrownBy(() -> draft.submit().voidRecord())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.submit().approve().voidRecord())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.voidRecord().submit())
                .isInstanceOf(IllegalStateException.class);
    }
}
