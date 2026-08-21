package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import java.util.List;

/** Keeps shared workbook/job mechanics separate from each domain's existing save/submit rules. */
interface OperationalReturnedCorrectionDomain {
    String domainCode();
    String domainLabel();
    BusinessImportWorkbook.Template workbook(String productCode);
    List<ReturnedRecord> returned(String productCode);
    String correctAndSubmit(String productCode, String originalId, long originalVersion,
            List<String> values);

    record ReturnedRecord(String id, long version, List<String> values) {
        public ReturnedRecord { values = List.copyOf(values); }
    }
}
