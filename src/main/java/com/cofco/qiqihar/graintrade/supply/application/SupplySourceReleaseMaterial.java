package com.cofco.qiqihar.graintrade.supply.application;

import java.math.BigDecimal;

public record SupplySourceReleaseMaterial(
        boolean contextExists,
        boolean semanticsApplicable,
        UpstreamFact upstreamFact,
        SourceMapping mapping,
        ExistingRelease existingRelease) {

    public record UpstreamFact(BigDecimal value, String unitCode, String directionCode) {}

    public record SourceMapping(
            long id,
            int version,
            String sourceUnitCode,
            String accountUnitCode,
            String conversionRule,
            BigDecimal conversionFactor) {}

    public record ExistingRelease(String id, boolean matchesContext) {}
}
