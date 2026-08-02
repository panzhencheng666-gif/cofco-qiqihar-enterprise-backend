package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface ProductionRecordRepository {
    PagedResult<ProductionRecord> findPage(ProductionRecordQuery query);
    Optional<ProductionRecord> findById(String id);
    boolean isApplicableObjectType(String productCode, String objectTypeCode);
    void save(ProductionRecord record, Map<String, BigDecimal> costs,
              Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies, String actorId);
}
