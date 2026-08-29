package com.cofco.qiqihar.graintrade.analysis.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ObservableEndingInventorySource(
        String sourceDomain,
        String sourceRecordId,
        long sourceVersion,
        String businessIdentity,
        String regionCode,
        LocalDate dataCutoff,
        BigDecimal valueTonnes,
        OffsetDateTime approvedAt) {

    public ObservableEndingInventorySource {
        if (sourceDomain == null || sourceDomain.isBlank()
                || sourceRecordId == null || sourceRecordId.isBlank()
                || sourceVersion < 0
                || businessIdentity == null || businessIdentity.isBlank()
                || regionCode == null || regionCode.isBlank()
                || dataCutoff == null || valueTonnes == null || valueTonnes.signum() < 0
                || approvedAt == null) {
            throw new IllegalArgumentException("Observable ending inventory source is incomplete");
        }
    }
}
