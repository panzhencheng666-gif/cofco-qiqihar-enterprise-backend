package com.cofco.qiqihar.graintrade.shared.audit.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditView;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessAuditQueryService {
    private final BusinessAuditReader reader;
    private final AccessControl access;

    public BusinessAuditQueryService(BusinessAuditReader reader, AccessControl access) {
        this.reader = reader;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public PagedResult<BusinessAuditView> events(
            String requestedWorkUnit,
            String aggregateType,
            String actorSubjectId,
            Instant occurredFrom,
            Instant occurredTo,
            int pageNumber,
            int pageSize) {
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100
                || occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new ClientRequestException("INVALID_AUDIT_QUERY", "Audit query parameters are invalid");
        }
        SecurityPrincipal principal = access.require("AUDIT_READ", null);
        String workUnit = requestedWorkUnit == null || requestedWorkUnit.isBlank()
                ? principal.workUnitCode()
                : requestedWorkUnit.trim();
        if (!workUnit.equals(principal.workUnitCode()) && !principal.roleCodes().contains("SYSTEM_ADMIN")) {
            throw new AccessDeniedException("ACCESS_WORK_UNIT_DENIED", "Work unit is outside the assigned scope");
        }
        return reader.find(
                workUnit,
                normalize(aggregateType),
                normalize(actorSubjectId),
                occurredFrom,
                occurredTo,
                pageNumber,
                pageSize);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
