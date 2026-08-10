package com.cofco.qiqihar.graintrade.market.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MarketObjectRepository {
    boolean valid(MarketObjectDraft draft);

    List<MarketObjectView> findAll(Set<String> regionCodes);

    Optional<MarketObjectView> find(String objectId);

    MarketObjectView insert(
            String objectId,
            MarketObjectDraft draft,
            String responsibleSubjectId,
            String responsiblePerson,
            Instant now);

    Optional<MarketObjectView> update(
            String objectId,
            long expectedVersion,
            MarketObjectDraft draft,
            String responsibleSubjectId,
            String responsiblePerson,
            String updatedBy,
            Instant now);
}
