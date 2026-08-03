package com.cofco.qiqihar.graintrade.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class LogisticsRecordTest {
    @Test
    void followsTheCompleteReviewStateMachineAndRequiresReturnReason() {
        LogisticsRecord draft = LogisticsRecord.draft("l1", "CORN", "P1", LocalDate.parse("2026-08-01"),
                OffsetDateTime.parse("2026-08-01T12:00:00+08:00"), "n1", "n2", "RAIL", "INFLOW",
                new BigDecimal("12.500"), new BigDecimal("80.25"), new BigDecimal("2.50"));
        LogisticsRecord pending = draft.submit();
        assertThat(pending.status()).isEqualTo(LogisticsStatus.PENDING_REVIEW);
        assertThat(pending.approve().status()).isEqualTo(LogisticsStatus.APPROVED);
        assertThatThrownBy(() -> pending.returnForCorrection(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(pending.returnForCorrection("补充运单").revise().status()).isEqualTo(LogisticsStatus.DRAFT);
    }
}
