package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;

public interface ImportJobHistoryReader {
    PagedResult<ImportJobView> findPage(
            String subjectId, String workUnitCode, String domainCode,
            int pageNumber, int pageSize);
}
