package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record RegionSurplusAuditSource(
        String sourceDomain,
        String sourceRecordId,
        long sourceVersion,
        String subjectKey,
        String inventoryHolderKey,
        String cargoOwnerKey,
        String ownershipType,
        String regionCode,
        LocalDate dataCutoff,
        BigDecimal valueTonnes,
        OffsetDateTime approvedAt,
        boolean adopted,
        String adoptionReason) {}
