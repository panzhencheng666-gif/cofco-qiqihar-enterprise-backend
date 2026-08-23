package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;

/** Serializes and rejects a second current canonical record for one sample-period business key. */
public interface BusinessPeriodRecordGuard {
    void lockAndRequireAvailable(ImportDraft draft);
}
