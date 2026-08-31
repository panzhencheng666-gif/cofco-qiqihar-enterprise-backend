package com.cofco.qiqihar.graintrade.designsample.point.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface DesignSamplePointRepository {
    Optional<BoundaryContainment> coordinateBoundaryState(
            String regionCode, BigDecimal longitude, BigDecimal latitude);

    PagedResult<DesignSamplePointView> findPage(DesignSamplePointQuery query);

    Optional<DesignSamplePointView> find(UUID id);

    Optional<CreateResult> insert(
            UUID id,
            DesignSamplePointDraft draft,
            Map<String, JsonNode> normalizedValues,
            String sampleName,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            String idempotencyKey,
            String requestDigest,
            String actorSubjectId,
            Instant now);

    Optional<DesignSamplePointView> update(
            UUID id,
            long expectedVersion,
            DesignSamplePointDraft draft,
            Map<String, JsonNode> normalizedValues,
            String sampleName,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            String actorSubjectId,
            Instant now);

    boolean delete(UUID id, long expectedVersion);

    enum BoundaryContainment { UNAVAILABLE, OUTSIDE, INSIDE }

    record CreateResult(DesignSamplePointView point, boolean replayed) {}
}
