package com.cofco.qiqihar.graintrade.overview.application;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationalStorageFacilityRepository {
    List<OperationalFacilityCatalogue.StorageFacility> find(String regionCode, String productCode, LocalDate asOf);
    String latestSourceAsOf(String regionCode);
    List<String> railwayRegionCodes(String regionCode);
    boolean supportedRegion(String regionCode);
    Optional<OperationalFacilityCatalogue.StorageFacility> create(
            String facilityCode, OperationalStorageFacilityDraft draft,
            String workUnitCode, String actorSubjectId, Instant now);
    Optional<OperationalFacilityCatalogue.StorageFacility> update(
            String facilityCode, OperationalStorageFacilityDraft draft,
            String workUnitCode, String actorSubjectId, Instant now);
    Optional<String> archive(String facilityCode, long expectedVersion, String workUnitCode,
            String actorSubjectId, Instant now);
}
