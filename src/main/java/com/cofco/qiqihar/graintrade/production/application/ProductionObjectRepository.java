package com.cofco.qiqihar.graintrade.production.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ProductionObjectRepository {
    boolean valid(ProductionObjectDraft draft);

    boolean conflicts(String objectId, ProductionObjectDraft draft);

    List<ProductionObjectView> findAll(Set<String> regionCodes);

    Optional<ProductionObjectView> find(String objectId);

    ProductionObjectView insert(
            String objectId,
            ProductionObjectDraft draft,
            String responsibleSubjectId,
            String responsiblePerson,
            Instant now);

    Optional<ProductionObjectView> update(
            String objectId,
            long expectedVersion,
            ProductionObjectDraft draft,
            String responsibleSubjectId,
            String responsiblePerson,
            String updatedBy,
            Instant now);
}
