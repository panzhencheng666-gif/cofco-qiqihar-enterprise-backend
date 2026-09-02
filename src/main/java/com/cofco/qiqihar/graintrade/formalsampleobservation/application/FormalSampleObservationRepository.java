package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import com.cofco.qiqihar.graintrade.shared.application.FormalSampleIdentity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FormalSampleObservationRepository {
    List<EligibleFormalSample> findEligibleSamples(
            FormalSampleObservationDomain domain,
            String productCode,
            String regionCode,
            String objectTypeCode,
            String keywordPattern,
            LocalDate observedOn,
            Set<String> authorizedRegionCodes,
            String actorSubjectId,
            boolean administratorOverride);

    Optional<String> findObjectTypeName(
            FormalSampleObservationDomain domain, String productCode, String objectTypeCode);

    FormalSampleObservationHistoryPage findHistory(
            FormalSampleObservationDomain domain, UUID samplePointId, String productCode,
            int year, int pageNumber, int pageSize, Set<String> authorizedRegionCodes);

    void lockIdempotencyScope(String actorSubjectId, FormalSampleObservationDomain domain, String idempotencyKey);

    Optional<StoredFormalSampleObservation> findStored(
            String actorSubjectId, FormalSampleObservationDomain domain, String idempotencyKey);

    FormalSampleIdentity lockEligibleSample(
            FormalSampleObservationDomain domain, UUID samplePointId,
            String productCode, LocalDate observedOn, Set<String> authorizedRegionCodes);

    void store(
            String actorSubjectId, String idempotencyKey, String requestSha256,
            String sourceRecordId, FormalSampleObservationResult result);
}
