package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HistoricalFormalSample(UUID samplePointId, String sampleName, String regionCode,
        String regionName, String address, String objectTypeCode, String objectTypeName,
        String productCode, String productName, String domain, OffsetDateTime retiredAt,
        int retirementYear, String retirementReason, String lastObservationId, OffsetDateTime lastObservedAt) {}
