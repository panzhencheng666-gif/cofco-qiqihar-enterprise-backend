package com.cofco.qiqihar.graintrade.market.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketPricingTest {

    @Test
    void calculates_actual_purchase_price_from_base_price_and_applicable_components() {
        assertThat(MarketPricing.actualPrice(
                        MarketTradeDirection.PURCHASE,
                        new BigDecimal("2346.0000"),
                        null,
                        new BigDecimal("36.0000"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("72.0000")))
                .isEqualByComparingTo("2466.0000");
    }

    @Test
    void calculates_actual_sale_price_from_sale_base_price_without_using_purchase_price() {
        assertThat(MarketPricing.actualPrice(
                        MarketTradeDirection.SALE,
                        new BigDecimal("2346.0000"),
                        new BigDecimal("2370.0000"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("8.0000"),
                        BigDecimal.ZERO))
                .isEqualByComparingTo("2390.0000");
    }

    @Test
    void rejects_negative_component_and_rounds_at_the_database_scale() {
        assertThat(MarketPricing.actualPrice(
                        MarketTradeDirection.PURCHASE,
                        new BigDecimal("1.00005"),
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .isEqualByComparingTo("1.0001");
        assertThatThrownBy(() -> MarketPricing.actualPrice(
                        MarketTradeDirection.PURCHASE, BigDecimal.ONE, null,
                        new BigDecimal("-0.0001"), BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(MarketValidationException.class);
    }

    @Test
    void preserves_facts_across_submit_return_and_revision() {
        MarketMonitoringRecord draft = MarketMonitoringRecord.draft("record-1", "CORN", "FEED_MILL", "230200",
                LocalDate.of(2026, 8, 1), OffsetDateTime.of(2026, 8, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8)),
                MarketTradeDirection.PURCHASE, new BigDecimal("2300"), null, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "BULK", Map.of("PURCHASE_VOLUME", new BigDecimal("12"), "MOISTURE", new BigDecimal("14.6")));
        MarketMonitoringRecord returned = draft.submit().returnForCorrection("补充凭证");
        MarketMonitoringRecord revised = returned.revise("FEED_MILL", "230200", LocalDate.of(2026, 8, 1),
                returned.reportedAt(), MarketTradeDirection.PURCHASE, new BigDecimal("2301"), null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "BULK", returned.facts());
        assertThat(revised.status()).isEqualTo(MarketStatus.DRAFT);
        assertThat(revised.facts()).containsEntry("PURCHASE_VOLUME", new BigDecimal("12.0000"));
    }
}
