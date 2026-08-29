package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface RegionalCropAnnualStatRepository {
    Optional<RegionDescriptor> region(String regionCode);

    boolean knownProduct(String productCode);

    List<RegionalCropAnnualStat> findAll(
            int dataYear, String productCode, String prefectureCode, Set<String> authorizedRegions);

    Optional<RegionalCropAnnualStat> upsert(
            String regionCode, int dataYear, String productCode,
            BigDecimal plantedAreaMu, BigDecimal yieldPerMuKg,
            long expectedVersion, String actor, Instant now);

    record RegionDescriptor(String code, String name, String parentCode, String administrativeLevel) {}
}
