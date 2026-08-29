package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.UUID;

public interface ProductionRecordRepository {
    PagedResult<ProductionListRow> findPage(ProductionRecordQuery query);
    PagedResult<ProductionListRow> findLifecyclePage(ProductionRecordQuery query);
    Optional<ProductionRecord> findById(String id);
    boolean isApplicableObjectType(String productCode, String objectTypeCode);
    boolean isApplicableCultivar(String productCode, String cultivarCode);
    boolean isKnownRegion(String regionCode);
    boolean isPointWithinRegion(String regionCode, BigDecimal latitude, BigDecimal longitude);
    boolean areApplicableFacts(String productCode, String objectTypeCode, Map<String, Set<String>> factCodes);
    List<ProductionFactCategory> findFactCategories();
    List<ProductionFactDefinition> findFactDefinitions(String productCode, String objectTypeCode);
    ProductionRecord insert(ProductionRecord record, String actorId);
    ProductionRecord insertOfficialObservation(
            ProductionRecord record, UUID samplePointId, String actorId, Instant officialSavedAt);
    ProductionRecord updateFacts(ProductionRecord record, long expectedVersion, String actorId);
    ProductionRecord updateState(ProductionRecord record, long expectedVersion, String actorId);
    void linkApprovedSamplePoint(ProductionRecord record, String approvingActorId, Instant approvedAt);
}
