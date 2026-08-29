package com.cofco.qiqihar.graintrade.supplybalance.application;

import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceCalculator.RegionalProductionSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SupplyBalanceRepository {
    List<CountySource> countySources(
            String regionCode, int surveyYear, String productCode, Set<String> authorizedRegions);

    Optional<SavedBalance> upsert(
            String regionCode, int surveyYear, String productCode,
            Map<String, BigDecimal> manualValues, Map<String, String> notes,
            long expectedVersion, String actor, Instant now);

    List<HistoryEntry> history(String regionCode, int surveyYear, String productCode);

    record CountySource(
            String regionCode, String regionName, String prefectureCode,
            RegionalProductionSource production, Map<String, BigDecimal> manualValues,
            Map<String, String> notes, boolean balancePresent, long version, Instant updatedAt) {}

    record SavedBalance(long version, Instant updatedAt) {}

    record HistoryEntry(
            long sourceVersion, Map<String, BigDecimal> manualValues,
            Map<String, String> notes, String replacedBy, Instant replacedAt) {}
}
