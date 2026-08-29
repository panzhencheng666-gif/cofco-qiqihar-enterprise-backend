package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface MarketMonitoringRepository {
    PagedResult<MarketListRow> findPage(MarketRecordQuery query);
    PagedResult<MarketListRow> findLifecyclePage(MarketRecordQuery query);
    Optional<MarketMonitoringRecord> findById(String id);
    boolean isKnownRegion(String regionCode);
    boolean isPointWithinRegion(String regionCode, BigDecimal latitude, BigDecimal longitude);
    boolean isApplicableObjectType(String productCode, String objectTypeCode);
    boolean areApplicableFacts(String productCode, String objectTypeCode, Set<String> codes);
    List<MarketFactCategory> findFactCategories();
    List<MarketFactDefinition> findFactDefinitions(String productCode, String objectTypeCode);
    List<MarketCoreFieldDefinition> findCoreFields(String productCode, String objectTypeCode);
    Map<String, String> findExtensionCoreValues(String id);
    MarketMonitoringRecord insert(
            MarketMonitoringRecord record, String actorId, Map<String, String> extensionCoreValues);
    MarketMonitoringRecord insertOfficialObservation(
            MarketMonitoringRecord record, Map<String, String> extensionCoreValues,
            UUID samplePointId, String actorId, Instant officialSavedAt);
    MarketMonitoringRecord updateFacts(
            MarketMonitoringRecord record, long expectedVersion, String actorId,
            Map<String, String> extensionCoreValues);
    MarketMonitoringRecord updateState(
            MarketMonitoringRecord record, long expectedVersion, String actorId, Instant updatedAt);
    void linkApprovedSamplePoint(MarketMonitoringRecord record, Map<String, String> extensionCoreValues,
            String approvingActorId, Instant approvedAt);
}
