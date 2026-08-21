package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportJobHistoryService {
    private static final Set<String> DOMAINS = Set.of("PRODUCTION", "MARKET", "LOGISTICS");
    private final ImportJobHistoryReader reader;
    private final AccessControl access;

    public ImportJobHistoryService(ImportJobHistoryReader reader, AccessControl access) {
        this.reader = reader;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public PagedResult<ImportJobView> list(String domain, int pageNumber, int pageSize) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        String domainCode = domain == null ? "" : domain.toUpperCase(Locale.ROOT);
        if (!DOMAINS.contains(domainCode) || pageNumber < 0 || pageSize < 1 || pageSize > 50) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_JOB_HISTORY_QUERY", "导入任务查询条件无效");
        }
        try {
            Math.multiplyExact((long) pageNumber, pageSize);
        } catch (ArithmeticException exception) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_JOB_HISTORY_QUERY", "导入任务查询条件无效");
        }
        return reader.findPage(principal.subjectId(), principal.workUnitCode(), domainCode,
                pageNumber, pageSize);
    }
}
