package com.cofco.qiqihar.graintrade.shared.audit.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditView;
import java.time.Instant;

public interface BusinessAuditReader {
    PagedResult<BusinessAuditView> find(
            String workUnitCode,
            String aggregateType,
            String actorSubjectId,
            Instant occurredFrom,
            Instant occurredTo,
            int pageNumber,
            int pageSize);
}
