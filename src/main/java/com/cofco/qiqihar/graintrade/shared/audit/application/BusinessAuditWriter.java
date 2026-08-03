package com.cofco.qiqihar.graintrade.shared.audit.application;

import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;

public interface BusinessAuditWriter {
    void append(BusinessAuditEvent event);
}
