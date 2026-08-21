package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record InventoryPositionObservation(
        String recordId,
        String subjectKey,
        String normalizedName,
        String normalizedContact,
        String regionCode,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDate observedOn,
        long version,
        OffsetDateTime approvedAt,
        BigDecimal valueTonnes) {

    public InventoryPositionObservation {
        if (recordId == null || recordId.isBlank()
                || subjectKey == null || subjectKey.isBlank()
                || normalizedName == null || normalizedName.isBlank()
                || normalizedContact == null || normalizedContact.isBlank()
                || regionCode == null || regionCode.isBlank()
                || (latitude == null) != (longitude == null)
                || observedOn == null || version < 0 || approvedAt == null
                || valueTonnes == null || valueTonnes.signum() < 0) {
            throw new IllegalArgumentException("Inventory position observation is invalid");
        }
    }

    public String positionKey() {
        String location = latitude == null
                ? "REGION|" + regionCode
                : latitude.stripTrailingZeros().toPlainString() + "|"
                        + longitude.stripTrailingZeros().toPlainString();
        return subjectKey + "|" + location;
    }
}
