package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionRecordTest {

    @Test
    void separatesSurveyDateFromReportedAtAndCalculatesEstimatedOutput() {
        ProductionRecord record = ProductionRecord.draft(
                "record-1", "SOYBEAN", "FARMER", "230202", "黑农84",
                LocalDate.of(2026, 7, 30), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("100.00"), new BigDecimal("180.50"), Map.of());

        assertThat(record.surveyDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(record.reportedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T08:00:00+08:00"));
        assertThat(record.estimatedOutputKilograms()).isEqualByComparingTo("18050.0000");
    }

    @Test
    void rejectsBlankObjectTypesAndFutureSurveyDates() {
        assertThatThrownBy(() -> ProductionRecord.draft(
                "record-1", "CORN", "", "230202", null,
                LocalDate.of(2026, 8, 3), OffsetDateTime.parse("2026-08-02T08:00:00Z"),
                BigDecimal.ONE, BigDecimal.ONE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object type");
        assertThatThrownBy(() -> ProductionRecord.draft(
                "record-1", "CORN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 3), OffsetDateTime.parse("2026-08-02T08:00:00Z"),
                BigDecimal.ONE, BigDecimal.ONE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("survey date");
    }

    @Test
    void enforcesDraftSubmitReviewAndReturnStateTransitions() {
        ProductionRecord draft = ProductionRecord.draft(
                "record-1", "RICE", "VILLAGE_COMMITTEE", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00Z"),
                BigDecimal.ONE, BigDecimal.ONE, Map.of());

        ProductionRecord submitted = draft.submit();
        assertThat(submitted.status()).isEqualTo(ProductionStatus.PENDING_REVIEW);
        assertThat(submitted.approve().status()).isEqualTo(ProductionStatus.APPROVED);
        assertThat(submitted.returnForCorrection("缺少佐证").status()).isEqualTo(ProductionStatus.RETURNED);
        assertThatThrownBy(draft::approve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void revisionPreservesAllNormalizedFactsAndCannotReopenPendingOrApprovedRecords() {
        ProductionRecord draft = ProductionRecord.draft(
                "record-1", "RICE", "VILLAGE_COMMITTEE", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("1.0000"), new BigDecimal("2.0000"),
                Map.of("QUALITY_TEST", new BigDecimal("3.0000")),
                Map.of("COST_TEST", new BigDecimal("4.0000")),
                Map.of("INSURANCE_TEST", new BigDecimal("5.0000")),
                Map.of("SUBSIDY_TEST", new BigDecimal("6.0000")));

        ProductionRecord returned = draft.submit().returnForCorrection("补充依据");
        ProductionRecord revised = returned.revise(
                "RICE", "VILLAGE_COMMITTEE", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                new BigDecimal("2"), new BigDecimal("3"),
                returned.quality(), returned.costs(), returned.insurance(), returned.subsidies());

        assertThat(revised.status()).isEqualTo(ProductionStatus.DRAFT);
        assertThat(revised.costs()).containsEntry("COST_TEST", new BigDecimal("4.0000"));
        assertThat(revised.insurance()).containsEntry("INSURANCE_TEST", new BigDecimal("5.0000"));
        assertThat(revised.subsidies()).containsEntry("SUBSIDY_TEST", new BigDecimal("6.0000"));
        assertThatThrownBy(() -> draft.submit().revise(
                "RICE", "VILLAGE_COMMITTEE", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                BigDecimal.ONE, BigDecimal.ONE, Map.of(), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.submit().approve().revise(
                "RICE", "VILLAGE_COMMITTEE", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                BigDecimal.ONE, BigDecimal.ONE, Map.of(), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalizesDecimalsBeforeCalculationAndRejectsDatabaseOverflow() {
        ProductionRecord record = ProductionRecord.draft(
                "record-1", "CORN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("1.23456"), new BigDecimal("2.34567"), Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(record.cultivatedAreaMu()).isEqualByComparingTo("1.2346");
        assertThat(record.yieldPerMuKilograms()).isEqualByComparingTo("2.3457");
        assertThat(record.estimatedOutputKilograms()).isEqualByComparingTo("2.8960");
        assertThatThrownBy(() -> ProductionRecord.draft(
                "record-1", "CORN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("100000000000000.0000"), BigDecimal.ONE,
                Map.of(), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }
}
