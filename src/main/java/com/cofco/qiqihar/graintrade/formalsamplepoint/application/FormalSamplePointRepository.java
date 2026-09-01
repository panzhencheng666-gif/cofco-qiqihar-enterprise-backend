package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FormalSamplePointRepository {
    PagedResult<FormalSamplePointView> findPage(
            String regionCode, String keyword, int pageNumber, int pageSize,
            Set<String> authorizedRegionCodes);

    Optional<FormalSamplePointView> find(UUID id);

    Optional<BoundaryContainment> coordinateBoundaryState(
            String regionCode, BigDecimal longitude, BigDecimal latitude);

    boolean isSupportedObjectType(String objectTypeCode);

    Optional<FormalSamplePointView> insert(
            UUID id, FormalSamplePointDraft draft, String actorSubjectId,
            LocalDate effectiveFrom, Instant now);

    Optional<FormalSamplePointView> update(
            UUID id, long expectedVersion, FormalSamplePointDraft draft,
            String actorSubjectId, Instant now);

    DeleteResult delete(
            UUID id, long expectedVersion, String expectedRegionCode, String actorSubjectId);

    enum DeleteResult {
        DELETED,
        NOT_FOUND,
        VERSION_CONFLICT,
        REGION_CONFLICT,
        ACCESS_DENIED,
        ACCESS_REGION_DENIED,
        NETWORK_REFERENCED,
        HISTORICAL_REFERENCE
    }

    enum BoundaryContainment { UNAVAILABLE, OUTSIDE, INSIDE }
}
