package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

public interface ProductionRecordRepository {
    PagedResult<ProductionListRow> findPage(ProductionRecordQuery query);
    Optional<ProductionRecord> findById(String id);
    boolean isApplicableObjectType(String productCode, String objectTypeCode);
    boolean isApplicableCultivar(String productCode, String cultivarCode);
    boolean isKnownRegion(String regionCode);
    boolean areApplicableFacts(String productCode, String objectTypeCode, Map<String, Set<String>> factCodes);
    List<ProductionFactCategory> findFactCategories();
    List<ProductionFactDefinition> findFactDefinitions(String productCode, String objectTypeCode);
    ProductionRecord insert(ProductionRecord record, String actorId);
    ProductionRecord updateFacts(ProductionRecord record, long expectedVersion, String actorId);
    ProductionRecord updateState(ProductionRecord record, long expectedVersion, String actorId);
}
