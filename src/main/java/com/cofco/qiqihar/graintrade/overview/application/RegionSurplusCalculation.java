package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RegionSurplusCalculation(
        BigDecimal valueTonnes,
        long sourceCount,
        LocalDate dataCutoff,
        String coverageStatus,
        String calculationVersion,
        List<RegionSurplusAuditSource> auditSources) {
    public RegionSurplusCalculation {
        auditSources = List.copyOf(auditSources);
    }
}
